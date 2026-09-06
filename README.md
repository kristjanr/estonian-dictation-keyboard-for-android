# estonian-dictation-keyboard-for-android

An Android keyboard (IME) that transcribes Estonian and English speech fully
on-device — no cloud, no account, no word quota.

- **Streaming**: partial words appear at the cursor as you speak
  (TalTech streaming Zipformer via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)).
- **Optional accurate second pass**: each finished segment is re-transcribed
  with TalTech's finetuned Whisper-turbo and the provisional text is replaced.

Status: planning. See [PLAN.md](PLAN.md) for models, runtime choices, phases
and open questions, and [RESEARCH.md](RESEARCH.md) for the verified facts
behind it (licences, model card details, sherpa-onnx NPU support).

Models are published by [TalTech NLP](https://huggingface.co/TalTechNLP).

## Licence

MIT — see [LICENSE](LICENSE). The models carry their own licences (MIT /
Apache-2.0); see RESEARCH.md.
