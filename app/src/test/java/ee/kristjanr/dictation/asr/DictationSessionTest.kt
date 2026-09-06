package ee.kristjanr.dictation.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationSessionTest {

    private val chunk = FloatArray(1600)

    private fun run(vararg steps: FakeStreamingAsr.Step): Pair<List<TranscriptEvent>, FakeStreamingAsr> {
        val asr = FakeStreamingAsr(steps.toList())
        val session = DictationSession(asr)
        val events = steps.flatMap { session.accept(chunk) }
        return events to asr
    }

    @Test
    fun `emits a partial only when the text changed`() {
        val (events, _) = run(
            FakeStreamingAsr.Step("tere"),
            FakeStreamingAsr.Step("tere"),
            FakeStreamingAsr.Step("tere kuidas"),
        )

        assertEquals(
            listOf(
                TranscriptEvent.Partial("tere"),
                TranscriptEvent.Partial("tere kuidas"),
            ),
            events,
        )
    }

    @Test
    fun `endpoint settles the segment and resets the recogniser`() {
        val (events, asr) = run(
            FakeStreamingAsr.Step("tere"),
            FakeStreamingAsr.Step("tere kuidas läheb", endpoint = true),
        )

        assertEquals(
            listOf(
                TranscriptEvent.Partial("tere"),
                TranscriptEvent.Final("tere kuidas läheb"),
            ),
            events,
        )
        assertEquals(1, asr.resets)
    }

    @Test
    fun `a new segment starts clean after an endpoint`() {
        val (events, _) = run(
            FakeStreamingAsr.Step("üks", endpoint = true),
            FakeStreamingAsr.Step("kaks"),
        )

        assertEquals(
            listOf(
                TranscriptEvent.Final("üks"),
                TranscriptEvent.Partial("kaks"),
            ),
            events,
        )
    }

    @Test
    fun `silence produces nothing`() {
        val (events, asr) = run(
            FakeStreamingAsr.Step(""),
            FakeStreamingAsr.Step("", endpoint = true),
        )

        assertTrue(events.isEmpty())
        assertEquals(1, asr.resets)
    }

    @Test
    fun `an endpoint that reports no text still settles the last partial`() {
        // Guards the IME against a composing region nothing ever replaces.
        val (events, _) = run(
            FakeStreamingAsr.Step("pooleli jäänud"),
            FakeStreamingAsr.Step("", endpoint = true),
        )

        assertEquals(
            listOf(
                TranscriptEvent.Partial("pooleli jäänud"),
                TranscriptEvent.Final("pooleli jäänud"),
            ),
            events,
        )
    }

    @Test
    fun `finish commits the open segment`() {
        val asr = FakeStreamingAsr(listOf(FakeStreamingAsr.Step("poolik lause")))
        val session = DictationSession(asr)

        assertEquals(listOf(TranscriptEvent.Partial("poolik lause")), session.accept(chunk))
        assertEquals(listOf(TranscriptEvent.Final("poolik lause")), session.finish())
        assertTrue(asr.inputFinished)
    }

    @Test
    fun `finish on silence commits nothing`() {
        val asr = FakeStreamingAsr(emptyList())
        assertTrue(DictationSession(asr).finish().isEmpty())
    }

    @Test
    fun `audio is forwarded at the model's sample rate`() {
        val asr = FakeStreamingAsr(listOf(FakeStreamingAsr.Step("")))
        DictationSession(asr).accept(chunk)
        assertEquals(listOf(1600), asr.fedSamples)
    }
}
