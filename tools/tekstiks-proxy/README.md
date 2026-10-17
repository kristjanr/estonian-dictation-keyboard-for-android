# tekstiks-proxy

POST an audio file, get Estonian text back. A thin bridge to
[tekstiks.ee](https://tekstiks.ee)'s live recogniser for clients that can't
speak WebSocket, such as iOS Shortcuts.

```
curl --data-binary @memo.m4a -H "Authorization: Bearer $TOKEN" https://<host>/transcribe
→ Tere! Ma jõuan koju umbes kell kuus.
```

## Why it exists

tekstiks.ee does live recognition only over a WebSocket
(`wss://tekstiks.ee/asr/v2`). Shortcuts' "Get Contents of URL" can only make
one request and read one response. This service turns one into the other: it
decodes the upload with ffmpeg to 16 kHz mono PCM, streams it up, and returns
the transcript once it stops changing.

**This uses an undocumented endpoint** of TalTech's public service. It needs
no login today, but it could change or close at any time. Keep usage personal
and light: the site asks for at most 10 file uploads a day.

## API

`POST /transcribe` returns `text/plain; charset=utf-8`.

- Body: the raw audio file, or multipart form data with a `file` field. Any
  format ffmpeg reads works (m4a, mp3, wav, ogg, opus, …).
- Auth: `Authorization: Bearer <PROXY_TOKEN>` or `?token=<PROXY_TOKEN>`.
- `?lang=` defaults to `et`. The other options are
  `fastconformer_transducer_en_80ms` and `fastconformer_transducer_en_1040ms`
  (English). The `et` model also handles mixed Estonian and English.
- Errors: 401 bad token, 415 undecodable audio, 502 upstream failure.

Measured: a 9 s memo returns in ~5 s; a 71 s memo in ~11 s. About 2.5 s of
that is fixed: the wait that confirms the transcript has finished.

| Env var | Default | |
|---|---|---|
| `PROXY_TOKEN` | *(none)* | Set this. Without it the URL is open to anyone. |
| `PORT` | `8080` | |
| `MAX_UPLOAD_MB` | `25` | |
| `MAX_AUDIO_SECONDS` | `600` | Longer audio is truncated. |
| `QUIET_SECONDS` | `2.5` | How long the transcript must be unchanged before returning. |

## Run locally

```bash
pip install -r requirements.txt   # needs ffmpeg on PATH too
PROXY_TOKEN=change-me python app.py
```

## Deploy to Google Cloud Run

Cloud Run fits this well. It needs a container (for ffmpeg), it scales to
zero when idle, and the free tier covers personal use many times over. A cold
start adds a few seconds to the first request after a quiet spell.

From [Cloud Shell](https://shell.cloud.google.com), with this repo cloned:

```bash
TOKEN=$(openssl rand -hex 16); echo "Your token: $TOKEN"
gcloud run deploy tekstiks-proxy \
  --source tools/tekstiks-proxy \
  --region europe-north1 \
  --allow-unauthenticated \
  --set-env-vars PROXY_TOKEN=$TOKEN \
  --memory 512Mi --timeout 300
```

`--allow-unauthenticated` turns off Google's own sign-in check, because
Shortcuts can't do Google sign-in. The `PROXY_TOKEN` takes its place.

The command prints the service URL. Any host that runs a Dockerfile (Fly.io,
Railway, a VPS) works the same way.

## iOS Shortcut

1. **Record Audio**. Set *Start Recording* to *Immediately*, and *Finish
   Recording* to *On Tap*.
2. **Get Contents of URL**
   - URL: `https://<your-service>/transcribe`
   - Method: `POST`
   - Headers: `Authorization` = `Bearer <your token>`
   - Request Body: `File` → *Recorded Audio*
3. **Send Message** (WhatsApp): set the message to *Contents of URL* and
   pick the recipient. Or use **Copy to Clipboard** / **Show Result** to
   check the text before sending.

You can also start it from a Share Sheet for voice memos: set the Shortcut
to accept audio files, and pass *Shortcut Input* as the request body.
