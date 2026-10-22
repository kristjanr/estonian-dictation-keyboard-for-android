# tekstiks-proxy

POST an audio file, get Estonian text back. Built for iOS Shortcuts, which can
send a file and read the reply but can't do anything more involved.

```
curl --data-binary @memo.m4a -H "Authorization: Bearer $TOKEN" https://<host>/transcribe
→ Tere ma jõuan koju umbes kell kuus.
```

## How it works

The service decodes the upload with ffmpeg to 16 kHz mono PCM, then runs
TalTech's streaming Zipformer
([`streaming-zipformer-large.et-en`](https://huggingface.co/TalTechNLP/streaming-zipformer-large.et-en),
MIT licence) in-process with sherpa-onnx. It's one bilingual Estonian/English
model, and it adds punctuation itself. The int8 weights (~156 MB) are baked
into the image at a pinned revision, and they're loaded and warmed up before
the server opens its port. So no request ever waits for the model.

The name is historical. It started as a relay to tekstiks.ee's live
WebSocket, and that path survives as `BACKEND=tekstiks`. It depends on an
undocumented endpoint that has had outages, so it's not the default.

Measured in the container, capped at 1 CPU and 1 GB:

| Audio | Response time |
|---|---|
| 3 s | 0.6 s |
| 9 s | 1.0 s |
| 21 s | 2.2 s |
| 71 s | 7.3 s |

Memory peaks at ~270 MB. A second CPU barely helps.

## API

`POST /transcribe` returns `text/plain; charset=utf-8`.

- Body: the raw audio file, or multipart form data with a `file` field. Any
  format ffmpeg reads works (m4a, mp3, wav, ogg, opus, …).
- Auth: `Authorization: Bearer <PROXY_TOKEN>` or `?token=<PROXY_TOKEN>`.
- **Every failure body starts with `ERROR:`.** Shortcuts passes on the body
  of a failed request as if it were the result, so check for that prefix
  before sending the text anywhere. The cases are 401 bad token, 415
  undecodable audio, 422 no speech recognised, 502 recognition failure, and
  503 tekstiks.ee down (that last one only with `BACKEND=tekstiks`).

| Env var | Default | |
|---|---|---|
| `PROXY_TOKEN` | *(none)* | Set this. Without it the URL is open to anyone. |
| `BACKEND` | `local` | `local` runs the model in-process; `tekstiks` relays to tekstiks.ee. |
| `MODEL_DIR` | `/model` | Directory with `encoder/decoder/joiner(.int8).onnx` and `tokens.txt`. |
| `ASR_THREADS` | `1` | |
| `PORT` | `8080` | |
| `MAX_UPLOAD_MB` | `25` | |
| `MAX_AUDIO_SECONDS` | `600` | Longer audio is truncated. |

## Run locally

```bash
pip install -r requirements.txt        # plus ffmpeg on PATH
# download the four model files into ./model (see the Dockerfile), then:
MODEL_DIR=./model PROXY_TOKEN=change-me python app.py
```

## Deploy to Google Cloud Run

One-time setup by a human: install the
[gcloud CLI](https://cloud.google.com/sdk/docs/install), run
`gcloud auth login`, and create a
[billing account](https://console.cloud.google.com/billing). Then:

```bash
./deploy.sh                    # always warm: ~1 s answers, ~$8-13/month
MIN_INSTANCES=0 ./deploy.sh    # free, but ~7 s to load the model after idle
```

The script creates a project, links billing, enables the APIs, builds and
deploys, runs a test transcription, and prints the URL and token for the
Shortcut. It's safe to re-run, and it keeps the same URL and token.

**Why always warm costs money:** loading the model takes ~7 s, so a service
that scales to zero makes the first message after idle wait that long. With
one instance kept running (1 vCPU, 1 GiB), Cloud Run bills idle time at its
idle rate. That's about $13/month before the free tier, ~$8 after.

`--allow-unauthenticated` turns off Google's own sign-in check, because
Shortcuts can't do Google sign-in. The `PROXY_TOKEN` takes its place.

## iOS Shortcut

1. **Record Audio**. Set *Start Recording* to *Immediately*, and *Finish
   Recording* to *On Tap*.
2. **Get Contents of URL**
   - URL: `https://<your-service>/transcribe`
   - Method: `POST`
   - Headers: `Authorization` = `Bearer <your token>`
   - Request Body: `File` → *Recorded Audio*
3. **If** *Contents of URL* **begins with** `ERROR`: **Show** *Contents of
   URL*, then **Stop This Shortcut**. **End If**.
4. **Send Message** (WhatsApp) with *Contents of URL*.
