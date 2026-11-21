package app.recall.glasses.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HELLO = "hello nice to meet you"
private const val BYE = "goodbye see you next time"

class EventSessionTest {
    private fun recordingSession(at: Long = 0): EventSession = EventSession().apply {
        assertEquals(EventStep.StartCapture, onPhrase(HELLO, at))
        onRecording(at)
    }

    @Test
    fun theFullGreetingStartsACapture() {
        listOf(HELLO, "[unk] hello nice to meet you", "hello nice to meet you [unk]").forEach {
            assertEquals(it, EventStep.StartCapture, EventSession().onPhrase(it, 0))
        }
    }

    @Test
    fun partOfTheGreetingDoesNothing() {
        listOf("hello", "hi", "hey", "nice to meet you", "hello [unk] nice to meet you").forEach {
            assertNull(it, EventSession().onPhrase(it, 0))
        }
    }

    @Test
    fun partOfAWordIsNotAPhrase() {
        assertFalse(containsPhrase("othello nice to meet you", listOf(HELLO)))
        assertTrue(containsPhrase("[unk] $BYE [unk]", listOf(BYE)))
    }

    @Test
    fun theFullClosingStopsTheRecording() {
        assertEquals(EventStep.Stop, recordingSession().onPhrase(BYE, 6_000))
    }

    @Test
    fun partOfTheClosingDoesNothing() {
        listOf("bye", "goodbye", "see you", "see you later", "see you next time").forEach {
            assertNull(it, recordingSession().onPhrase(it, 6_000))
        }
    }

    @Test
    fun aClosingPhraseInTheFirstSecondsIsIgnored() {
        val session = recordingSession()
        assertEquals(EventStep.ClearHeard, session.onPhrase(BYE, 4_999))
        assertEquals(EventStep.Stop, session.onPhrase(BYE, 5_000))
    }

    @Test
    fun greetingsAreIgnoredWhileRecording() {
        assertNull(recordingSession().onPhrase(HELLO, 10_000))
    }

    @Test
    fun theNextGreetingWorksFiveSecondsAfterARecording() {
        val session = recordingSession()
        session.onFinished(100_000)
        assertEquals(EventStep.ClearHeard, session.onPhrase(HELLO, 104_999))
        assertEquals(EventStep.StartCapture, session.onPhrase(HELLO, 105_000))
    }

    @Test
    fun aTapSkipsTheWaitButNotAnActiveRecording() {
        val session = recordingSession()
        assertFalse(session.onTapStart())
        session.onFinished(100_000)
        assertTrue(session.onTapStart())
    }

    @Test
    fun noFaceWaitsBrieflyBeforeListeningAgain() {
        val session = EventSession()
        session.onPhrase(HELLO, 0)
        session.onNoFace(1_000)
        assertEquals(EventStep.ClearHeard, session.onPhrase(HELLO, 8_999))
        assertEquals(EventStep.StartCapture, session.onPhrase(HELLO, 9_000))
    }

    @Test
    fun twentySecondsOfSilenceStops() {
        val session = recordingSession()
        session.onSpeech(10_000)
        assertNull(session.onTick(29_999))
        assertEquals(EventStep.Stop, session.onTick(30_000))
        assertNull("Stops only once", session.onTick(31_000))
    }

    @Test
    fun tenMinuteLimitStopsEvenDuringSpeech() {
        val session = recordingSession()
        session.onSpeech(599_999)
        assertNull(session.onTick(599_999))
        assertEquals(EventStep.Stop, session.onTick(600_000))
    }

    @Test
    fun speechGateIgnoresSteadyNoiseAndHearsSpeech() {
        val gate = SpeechGate()
        repeat(50) { assertFalse(gate.isSpeech(400.0)) }
        assertTrue(gate.isSpeech(2_000.0))
    }
}
