package ee.kristjanr.dictation.asr

/**
 * What the recogniser has to say about the utterance in progress.
 *
 * The two cases map 1:1 onto the Android composing-text mechanism:
 * [Partial] becomes `setComposingText`, [Final] becomes `commitText`.
 */
sealed interface TranscriptEvent {
    /** The current best guess. Supersedes any earlier [Partial]; may still change. */
    data class Partial(val text: String) : TranscriptEvent

    /** The recogniser hit an endpoint. This text is settled and will not be revised. */
    data class Final(val text: String) : TranscriptEvent
}
