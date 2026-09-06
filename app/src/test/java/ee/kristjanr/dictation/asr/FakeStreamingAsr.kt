package ee.kristjanr.dictation.asr

/**
 * Scripted [StreamingAsr]: each fed chunk advances to the next scripted answer,
 * so [DictationSession]'s behaviour can be checked without the native library.
 */
class FakeStreamingAsr(private val script: List<Step>) : StreamingAsr {

    data class Step(val text: String, val endpoint: Boolean = false)

    private var index = -1
    private var current = Step("")

    var resets = 0
        private set
    var inputFinished = false
        private set
    var closed = false
        private set
    val fedSamples = mutableListOf<Int>()

    override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        fedSamples += samples.size
        index++
        if (index < script.size) current = script[index]
    }

    override fun decodeAvailable() = Unit

    override fun currentText(): String = current.text

    override fun isEndpoint(): Boolean = current.endpoint

    override fun finishInput() {
        inputFinished = true
    }

    override fun reset() {
        resets++
        current = Step("")
    }

    override fun close() {
        closed = true
    }
}
