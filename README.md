# estonian-dictation-keyboard-for-android

An Android keyboard (IME) that transcribes Estonian and English speech fully
on-device — no cloud, no account, no word quota.

- **Streaming**: partial words appear at the cursor as you speak
  (TalTech streaming Zipformer via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)).
- **Optional accurate second pass**: each finished segment is re-transcribed
  with TalTech's finetuned Whisper-turbo and the provisional text is replaced.

See [PLAN.md](PLAN.md) for models, runtime choices, phases and open questions,
and [RESEARCH.md](RESEARCH.md) for the verified facts behind it (licences,
model card details, sherpa-onnx NPU support).

## Status

The keyboard is written but has never run: it was built in an environment with
no Android SDK and no phone, so it is **unbuilt and untested on a device**.
What *is* verified is listed under [Building](#building).

| Plan phase | State |
|---|---|
| 0 — prove the models on the laptop | Not started; needs your voice and your machine |
| 1 — Android skeleton | Code written: sherpa-onnx wiring, model download, streaming loop |
| 2 — keyboard | Code written: IME, composing-text pipeline, companion app |
| 3 — two-pass Whisper | Seam only (`SecondPass`, `AudioSegmentBuffer`); gated on Phase 0 |
| 4 — polish | Not started |

## Building

The sherpa-onnx runtime is not on Maven Central, so it is vendored rather than
committed:

```bash
./scripts/fetch-sherpa-onnx.sh    # JNI libraries + Kotlin API, pinned to v1.13.7
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open the app: grant the microphone, download the model, enable the
keyboard. To skip the in-app download on every reinstall:

```bash
./scripts/fetch-models.sh --push  # downloads to ./models/, sideloads via run-as
```

Verified so far: `scripts/fetch-sherpa-onnx.sh` runs end to end; the ASR core
type-checks against the real sherpa-onnx v1.13.7 Kotlin API; 22 unit tests
covering the endpoint state machine, punctuation handling and the segment
buffer pass on the JVM. Not verified: the Gradle build itself, resource
inflation, and everything that needs a device.

Models are published by [TalTech NLP](https://huggingface.co/TalTechNLP).

## Licence

MIT — see [LICENSE](LICENSE). The models carry their own licences (MIT /
Apache-2.0); see RESEARCH.md.
