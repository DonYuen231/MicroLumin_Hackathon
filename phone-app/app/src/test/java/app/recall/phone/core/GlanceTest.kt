package app.recall.phone.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlanceTest {
    private val sam = Profile(
        name = "Sam Rivera",
        headline = "ML engineer at Acme",
        topics = listOf("Robotics side project"),
        followUps = listOf("Send the RAG article"),
        avoid = listOf("Layoffs"),
        spoken = listOf("Sam Rivera, ML engineer."),
    )

    @Test
    fun `a follow-up comes before a topic`() {
        assertEquals(Glance("Sam Rivera", "Send the RAG article"), buildCard(sam).glance)
    }

    @Test
    fun `falls back to a topic and then the headline`() {
        assertEquals("Robotics side project", glanceLine(sam.copy(followUps = emptyList())))
        assertEquals(
            "ML engineer at Acme",
            glanceLine(sam.copy(followUps = emptyList(), topics = emptyList())),
        )
    }

    @Test
    fun `an avoid topic is never the glance line`() {
        val risky = sam.copy(followUps = listOf("Ask about the layoffs"))
        assertEquals("Robotics side project", glanceLine(risky))
    }

    @Test
    fun `the line fits the display`() {
        val long = sam.copy(followUps = listOf("x".repeat(90)))
        assertEquals(60, glanceLine(long).length)
    }

    @Test
    fun `a close match asks instead of asserting`() {
        val card = confirmCard(buildCard(sam))
        assertEquals(Glance("Sam Rivera", "Is this Sam Rivera?"), card.glance)
    }

    @Test
    fun `spoken items do not change`() {
        assertEquals(listOf("Sam Rivera, ML engineer.", "Avoid: Layoffs"), buildCard(sam).spokenItems)
    }

    @Test
    fun `glance is in the card json`() {
        val json = AppJson.encodeToString(ProfileCard.serializer(), buildCard(sam))
        assertTrue(json.contains(""""glance":{"name":"Sam Rivera","line":"Send the RAG article"}"""))
        assertFalse(json.contains("null"))
    }
}
