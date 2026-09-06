package ee.kristjanr.dictation.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFormattingTest {

    @Test
    fun `punctuation is attached to the preceding word`() {
        assertEquals(
            "Tere, kuidas läheb?",
            TextFormatting.tidy("Tere , kuidas läheb ?"),
        )
    }

    @Test
    fun `repeated whitespace collapses`() {
        assertEquals("üks kaks kolm", TextFormatting.tidy("üks   kaks\tkolm"))
    }

    @Test
    fun `leading and trailing whitespace goes`() {
        assertEquals("tere", TextFormatting.tidy("  tere  "))
    }

    @Test
    fun `text without stray punctuation is untouched`() {
        assertEquals("Kell on pool kolm.", TextFormatting.tidy("Kell on pool kolm."))
    }

    @Test
    fun `empty stays empty`() {
        assertEquals("", TextFormatting.tidy("   "))
    }

    @Test
    fun `a space is needed after a word`() {
        assertTrue(TextFormatting.needsLeadingSpace("tere", "maailm"))
    }

    @Test
    fun `no space after existing whitespace`() {
        assertFalse(TextFormatting.needsLeadingSpace("tere ", "maailm"))
    }

    @Test
    fun `no space at the start of an empty field`() {
        assertFalse(TextFormatting.needsLeadingSpace("", "tere"))
        assertFalse(TextFormatting.needsLeadingSpace(null, "tere"))
    }

    @Test
    fun `no space before punctuation`() {
        assertFalse(TextFormatting.needsLeadingSpace("tere", ", maailm"))
    }
}
