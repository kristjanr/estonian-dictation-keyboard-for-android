package ee.kristjanr.dictation.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSegmentBufferTest {

    @Test
    fun `chunks come back in order`() {
        val buffer = AudioSegmentBuffer(maxSamples = 16)
        buffer.append(floatArrayOf(1f, 2f))
        buffer.append(floatArrayOf(3f))

        assertArrayEquals(floatArrayOf(1f, 2f, 3f), buffer.drain(), 0f)
    }

    @Test
    fun `draining clears the buffer`() {
        val buffer = AudioSegmentBuffer(maxSamples = 16)
        buffer.append(floatArrayOf(1f))
        buffer.drain()

        assertEquals(0, buffer.drain().size)
    }

    @Test
    fun `overflow discards the segment rather than truncating it`() {
        // A second pass on half a sentence would be worse than none.
        val buffer = AudioSegmentBuffer(maxSamples = 4)
        buffer.append(floatArrayOf(1f, 2f, 3f))
        buffer.append(floatArrayOf(4f, 5f))

        assertFalse(buffer.isUsable)
        assertEquals(0, buffer.drain().size)
    }

    @Test
    fun `the buffer recovers after an overflow`() {
        val buffer = AudioSegmentBuffer(maxSamples = 4)
        buffer.append(FloatArray(5))
        buffer.drain()

        buffer.append(floatArrayOf(7f))
        assertTrue(buffer.isUsable)
        assertArrayEquals(floatArrayOf(7f), buffer.drain(), 0f)
    }

    @Test
    fun `it grows past its initial capacity`() {
        val buffer = AudioSegmentBuffer(maxSamples = 200_000)
        repeat(20) { buffer.append(FloatArray(8000) { 1f }) }

        assertEquals(160_000, buffer.drain().size)
    }
}
