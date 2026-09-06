# Verified research notes

_Verified 2026-09-06 by reading the linked pages directly. Written for agents/environments that cannot reach huggingface.co. Do not re-verify unless a claim looks stale; do not treat unlisted claims as verified._

## 1. Licences

| Model | Licence | Source |
|---|---|---|
| `TalTechNLP/whisper-large-v3-turbo-et-verbatim-2604` | **MIT** | model card header, https://huggingface.co/TalTechNLP/whisper-large-v3-turbo-et-verbatim-2604 |
| `TalTechNLP/whisper-large-v3-turbo-et-verbatim` (older) | MIT | model card header; card itself points to `-2604` as the newer, more accurate model |
| `TalTechNLP/streaming-zipformer-large.et-en` | MIT | model card |
| `TalTechNLP/streaming-zipformer-large.et-en.w2n` | **Apache-2.0** | model card header |
| `TalTechNLP/streaming-zipformer.et-en` (small) | MIT | README front-matter |

Consequence: download-on-first-run of the Whisper model from our own mirror is permitted. Include the MIT notice and credit "Laboratory of Language Technology, TalTech" in the app.

## 2. Whisper-turbo-et-verbatim-2604 — what the card says

- Base: `openai/whisper-large-v3-turbo`. 0.8B params.
- Training data: 1400 h manual verbatim transcriptions (TalTech Estonian Speech Dataset 1.0) + ~4000 h automatically transcribed ERR broadcast news (with contextual biasing from accompanying texts) + ~500 h English podcasts/YouTube.
- Stated strength: Estonian speech with embedded English terms (tech podcasts etc.).
- Formats shipped on the card: HF safetensors, **ct2** directory (faster-whisper / `whisper-ctranslate2`), **GGML** directory (whisper.cpp, added 2026-06-17).
- Card's own demo transcript shows correct capitalisation, punctuation, compound words and proper nouns (Peeter Põld, Kreutzwald, Stenbocki maja).
- Citation: Olev & Alumäe, "Open source platform for Estonian speech transcription", LRE 59(4), 2025.

## 3. `.w2n` variant

- `tokens.txt` (1118 entries) includes single-digit tokens `0`–`9` as well as number words (`▁kakskümmend`, `kümne`, `▁tuhat`, `▁miljon`). Digit tokens are consistent with words-to-numbers output being generated directly by the model.
- Semantics are **not documented** on the card. Confirm by dictating numbers in Phase 0. Entries `#0`…`#116` at the end of the vocab are unexplained (likely padding/reserved); ignore.

## 4. sherpa-onnx NPU / Whisper status (CHANGELOG, pub.dev changelogs)

- v1.13.3: streaming Zipformer transducer on Qualcomm NPU (QNN) + Android demo; non-streaming Zipformer on QNN.
- v1.13.4: Whisper C++ runtime on Qualcomm NPU (QNN); Whisper → QNN export script.
- v1.12.21: Whisper on Ascend NPU; Whisper → RK NPU export.
- Constraint from the QNN docs: QNN does not support dynamic input shapes, so models are exported with a fixed maximum duration; shorter input is padded, longer is truncated. Model filenames encode the limit (e.g. `qnn-10-seconds-...`). QNN builds require the Qualcomm QNN SDK.
- Qualcomm's own `qualcomm/Whisper-Large-V3-Turbo` card reports decoder step ≈ 7.9 ms on Snapdragon 8 Gen 3 and ≈ 6.7 ms on 8 Elite on the NPU (base model, not the Estonian finetune; encoder time not captured in our excerpt).
- Export script for CPU/ONNX path: `sherpa-onnx/scripts/whisper/export-onnx.py`.

## 5. Production reference for the Zipformer model

- `alumae/kiirkirjutaja` (MIT) migrated to `streaming-zipformer-large.et-en` on 2026-07-03 for live TV subtitling. Its `main.py` holds the recogniser config (copied into PLAN.md); its `asr.py` holds the feed/decode/endpoint loop. Old post-processing modules (compound words, words-to-numbers, punctuation) are commented out — the model does this now.

## 6. Comparable product facts (lausu.ee, App Store)

- Lausu is a keyboard extension for iPhone; text appears at the cursor; requires Full Access; requires **iPhone 14 or newer**; first launch "prepares" the model for 5–7 minutes (Core ML compilation, not a download); free tier 1000 words/month, €9.99 one-time unlimited. Android and macOS versions listed as "coming soon".

## 7. Still unverified

- Real on-device latency of the Estonian Whisper finetune on Android (CPU or QNN).
- Zipformer int8 file availability for the *large* model (small model card lists `*.int8.onnx`; large card lists only fp32 in the excerpt we read — check the Files tab or quantise with sherpa-onnx's script).
