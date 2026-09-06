package ee.kristjanr.dictation.asr

/**
 * Turns a stream of audio chunks into [TranscriptEvent]s.
 *
 * The loop mirrors `alumae/kiirkirjutaja`'s `asr.py`, which drives the same
 * model in production: feed a chunk, decode while the recogniser is ready, emit
 * when the text changed, and cut the segment when it reports an endpoint.
 *
 * Deliberately free of Android and of any native call: everything here is
 * decided from [StreamingAsr]'s answers, so it can be unit-tested on the JVM.
 * Not thread-safe — [ee.kristjanr.dictation.engine.DictationEngine] confines it
 * to a single worker thread.
 */
class DictationSession(
    private val asr: StreamingAsr,
    private val sampleRate: Int = SAMPLE_RATE,
) {
    /** Last text handed out as a [TranscriptEvent.Partial], to suppress repeats. */
    private var lastPartial: String = ""

    /**
     * Feed one chunk of audio and collect whatever it produced.
     *
     * Returns at most one event: a segment either continues (possibly with
     * revised text) or ends. Callers should apply events in order.
     */
    fun accept(samples: FloatArray): List<TranscriptEvent> {
        asr.acceptWaveform(samples, sampleRate)
        asr.decodeAvailable()

        val text = asr.currentText()

        if (asr.isEndpoint()) {
            // Fall back to the last partial: a segment that showed text must
            // settle as text, or the caller is left with a composing region
            // nothing will ever replace.
            val settled = if (text.isNotBlank()) text else lastPartial
            asr.reset()
            lastPartial = ""
            return if (settled.isNotBlank()) listOf(TranscriptEvent.Final(settled)) else emptyList()
        }

        if (text != lastPartial) {
            lastPartial = text
            return listOf(TranscriptEvent.Partial(text))
        }

        return emptyList()
    }

    /**
     * Stop dictating. Flushes whatever is buffered and settles the open segment,
     * so the user never loses the words they just said by releasing the mic.
     */
    fun finish(): List<TranscriptEvent> {
        asr.finishInput()
        asr.decodeAvailable()

        val text = asr.currentText()
        val settled = if (text.isNotBlank()) text else lastPartial
        asr.reset()
        lastPartial = ""

        return if (settled.isNotBlank()) listOf(TranscriptEvent.Final(settled)) else emptyList()
    }

    companion object {
        /** The rate both the Zipformer and the Whisper finetune expect. */
        const val SAMPLE_RATE = 16000
    }
}
