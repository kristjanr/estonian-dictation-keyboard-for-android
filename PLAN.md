# Estonian Dictation Keyboard for Android — Project Plan

_Last updated: 2026-09-06_

## Goal

An Android keyboard (IME) that transcribes Estonian (and English) speech fully on-device, with:

- **Streaming** partial words at the cursor as the user speaks (TalTech streaming Zipformer via sherpa-onnx)
- **Optional accurate second pass** that re-transcribes each finished segment with TalTech's finetuned Whisper-turbo and replaces the provisional text
- No cloud, no account, no word quota

Reference product: [Lausu](https://lausu.ee) (iOS, Whisper-based, non-streaming, requires iPhone 14+). This project is the Android, streaming, self-owned equivalent.

## Non-goals (for now)

- iOS (keyboard-extension memory cap makes this a separate problem)
- Training or finetuning any model
- Long-recording transcription (voice memos, meetings)

---

## Models

All models are published by TalTech NLP on Hugging Face. **No export is needed for the Zipformer; the ONNX files are published directly.**

| Role | Model | Notes |
|---|---|---|
| Streaming (pass 1) | `TalTechNLP/streaming-zipformer-large.et-en` | ~150M params, ET+EN, punctuation built in, MIT. Files: `encoder.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt`. Updated Sept 2026. |
| Streaming, numbers variant | `TalTechNLP/streaming-zipformer-large.et-en.w2n` | Same model with words-to-numbers baked in (assumed from name — verify on model card). Prefer for dictation. |
| Accurate (pass 2) | `TalTechNLP/whisper-large-v3-turbo-et-verbatim-2604` | ~800M params, finetuned on 1400 h manually transcribed verbatim Estonian. Needs export to sherpa-onnx ONNX format. **Check licence on model card before distributing.** |
| Smaller streaming fallback | `TalTechNLP/streaming-zipformer.et-en` | If the large model is too heavy on target phones. |

Author's reference recogniser config (from `alumae/kiirkirjutaja/main.py`, which uses the same Zipformer model in production for live TV subtitles):

```python
sherpa_onnx.OnlineRecognizer.from_transducer(
    tokens="tokens.txt",
    encoder="encoder.onnx",
    decoder="decoder.onnx",
    joiner="joiner.onnx",
    num_threads=2,
    sample_rate=16000,
    feature_dim=80,
    enable_endpoint_detection=True,
    rule1_min_trailing_silence=5.0,
    rule2_min_trailing_silence=2.0,
    rule3_min_utterance_length=300,
    decoding_method="modified_beam_search",
)
```

Every parameter has a 1:1 equivalent in the sherpa-onnx Kotlin API.

Post-processing: kiirkirjutaja's old compound-word, words-to-numbers and punctuation modules are all disabled in current code; punctuation comes from the model. The only remaining step is attaching `,.!?` tokens to the preceding word. Expect near-zero Estonian-specific text logic in the app.

## Runtime

**sherpa-onnx** (k2-fsa) for everything:

- `OnlineRecognizer` → Zipformer streaming
- `OfflineRecognizer` → Whisper (non-streaming, segment at a time)
- Bundled Silero VAD for endpointing the Whisper segments
- Prebuilt Android JNI libs / AAR in releases; Kotlin API
- Existing Android demos: streaming ASR, two-pass ASR (streaming + offline re-decode) — use the two-pass demo as the architectural template

Do **not** build the C++ from source unless a needed feature is missing.

---

## Phase 0 — Prove the models on the laptop

**Goal:** confirm quality and speed on my own voice before writing any Android code. Also yields a usable desktop dictation tool.

- [ ] `pip install sherpa-onnx` (Linux aarch64 wheel; Asahi/Omarchy)
- [ ] Download Zipformer files from HF (`git lfs` or `huggingface-cli`)
- [ ] Run the sherpa-onnx microphone streaming example with the config above; dictate for 10 minutes; note error types
- [ ] Export Whisper-turbo-et-verbatim-2604 using sherpa-onnx's Whisper export script (accepts a local HF checkpoint path); produce int8 variant
- [ ] Run `OfflineRecognizer` on recordings of the same sentences; compare accuracy by ear and measure seconds-per-sentence on the M2 (phone will be ~3–6× slower)
- [ ] Record 20 phone-mic-quality test sentences (names, numbers, mixed ET/EN, quiet room, kitchen noise) as a fixed regression set for later phases

**Decision gate:** if Whisper's accuracy gain on phone-style audio is small, defer Phase 3 (two-pass) indefinitely and ship Zipformer-only.

**Known risk:** Android Studio has no official Linux-arm64 build. Plan for Gradle CLI + `adb` to a physical phone; no emulator.

## Phase 1 — Android skeleton

**Goal:** Estonian streaming ASR running in a plain Android app.

- [ ] Clone `k2-fsa/sherpa-onnx` `android/` demos: streaming ASR and two-pass
- [ ] Build and deploy both unchanged with prebuilt JNI libs; confirm they run on the target phone
- [ ] Replace demo model files with TalTech Zipformer; adapt config to match the reference block above
- [ ] Model delivery:
  - Zipformer (~150 MB int8): bundle in APK or download on first launch (decide based on APK size tolerance)
  - Whisper (~800 MB int8): **always** download on first run to app-private storage, with progress UI and resume. Never ship in the APK.
- [ ] Verify partial results, endpoint detection and reset behave like `kiirkirjutaja/asr.py`: feed ~100 ms chunks, decode while `isReady`, emit on text change, reset on endpoint

## Phase 2 — Make it a keyboard

**Goal:** dictation into any text field. This is the bulk of the work.

- [ ] `InputMethodService` with minimal keyboard view: mic button, ET/EN toggle, backspace, space, `.` `,` `?` `!`, "switch keyboard"
- [ ] Reference for IME lifecycle and edge cases: `Kaljurand/K6nele` source (UI is dated; lifecycle handling is correct)
- [ ] Text pipeline using the composing-text mechanism:
  - streaming partials → `setComposingText()` (underlined, mutable)
  - endpoint → `commitText()`
  - this maps 1:1 to the recogniser's revision stream and makes the Phase 3 replacement free
- [ ] Audio: `AudioRecord`, 16 kHz mono PCM16 → float. `RECORD_AUDIO` granted once via the companion activity (an IME cannot prompt for permissions itself)
- [ ] Threading: recogniser on a dedicated thread/coroutine; load model lazily on first mic press; release after idle timeout so the IME process stays small when not dictating
- [ ] Handle Android killing the IME service under memory pressure: reload transparently, never crash the host app's keyboard
- [ ] Companion app: permission grant, model download/status, settings, licences/credits (TalTech, sherpa-onnx)
- [ ] Bonus (low cost, high value): implement `RecognitionService` so every app's built-in mic button — and Kõnele — can use this engine

## Phase 3 — Two-pass (streaming + Whisper)

**Goal:** Whisper-level accuracy without losing streaming feedback.

- [ ] Buffer raw audio of the current segment while streaming
- [ ] On endpoint: if Whisper enabled and loaded → transcribe buffer → replace composing text → commit. Otherwise commit Zipformer text as-is
- [ ] Timeout: if Whisper exceeds N seconds, commit Zipformer text and drop the second pass
- [ ] Settings: `fast` / `accurate` / `both`; battery-aware fallback (skip pass 2 below X% or on battery saver)
- [ ] Benchmark on target phone: RAM peak, seconds-per-sentence, thermal after 5 minutes of continuous dictation

## Phase 4 — Polish and daily use

- [ ] Local-only history (opt-in), reuse previous dictations
- [ ] Auto-capitalisation after sentence end; cursor-position edge cases (mid-text insertion, selection replace)
- [ ] Try the `.w2n` variant; compare number handling
- [ ] One week of daily use before adding anything else

---

## Decisions

| Decision | Choice | Why |
|---|---|---|
| Runtime | sherpa-onnx | One library covers both model types, Kotlin API, existing two-pass Android demo |
| Language | Kotlin | Matches sherpa-onnx demos and Kõnele |
| Min SDK | Android 10 (API 29) | Keeps audio + permission code simple |
| Text insertion | composing text + commit | Native fit for streaming revisions and two-pass replacement |
| Whisper delivery | first-run download | 800 MB does not belong in an APK |
| Target phone | ≥ 8 GB RAM (12 GB comfortable) | Whisper-turbo int8 resident in RAM alongside host app |

## Open questions

- Whisper-turbo-et licence terms for redistribution/download-on-first-run
- Whether the `.w2n` variant is words-to-numbers as assumed
- Real Whisper-turbo latency on the chosen Android phone (sherpa-onnx NPU acceleration does not cover Whisper as far as known — verify current state)
- Whether phone-mic audio changes the Zipformer-vs-Whisper accuracy gap reported for broadcast speech (TalTech EACL 2026: streaming Zipformer "very close" to offline finetuned Whisper)

## References

- Models: https://huggingface.co/TalTechNLP
- Production use of the Zipformer model: https://github.com/alumae/kiirkirjutaja (see `main.py`, `asr.py`)
- Runtime: https://github.com/k2-fsa/sherpa-onnx (Android demos under `android/`, Flutter under `flutter-examples/`)
- IME reference: https://github.com/Kaljurand/K6nele
- TalTech lab services and app source links: https://bark.cs.taltech.ee/
- Paper (Zipformer vs Whisper for Estonian): https://aclanthology.org/2026.eacl-demo.40.pdf
- Comparable product: https://lausu.ee
