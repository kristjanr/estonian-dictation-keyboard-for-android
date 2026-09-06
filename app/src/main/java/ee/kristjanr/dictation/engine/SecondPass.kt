package ee.kristjanr.dictation.engine

/**
 * The optional accurate re-transcription of a finished segment (PLAN.md Phase 3).
 *
 * Phase 3 is behind a decision gate — if Whisper's gain on phone-mic audio is
 * small it is dropped — so this is the seam and not the implementation. The
 * pipeline runs through it either way, which keeps the Whisper path from
 * becoming a rewrite of the engine later.
 */
fun interface SecondPass {

    /**
     * Returns the text to commit, given the segment's audio and what the
     * streaming pass produced. Runs on the dictation worker thread; a slow
     * implementation delays the commit, so it must honour its own timeout and
     * fall back to [firstPass].
     */
    fun refine(audio: FloatArray, sampleRate: Int, firstPass: String): String

    companion object {
        /** Commit the streaming result unchanged. */
        val Disabled = SecondPass { _, _, firstPass -> firstPass }
    }
}
