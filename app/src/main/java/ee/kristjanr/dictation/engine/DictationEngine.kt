package ee.kristjanr.dictation.engine

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import ee.kristjanr.dictation.asr.DictationSession
import ee.kristjanr.dictation.asr.RecognizerFactory
import ee.kristjanr.dictation.asr.SherpaStreamingAsr
import ee.kristjanr.dictation.asr.TextFormatting
import ee.kristjanr.dictation.asr.TranscriptEvent
import ee.kristjanr.dictation.audio.MicrophoneSource
import ee.kristjanr.dictation.model.ModelStore

/**
 * Runs dictation: microphone in, [TranscriptEvent]s out on the main thread.
 *
 * Everything native happens on one worker thread, which is also the thread that
 * created the recogniser — sherpa-onnx objects are not thread-safe and this
 * keeps confinement obvious rather than lock-based.
 *
 * The model is loaded on the first mic press, not at service creation, and
 * released again after [IDLE_RELEASE_MILLIS] of not dictating. An IME process
 * that holds hundreds of megabytes while the user types is one the platform
 * will kill, taking the keyboard out from under whatever app is in front.
 */
class DictationEngine(
    private val store: ModelStore,
    private val callbacks: Callbacks,
    private val secondPass: SecondPass = SecondPass.Disabled,
) {

    /** Delivered on the main thread. */
    interface Callbacks {
        /** Provisional text for the segment in progress; supersedes the last one. */
        fun onPartial(text: String)

        /** Settled text for a finished segment. */
        fun onFinal(text: String)

        fun onState(state: State)

        fun onError(error: Error)
    }

    enum class State { IDLE, LOADING, LISTENING }

    enum class Error {
        /** Model files are not on the device yet. */
        MODEL_MISSING,

        /** The model is present but would not load. */
        MODEL_LOAD_FAILED,

        /** The microphone could not be opened — permission, or another app has it. */
        MICROPHONE_UNAVAILABLE,
    }

    private val worker = HandlerThread("dictation-worker").apply { start() }
    private val workerHandler = Handler(worker.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Read by the audio loop on the worker, written by callers on the main thread. */
    @Volatile
    private var listening = false

    /** Worker-thread only. */
    private var recognizer: OnlineRecognizer? = null

    private val segment = AudioSegmentBuffer(MAX_SEGMENT_SAMPLES)

    private val releaseModel = Runnable { workerHandler.post(::releaseRecognizerOnWorker) }

    /** True between [start] and the moment the audio loop actually stops. */
    val isListening: Boolean get() = listening

    fun start() {
        if (listening) return
        listening = true
        mainHandler.removeCallbacks(releaseModel)
        workerHandler.post(::runDictation)
    }

    /** Asks the audio loop to wind up. The final segment is still committed. */
    fun stop() {
        listening = false
    }

    /** Releases the microphone, the model and the worker thread for good. */
    fun shutdown() {
        listening = false
        mainHandler.removeCallbacks(releaseModel)
        workerHandler.post {
            releaseRecognizerOnWorker()
            worker.quitSafely()
        }
    }

    // --- worker thread ------------------------------------------------------

    private fun runDictation() {
        val recogniser = obtainRecognizer() ?: run {
            listening = false
            return
        }

        val microphone = MicrophoneSource()
        if (!microphone.start()) {
            listening = false
            post { callbacks.onError(Error.MICROPHONE_UNAVAILABLE) }
            post { callbacks.onState(State.IDLE) }
            return
        }

        post { callbacks.onState(State.LISTENING) }

        val asr = SherpaStreamingAsr(recogniser)
        val session = DictationSession(asr)
        segment.clear()

        try {
            while (listening) {
                val chunk = microphone.read() ?: break
                segment.append(chunk)
                session.accept(chunk).forEach(::dispatch)
            }
            // The user let go mid-sentence; do not throw those words away.
            session.finish().forEach(::dispatch)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Dictation loop failed", e)
            post { callbacks.onError(Error.MODEL_LOAD_FAILED) }
        } finally {
            listening = false
            microphone.stop()
            asr.close()
            segment.clear()
            post { callbacks.onState(State.IDLE) }
            mainHandler.postDelayed(releaseModel, IDLE_RELEASE_MILLIS)
        }
    }

    private fun dispatch(event: TranscriptEvent) {
        when (event) {
            is TranscriptEvent.Partial -> {
                val text = TextFormatting.tidy(event.text)
                post { callbacks.onPartial(text) }
            }

            is TranscriptEvent.Final -> {
                // Phase 3 hooks in here: the segment's audio is still buffered,
                // and the commit has not happened yet.
                val audio = segment.drain()
                val refined = if (audio.isEmpty()) {
                    event.text
                } else {
                    secondPass.refine(audio, DictationSession.SAMPLE_RATE, event.text)
                }
                val text = TextFormatting.tidy(refined)
                post { callbacks.onFinal(text) }
            }
        }
    }

    private fun obtainRecognizer(): OnlineRecognizer? {
        recognizer?.let { return it }

        val files = store.zipformerFiles() ?: run {
            post { callbacks.onError(Error.MODEL_MISSING) }
            post { callbacks.onState(State.IDLE) }
            return null
        }

        post { callbacks.onState(State.LOADING) }

        return try {
            RecognizerFactory.create(files).also { recognizer = it }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to load recogniser", e)
            post { callbacks.onError(Error.MODEL_LOAD_FAILED) }
            post { callbacks.onState(State.IDLE) }
            null
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "sherpa-onnx native library missing", e)
            post { callbacks.onError(Error.MODEL_LOAD_FAILED) }
            post { callbacks.onState(State.IDLE) }
            null
        }
    }

    private fun releaseRecognizerOnWorker() {
        if (listening) return
        recognizer?.release()
        recognizer = null
    }

    private fun post(action: () -> Unit) = mainHandler.post(action)

    companion object {
        private const val TAG = "DictationEngine"

        /** How long the model stays resident after the user stops dictating. */
        const val IDLE_RELEASE_MILLIS = 60_000L

        /** 60 s of audio. Far past any endpoint rule; a backstop, not a limit. */
        private const val MAX_SEGMENT_SAMPLES = DictationSession.SAMPLE_RATE * 60
    }
}
