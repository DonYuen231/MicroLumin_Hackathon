package app.recall.phone.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpsTest {
    private val met = Instant.parse("2026-11-21T15:00:00Z")

    @Test
    fun `a profile saved with plain strings still loads`() {
        val json = """{"name":"Sam","topics":["Robots"],"follow_ups":["Send the article"," "]}"""
        val profile = AppJson.decodeFromString(Profile.serializer(), json)
        assertEquals("Sam", profile.name)
        assertEquals(listOf("Robots"), profile.topics)
        assertEquals(listOf(FollowUp("Send the article")), profile.followUps)
        assertNull(profile.followUps.single().dueInHours)
    }

    @Test
    fun `a backend follow-up keeps every field through a save`() {
        val json = """{"name":"Sam","follow_ups":[{"text":"Send it","reason":"Promised",
            "due_in_hours":6.0,"channel":"email","draft":"Hi Sam"}]}"""
        val profile = AppJson.decodeFromString(Profile.serializer(), json)
        val saved = AppJson.encodeToString(Profile.serializer(), profile)
        assertEquals(profile, AppJson.decodeFromString(Profile.serializer(), saved))
        assertEquals(FollowUp("Send it", "Promised", 6.0, "email", "Hi Sam"), profile.followUps[0])
    }

    @Test
    fun `real timing counts from the meeting`() {
        val now = met.plusSeconds(3_600)
        assertEquals(5 * 3_600_000L, followUpDelayMs(6.0, met, now, demoSpeed = false))
    }

    @Test
    fun `an overdue follow-up fires right away`() {
        assertEquals(0L, followUpDelayMs(1.0, met, met.plusSeconds(90_000), demoSpeed = false))
    }

    @Test
    fun `demo speed turns one hour into ten seconds`() {
        assertEquals(10_000L, followUpDelayMs(1.0, met, met, demoSpeed = true))
        assertEquals(40_000L, followUpDelayMs(4.0, met, met.plusSeconds(99_999), demoSpeed = true))
    }

    @Test
    fun `demo speed caps a one-week follow-up at one minute`() {
        assertEquals(60_000L, followUpDelayMs(168.0, met, met, demoSpeed = true))
    }

    @Test
    fun `small rewording keeps the same reminder key`() {
        assertEquals(
            followUpKey("p_1", "Send the RAG article."),
            followUpKey("p_1", "send the rag article"),
        )
        assertFalse(followUpKey("p_1", "x") == followUpKey("p_2", "x"))
    }

    @Test
    fun `the title reads naturally`() {
        assertEquals(
            "Follow up with Sam: send the RAG article",
            followUpTitle("Sam Rivera", "Send the RAG article"),
        )
        assertEquals(
            "Follow up with Sam: Sam needs the RAG article",
            followUpTitle("Sam Rivera", "Sam needs the RAG article"),
        )
        assertEquals(
            "Follow up with Sam: RAG article link",
            followUpTitle("Sam Rivera", "RAG article link"),
        )
        assertTrue(followUpTitle("Unknown", "Send it").startsWith("Follow up with them:"))
    }

    @Test
    fun `editing keeps the draft on unchanged lines`() {
        val original = listOf(FollowUp("Send it", "Promised", 6.0, "message", "Hi Sam"))
        val merged = mergeEditedFollowUps(original, listOf("Send it", "Call Sam"))
        assertEquals(original[0], merged[0])
        assertEquals(FollowUp("Call Sam"), merged[1])
    }
}
