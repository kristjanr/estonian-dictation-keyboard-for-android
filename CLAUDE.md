# CLAUDE.md

Context for working on this repository. Read PLAN.md for where the project is
going and RESEARCH.md for verified facts about the models; this file covers the
code and the reasoning behind it.

## What this is

An Android IME that dictates Estonian and English fully on-device, using
TalTech's streaming Zipformer through sherpa-onnx.

## Current state — read this first

**The app has never been built or run.** It was written in a container with no
Android SDK and no phone. Before changing anything, build it: the first
compile will likely surface real errors, and they are mine, not yours.

Verified so far:

- `scripts/fetch-sherpa-onnx.sh` runs end to end.
- The `asr/` package type-checks against the real sherpa-onnx v1.13.7 Kotlin API.
- 22 JVM unit tests pass (`app/src/test/`).

Not verified: the Gradle build, resource inflation, the manifest, and
everything that needs a device.

## Setup

```bash
./scripts/fetch-sherpa-onnx.sh    # vendors JNI libs + Kotlin API, pinned v1.13.7
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

sherpa-onnx is not on Maven Central. The script vendors it into
`app/src/main/jniLibs/` and `app/src/main/java/com/k2fsa/`, both gitignored;
the build fails early with instructions if they are absent. Never edit
vendored files — change the pinned version in the script instead.

Model files go in `models/` (gitignored). `./scripts/fetch-models.sh --push`
sideloads them into app-private storage on a debug build, which saves
re-downloading ~600 MB after every reinstall.

## Architecture, and why it is shaped this way

```
asr/      recognition core — no Android imports at all
audio/    AudioRecord capture
engine/   threading, lifecycle, Phase 3 seams
ime/      the keyboard (InputMethodService)
model/    on-device model storage and download
ui/       companion activity
```

**`asr/` contains no Android imports.** `DictationSession` — the
feed/decode/endpoint loop — talks to the `StreamingAsr` interface, and
`SherpaStreamingAsr` is the only implementation that touches native code. This
is what makes the state machine testable on the JVM with `FakeStreamingAsr`.
Keep it that way: an Android import in `asr/` costs the test suite.

**All native calls happen on one worker thread.** sherpa-onnx objects are not
thread-safe. `DictationEngine` owns a `HandlerThread`, creates the recogniser
there, and posts results to the main thread. Confinement rather than locking,
so violations are visible rather than subtle.

**Streaming partials are composing text; a finished segment is committed.**
`setComposingText` for `TranscriptEvent.Partial`, `commitText` for `Final`.
This mirrors the recogniser's own revision model, so nothing has to diff text,
and it is what makes the Phase 3 Whisper replacement a substitution rather than
a rewrite.

**The model loads on first mic press and is released 60 s after idle.** An IME
process holding hundreds of megabytes while the user types is one the platform
kills, which takes the keyboard out from under whatever app is in front.

**One recogniser, many streams.** The recogniser holds the weights and is
long-lived; an `OnlineStream` that has been told its input is finished cannot
be reused, so `SherpaStreamingAsr` lives for exactly one dictation and its
`close()` deliberately does not release the recogniser.

## Easy things to get wrong

- A segment that showed partials must settle as a `Final`, even if the
  recogniser reports empty text at the endpoint — otherwise the IME is left
  with a composing region nothing will ever replace. `DictationSession` falls
  back to the last partial; there is a test named for it.
- `ModelStore` resolves `*.int8.onnx` first and falls back to `*.onnx`.
  Whether TalTech ships quantised weights for the large model is still
  unconfirmed (RESEARCH.md §7), so both paths must keep working.
- Keyboard views are inflated without a Material theme. Use concrete colours
  and drawables in `res/layout/keyboard.xml`, not theme attributes.

## Deliberate omissions

- **No ET/EN toggle**, though PLAN.md Phase 2 lists one. The Zipformer is a
  single bilingual model and `OnlineRecognizerConfig` has no language
  parameter, so the button would control nothing.
- **Phase 3 is a seam, not an implementation.** `SecondPass` defaults to a
  no-op and `AudioSegmentBuffer` already holds the segment audio. PLAN.md's
  decision gate may drop Phase 3 entirely, so it is not built yet.

## First thing to tune

`RecognizerFactory.RULE2_MIN_TRAILING_SILENCE` is 2.0 s, copied from
kiirkirjutaja, which is tuned for continuous broadcast audio. For a keyboard
that is a two-second pause before words commit, and will probably feel
sluggish. Lower it once you can measure it on a device.

## Conventions

- Kotlin, minSdk 29, arm64-v8a only.
- Comments explain why, not what; the reference for recogniser parameters is
  `alumae/kiirkirjutaja` and should be cited when they change.
- Model files and vendored runtime never go into git.
