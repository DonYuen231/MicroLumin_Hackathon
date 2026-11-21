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
        followUps = listOf(FollowUp("Send the RAG article")),
        avoid = listOf("Layoffs"),
        spoken = listOf("Sam Rivera, ML engineer."),
    )

    @Test
    fun `the summary comes first`() {
        val profile = sam.copy(summary = "ML engineer at Acme building a robot arm at home.")
        assertEquals(
            Glance("Sam Rivera", "ML engineer at Acme building a robot arm at home."),
            buildCard(profile).glance,
        )
    }

    @Test
    fun `a long summary is shortened on a word boundary`() {
        val line = glanceLine(sam.copy(summary = "robot arm ".repeat(40)))
        assertTrue(line.length <= 150)
        assertTrue(line.endsWith("…"))
        assertTrue(line.removeSuffix("…").split(' ').all { it == "robot" || it == "arm" })
    }

    @Test
    fun `a summary mentioning an avoid topic is not shown`() {
        val risky = sam.copy(summary = "Left after the layoffs last year.")
        assertEquals("Send the RAG article", glanceLine(risky))
    }

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
        val risky = sam.copy(followUps = listOf(FollowUp("Ask about the layoffs")))
        assertEquals("Robotics side project", glanceLine(risky))
    }

    @Test
    fun `the line fits the display`() {
        val long = sam.copy(followUps = listOf(FollowUp("x".repeat(90))))
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
