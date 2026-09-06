package ee.kristjanr.dictation.asr

/**
 * The slice of a streaming recogniser that [DictationSession] needs.
 *
 * This exists so the session state machine can be exercised without the native
 * library present: [SherpaStreamingAsr] is the real implementation, tests use a
 * fake. Keep it minimal — logic belongs in the session, not here.
 */
interface StreamingAsr {
    /** Feed PCM samples in [-1, 1]. */
    fun acceptWaveform(samples: FloatArray, sampleRate: Int)

    /** Decode everything the recogniser currently has enough audio for. */
    fun decodeAvailable()

    /** Best transcript of the current segment. Empty after [reset]. */
    fun currentText(): String

    /** True when the recogniser considers the current segment finished. */
    fun isEndpoint(): Boolean

    /** Tell the recogniser no more audio is coming for this segment. */
    fun finishInput()

    /** Start a new segment, discarding the current one. */
    fun reset()

    /** Release native resources. */
    fun close()
}
