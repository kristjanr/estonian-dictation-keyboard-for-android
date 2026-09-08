# Estonian Dictation Keyboard for Android — Project Plan

_Last updated: 2026-09-08. Facts marked ✅ were verified on 2026-09-06 against the source; see RESEARCH.md for details and links._

_Phases 1 and 2 are written but have never been built or run — see the status table in README.md and the context in CLAUDE.md._

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

| Role | Model | Licence | Notes |
|---|---|---|---|
| Streaming (pass 1) | `TalTechNLP/streaming-zipformer-large.et-en` | MIT ✅ | ~150M params, ET+EN, capitalisation + punctuation built in. Files: `encoder.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt`. Used in production by `alumae/kiirkirjutaja` since 2026-07-03 ✅ |
| Streaming, numbers variant | `TalTechNLP/streaming-zipformer-large.et-en.w2n` | Apache-2.0 ✅ | Vocabulary contains digit tokens `0`–`9`, consistent with words-to-numbers output ("kakskümmend viis" → "25") ✅. Semantics not documented on the card — confirm empirically in Phase 0. |
| Accurate (pass 2) | `TalTechNLP/whisper-large-v3-turbo-et-verbatim-2604` | MIT ✅ | 0.8B params. Trained on 1400 h manual verbatim + ~4000 h auto-transcribed ERR news + ~500 h English podcasts/YouTube; explicitly targets English terms inside Estonian sentences (tech talk) ✅. Card ships **ct2** (faster-whisper) and **GGML** (whisper.cpp, added 2026-06-17) exports ✅. Redistribution / download-on-first-run is fine under MIT with attribution. |
| Smaller streaming fallback | `TalTechNLP/streaming-zipformer.et-en` | MIT ✅ | Card ships `*.int8.onnx` files. Powers the browser app https://eestiasr.vercel.app/ ✅ |

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
- **Qualcomm NPU (QNN) support is recent and relevant** ✅: v1.13.3 added streaming Zipformer transducer on QNN with an Android demo; v1.13.4 added Whisper on QNN plus a Whisper→QNN export script. Caveats: QNN models take fixed-length input (padded/truncated to a max duration baked into the model), need the QNN SDK at build time, and only run on Snapdragon. Treat as a Phase 3 optimisation, not a Phase 1 dependency.

Do **not** build the C++ from source unless a needed feature is missing.

---

## Phase 0 — Prove the models on the laptop

**Goal:** confirm quality and speed on my own voice before writing any Android code. Also yields a usable desktop dictation tool.

- [ ] `pip install sherpa-onnx` (Linux aarch64 wheel; Asahi/Omarchy)
- [ ] Download Zipformer files from HF (`git lfs` or `huggingface-cli`)
- [ ] Run the sherpa-onnx microphone streaming example with the config above; dictate for 10 minutes; note error types
- [ ] Export Whisper-turbo-et-verbatim-2604 with `sherpa-onnx/scripts/whisper/export-onnx.py` (accepts a local HF checkpoint path); produce int8 variant. Shortcut for a first accuracy check: the card's ready-made GGML file runs directly in whisper.cpp, no export needed
- [ ] Dictate 10 sentences with numbers ("kakskümmend viis eurot", "kell pool kolm") through both Zipformer variants; confirm `.w2n` emits digits
- [ ] Run `OfflineRecognizer` on recordings of the same sentences; compare accuracy by ear and measure seconds-per-sentence on the M2 (phone will be ~3–6× slower)
- [ ] Record 20 phone-mic-quality test sentences (names, numbers, mixed ET/EN, quiet room, kitchen noise) as a fixed regression set for later phases

**Decision gate:** if Whisper's accuracy gain on phone-style audio is small, defer Phase 3 (two-pass) indefinitely and ship Zipformer-only.

**Build host:** macOS on Apple Silicon. Android Studio ships an official build for it and arm64 system images run natively, so the emulator is available — the first compile, resource inflation and keyboard layout can all be checked without a phone. (This was a real constraint while the plan assumed Linux-arm64, where Android Studio has no official build and there is no usable emulator; on that host it is Gradle CLI + `adb` to a physical phone.)

The phone is still required for what actually matters: real microphone audio, latency and thermals. It is just no longer required to find the first bug.

## Phase 1 — Android skeleton

**Goal:** Estonian streaming ASR running in a plain Android app.

- [x] ~~Clone `k2-fsa/sherpa-onnx` `android/` demos~~ — superseded: the app was written directly against the sherpa-onnx Kotlin API, with the two-pass demo as the architectural reference rather than a starting point
- [ ] First compile and run on the emulator: does it build, do the layouts inflate, does the keyboard appear
- [ ] Deploy to the target phone and confirm the prebuilt JNI libs load
- [x] ~~Replace demo model files with TalTech Zipformer~~ — config written to match the reference block above; unverified against a running model
- [ ] Model delivery:
  - Zipformer (~150 MB int8): bundle in APK or download on first launch (decide based on APK size tolerance)
  - Whisper (~800 MB int8): **always** download on first run to app-private storage, with progress UI and resume. Never ship in the APK.
- [ ] Verify partial results, endpoint detection and reset behave like `kiirkirjutaja/asr.py`: feed ~100 ms chunks, decode while `isReady`, emit on text change, reset on endpoint

## Phase 2 — Make it a keyboard

**Goal:** dictation into any text field. This is the bulk of the work.

_Every item below except the last two is written; none is verified on a device. Boxes stay unticked until something has actually run._

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
| Target phone | ≥ 8 GB RAM (12 GB comfortable); prefer Snapdragon 8 Gen 3 / 8 Elite | Whisper-turbo int8 resident in RAM alongside host app; Snapdragon keeps the sherpa-onnx QNN path open for both models |
| Model files | Not in git; `models/` is gitignored; download script in `scripts/` | Agent environments may block huggingface.co — download on the laptop, then point the agent at local files |

## Open questions

- Actual Whisper-turbo latency on the chosen phone, CPU path vs QNN path (measure in Phase 3)
- Whether phone-mic audio changes the Zipformer-vs-Whisper accuracy gap reported for broadcast speech (TalTech EACL 2026: streaming Zipformer "very close" to offline finetuned Whisper)
- QNN fixed-input-length constraint: what max duration to bake in for dictation segments (10 s? 20 s?)

Resolved 2026-09-06 (see RESEARCH.md): Whisper licence = MIT; `.w2n` vocab has digit tokens; sherpa-onnx has Whisper and streaming Zipformer on Qualcomm NPU as of v1.13.3–1.13.4.

## References

- Models: https://huggingface.co/TalTechNLP
- Production use of the Zipformer model: https://github.com/alumae/kiirkirjutaja (see `main.py`, `asr.py`)
- Runtime: https://github.com/k2-fsa/sherpa-onnx (Android demos under `android/`, Flutter under `flutter-examples/`)
- IME reference: https://github.com/Kaljurand/K6nele
- TalTech lab services and app source links: https://bark.cs.taltech.ee/
- Paper (Zipformer vs Whisper for Estonian): https://aclanthology.org/2026.eacl-demo.40.pdf
- Comparable product: https://lausu.ee
