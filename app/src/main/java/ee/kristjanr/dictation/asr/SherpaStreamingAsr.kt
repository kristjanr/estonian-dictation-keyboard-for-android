package ee.kristjanr.dictation.asr

import com.k2fsa.sherpa.onnx.OnlineRecognizer

/**
 * [StreamingAsr] backed by sherpa-onnx.
 *
 * Owns one [com.k2fsa.sherpa.onnx.OnlineStream] and lives for one dictation —
 * a stream that has been told its input is finished cannot be reused. The
 * [recognizer] holds the model weights, is expensive to build, and is owned by
 * the caller: [close] does not release it.
 */
class SherpaStreamingAsr(private val recognizer: OnlineRecognizer) : StreamingAsr {

    private var stream = recognizer.createStream()

    override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        stream.acceptWaveform(samples, sampleRate)
    }

    override fun decodeAvailable() {
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
    }

    override fun currentText(): String = recognizer.getResult(stream).text

    override fun isEndpoint(): Boolean = recognizer.isEndpoint(stream)

    override fun finishInput() {
        stream.inputFinished()
    }

    override fun reset() {
        recognizer.reset(stream)
    }

    override fun close() {
        stream.release()
    }
}
