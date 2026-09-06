package ee.kristjanr.dictation.engine

/**
 * Keeps the audio of the segment currently being dictated, so a second pass can
 * re-transcribe it (PLAN.md Phase 3).
 *
 * Bounded on purpose: a stuck endpoint detector must not grow this without
 * limit inside an IME process. Once full it stops accepting, and the second
 * pass is skipped for that segment rather than run on truncated audio.
 */
class AudioSegmentBuffer(private val maxSamples: Int) {

    private var samples = FloatArray(INITIAL_CAPACITY.coerceAtMost(maxSamples))
    private var size = 0
    private var overflowed = false

    /** True when the segment outgrew the buffer and its audio is unusable. */
    val isUsable: Boolean get() = !overflowed && size > 0

    fun append(chunk: FloatArray, length: Int = chunk.size) {
        if (overflowed) return
        if (size + length > maxSamples) {
            overflowed = true
            return
        }
        if (size + length > samples.size) {
            val grown = (samples.size * 2).coerceIn(size + length, maxSamples)
            samples = samples.copyOf(grown)
        }
        chunk.copyInto(samples, size, 0, length)
        size += length
    }

    /** Copies out the segment and clears the buffer, ready for the next one. */
    fun drain(): FloatArray {
        val out = if (isUsable) samples.copyOf(size) else FloatArray(0)
        clear()
        return out
    }

    fun clear() {
        size = 0
        overflowed = false
    }

    private companion object {
        const val INITIAL_CAPACITY = 16000 * 4
    }
}
