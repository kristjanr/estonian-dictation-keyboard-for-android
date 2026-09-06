package ee.kristjanr.dictation.asr

/**
 * The whole of this project's text post-processing.
 *
 * The model already produces capitalisation and punctuation, and kiirkirjutaja
 * has retired its compound-word, words-to-numbers and punctuation modules
 * accordingly. What is left is cosmetic: the recogniser emits punctuation as
 * separate tokens, so it arrives space-separated ("tere , kuidas läheb ?").
 */
object TextFormatting {

    /** Punctuation that belongs to the word before it, with no space between. */
    private const val ATTACHING = ",.!?:;"

    /** Joins stray punctuation onto the preceding word and squeezes whitespace. */
    fun tidy(raw: String): String {
        val out = StringBuilder(raw.length)
        var pendingSpace = false

        for (ch in raw) {
            if (ch.isWhitespace()) {
                // Hold the space back; a following punctuation mark cancels it.
                pendingSpace = out.isNotEmpty()
                continue
            }
            if (pendingSpace && ch !in ATTACHING) {
                out.append(' ')
            }
            pendingSpace = false
            out.append(ch)
        }

        return out.toString()
    }

    /**
     * Whether a space is needed between text already in the field and the words
     * about to be inserted, so dictation appends to a sentence in progress
     * without gluing words together or double-spacing.
     */
    fun needsLeadingSpace(before: CharSequence?, insertion: String): Boolean {
        if (insertion.isEmpty()) return false
        if (insertion.first() in ATTACHING) return false
        val previous = before?.lastOrNull() ?: return false
        return !previous.isWhitespace()
    }
}
