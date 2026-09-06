package ee.kristjanr.dictation.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import ee.kristjanr.dictation.asr.DictationSession

/**
 * 16 kHz mono microphone capture, converted to the float samples sherpa-onnx wants.
 *
 * Caller must hold RECORD_AUDIO; an IME cannot ask for it itself, so
 * [ee.kristjanr.dictation.ui.SetupActivity] gets it granted first.
 */
class MicrophoneSource(
    private val sampleRate: Int = DictationSession.SAMPLE_RATE,
    /** Chunk length. ~100 ms is what kiirkirjutaja feeds the recogniser. */
    private val chunkMillis: Int = 100,
) {
    /** Samples per chunk handed to the recogniser. */
    val chunkSamples: Int = sampleRate * chunkMillis / 1000

    private var record: AudioRecord? = null
    private val pcm = ShortArray(chunkSamples)
    private val floats = FloatArray(chunkSamples)

    /**
     * Opens the microphone. Returns false if the device refused it — another app
     * holds it, or the permission is missing.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) return false

        // Room for several chunks so a slow decode does not drop audio.
        val bufferBytes = maxOf(minBuffer, chunkSamples * Short.SIZE_BYTES * BUFFER_CHUNKS)

        val created = try {
            AudioRecord(
                // VOICE_RECOGNITION asks the platform for the ASR-tuned path:
                // no AGC surprises and no music-oriented processing.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (e: IllegalArgumentException) {
            return false
        } catch (e: SecurityException) {
            return false
        }

        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            return false
        }

        return try {
            created.startRecording()
            record = created
            true
        } catch (e: IllegalStateException) {
            created.release()
            false
        }
    }

    /**
     * Blocks for roughly one chunk and returns the samples, or null once the
     * microphone has stopped or errored.
     *
     * The returned array is reused between calls — consume it before the next read.
     */
    fun read(): FloatArray? {
        val active = record ?: return null
        val n = active.read(pcm, 0, pcm.size)
        if (n <= 0) return null

        for (i in 0 until n) {
            floats[i] = pcm[i] / PCM16_FULL_SCALE
        }
        // Short reads are rare but legal; zero the tail so stale audio is not re-fed.
        for (i in n until floats.size) {
            floats[i] = 0f
        }
        return floats
    }

    fun stop() {
        val active = record ?: return
        record = null
        try {
            if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) active.stop()
        } catch (e: IllegalStateException) {
            // Already stopped; nothing to do but release.
        }
        active.release()
    }

    private companion object {
        const val BUFFER_CHUNKS = 8
        const val PCM16_FULL_SCALE = 32768.0f
    }
}
