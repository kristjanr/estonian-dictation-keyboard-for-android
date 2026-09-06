package ee.kristjanr.dictation.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.inputmethodservice.InputMethodService
import androidx.core.content.ContextCompat
import ee.kristjanr.dictation.R
import ee.kristjanr.dictation.asr.TextFormatting
import ee.kristjanr.dictation.engine.DictationEngine
import ee.kristjanr.dictation.model.ModelStore
import ee.kristjanr.dictation.ui.SetupActivity

/**
 * The keyboard.
 *
 * Streaming partials go in as composing text (underlined, replaceable) and a
 * finished segment is committed. That is the same revision model the recogniser
 * already has, so no diffing is needed, and it is what makes the Phase 3 Whisper
 * replacement a one-line change rather than a rewrite.
 */
class DictationInputMethodService : InputMethodService(), DictationEngine.Callbacks {

    private lateinit var store: ModelStore
    private var engine: DictationEngine? = null

    private var micButton: View? = null
    private var statusView: android.widget.TextView? = null

    /** True while a composing region belongs to us. */
    private var composing = false

    /**
     * Space inserted before the current segment so dictation continues a
     * sentence instead of gluing onto it. Decided once per segment, before any
     * composing text exists — `getTextBeforeCursor` would otherwise return our
     * own provisional words.
     */
    private var segmentPrefix = ""

    override fun onCreate() {
        super.onCreate()
        store = ModelStore(this)
    }

    override fun onCreateInputView(): View {
        val view = LayoutInflater.from(this).inflate(R.layout.keyboard, null)

        statusView = view.findViewById(R.id.status)
        micButton = view.findViewById<View>(R.id.mic).apply {
            setOnClickListener { toggleDictation() }
        }

        view.findViewById<View>(R.id.backspace).setOnClickListener { backspace() }
        view.findViewById<View>(R.id.space).setOnClickListener { typeText(" ") }
        view.findViewById<View>(R.id.enter).setOnClickListener { performEnter() }
        view.findViewById<View>(R.id.switch_keyboard).setOnClickListener { switchKeyboard() }
        view.findViewById<View>(R.id.settings).setOnClickListener { openSetup() }

        for (id in PUNCTUATION_BUTTONS) {
            view.findViewById<android.widget.Button>(id).also { button ->
                button.setOnClickListener { typeText(button.text.toString()) }
            }
        }

        showIdleStatus()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        showIdleStatus()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // The field is going away; settle anything provisional before it does.
        stopDictation()
        finishComposing()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        engine?.shutdown()
        engine = null
        super.onDestroy()
    }

    // --- dictation ----------------------------------------------------------

    private fun toggleDictation() {
        val running = engine?.isListening == true
        if (running) {
            stopDictation()
            return
        }

        if (!hasMicrophonePermission()) {
            // An IME has no activity and cannot request permissions itself.
            setStatus(getString(R.string.status_needs_permission))
            openSetup()
            return
        }

        if (!store.isZipformerInstalled()) {
            setStatus(getString(R.string.status_needs_model))
            openSetup()
            return
        }

        val active = engine ?: DictationEngine(store, this).also { engine = it }
        active.start()
    }

    private fun stopDictation() {
        engine?.stop()
    }

    override fun onPartial(text: String) {
        val connection = currentInputConnection ?: return
        if (text.isEmpty()) return

        if (!composing) {
            segmentPrefix = leadingSpaceFor(connection, text)
            composing = true
        }
        connection.setComposingText(segmentPrefix + text, 1)
    }

    override fun onFinal(text: String) {
        val connection = currentInputConnection ?: return

        if (composing) {
            connection.setComposingText(segmentPrefix + text, 1)
            connection.finishComposingText()
        } else {
            connection.commitText(leadingSpaceFor(connection, text) + text, 1)
        }
        composing = false
        segmentPrefix = ""
    }

    override fun onState(state: DictationEngine.State) {
        micButton?.isSelected = state == DictationEngine.State.LISTENING
        setStatus(
            when (state) {
                DictationEngine.State.IDLE -> getString(R.string.status_idle)
                DictationEngine.State.LOADING -> getString(R.string.status_loading)
                DictationEngine.State.LISTENING -> getString(R.string.status_listening)
            }
        )
    }

    override fun onError(error: DictationEngine.Error) {
        setStatus(
            when (error) {
                DictationEngine.Error.MODEL_MISSING -> getString(R.string.status_needs_model)
                DictationEngine.Error.MODEL_LOAD_FAILED -> getString(R.string.status_model_failed)
                DictationEngine.Error.MICROPHONE_UNAVAILABLE -> getString(R.string.status_no_microphone)
            }
        )
        if (error == DictationEngine.Error.MODEL_MISSING) openSetup()
    }

    // --- plain keys ---------------------------------------------------------

    private fun typeText(text: String) {
        finishComposing()
        currentInputConnection?.commitText(text, 1)
    }

    private fun backspace() {
        finishComposing()
        val connection = currentInputConnection ?: return
        if (connection.getSelectedText(0).isNullOrEmpty()) {
            connection.deleteSurroundingText(1, 0)
        } else {
            // Replace the selection, matching what a hardware backspace does.
            connection.commitText("", 1)
        }
    }

    private fun performEnter() {
        finishComposing()
        val editorAction = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        val connection = currentInputConnection ?: return

        if (editorAction != null &&
            editorAction != EditorInfo.IME_ACTION_NONE &&
            editorAction != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            connection.performEditorAction(editorAction)
        } else {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun switchKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (switchToNextInputMethod(false)) return
        }
        val manager = getSystemService(InputMethodManager::class.java)
        manager?.showInputMethodPicker()
    }

    // --- helpers ------------------------------------------------------------

    private fun finishComposing() {
        if (!composing) return
        currentInputConnection?.finishComposingText()
        composing = false
        segmentPrefix = ""
    }

    private fun leadingSpaceFor(connection: InputConnection, text: String): String {
        val before = connection.getTextBeforeCursor(1, 0)
        return if (TextFormatting.needsLeadingSpace(before, text)) " " else ""
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun openSetup() {
        startActivity(
            Intent(this, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun showIdleStatus() {
        setStatus(
            when {
                !hasMicrophonePermission() -> getString(R.string.status_needs_permission)
                !store.isZipformerInstalled() -> getString(R.string.status_needs_model)
                else -> getString(R.string.status_idle)
            }
        )
    }

    private fun setStatus(text: String) {
        statusView?.text = text
    }

    private companion object {
        val PUNCTUATION_BUTTONS = intArrayOf(
            R.id.punct_comma,
            R.id.punct_period,
            R.id.punct_question,
            R.id.punct_exclamation,
        )
    }
}
