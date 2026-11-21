package app.recall.glasses.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSessionTest {
    private fun recordingSession(at: Long = 0): EventSession = EventSession().apply {
        assertEquals(EventStep.StartCapture, onPhrase("hello", at))
        onRecording(at)
    }

    @Test
    fun everyGreetingStartsACapture() {
        listOf("hello", "hi there", "hey", "oh nice to meet you").forEach {
            assertEquals(it, EventStep.StartCapture, EventSession().onPhrase(it, 0))
        }
    }

    @Test
    fun partOfAWordIsNotAGreeting() {
        assertFalse(containsPhrase("this is high", listOf("hi")))
        assertTrue(containsPhrase("[unk] hi [unk]", listOf("hi")))
        assertNull(EventSession().onPhrase("goodbye", 0))
    }

    @Test
    fun everyClosingPhraseStopsTheRecording() {
        listOf("nice meeting you", "see you later", "see you", "bye", "goodbye").forEach {
            assertEquals(it, EventStep.Stop, recordingSession().onPhrase(it, 6_000))
        }
    }

    @Test
    fun aClosingPhraseInTheFirstSecondsIsIgnored() {
        val session = recordingSession()
        assertEquals(EventStep.ClearHeard, session.onPhrase("bye", 4_999))
        assertEquals(EventStep.Stop, session.onPhrase("bye", 5_000))
    }

    @Test
    fun greetingsAreIgnoredWhileRecording() {
        assertNull(recordingSession().onPhrase("hello", 10_000))
    }

    @Test
    fun greetingsAreIgnoredForThirtySecondsAfterARecording() {
        val session = recordingSession()
        session.onFinished(100_000)
        assertEquals(EventStep.ClearHeard, session.onPhrase("hi", 129_999))
        assertEquals(EventStep.StartCapture, session.onPhrase("hi", 130_000))
    }

    @Test
    fun noFaceWaitsBrieflyBeforeListeningAgain() {
        val session = EventSession()
        session.onPhrase("hi", 0)
        session.onNoFace(1_000)
        assertEquals(EventStep.ClearHeard, session.onPhrase("hi", 8_999))
        assertEquals(EventStep.StartCapture, session.onPhrase("hi", 9_000))
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
