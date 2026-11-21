package app.recall.phone.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlanceTest {
    private val sam = Profile(
        name = "Sam Rivera",
        headline = "ML engineer at Acme",
        summary = "Works on vision models at Acme.",
        topics = listOf("Robotics side project"),
        commonInterests = listOf("AI wearables"),
        followUps = listOf(FollowUp("Send the RAG article")),
        avoid = listOf("Layoffs"),
        spoken = listOf("Sam Rivera, ML engineer."),
    )

    @Test
    fun `the first screen is five compact rows`() {
        assertEquals(
            listOf(
                "ML engineer at Acme",
                "Talk: Robotics side project",
                "Both: AI wearables",
                "Do: Send the RAG article",
                "Avoid: Layoffs",
            ),
            glanceLine(sam).split('\n'),
        )
    }

    @Test
    fun `every row fits on one line`() {
        val long = "word ".repeat(30)
        val profile = sam.copy(
            headline = long,
            topics = listOf(long),
            commonInterests = listOf(long),
            followUps = listOf(FollowUp(long)),
            avoid = listOf("sensitive ".repeat(30)),
        )
        val rows = glanceLine(profile).split('\n')
        assertEquals(5, rows.size)
        assertTrue(rows.all { it.length <= 38 })
    }

    @Test
    fun `an avoid topic is never suggested as something to talk about`() {
        val risky = sam.copy(
            topics = listOf("The layoffs", "Robotics"),
            followUps = listOf(FollowUp("Ask about the layoffs")),
        )
        val rows = glanceLine(risky).split('\n')
        assertTrue("Talk: Robotics" in rows)
        assertFalse(rows.any { it.startsWith("Do:") })
        assertEquals("Avoid: Layoffs", rows.last())
    }

    @Test
    fun `a sparse profile falls back to its summary`() {
        val sparse = Profile(name = "Sam", summary = "Met at the hackathon.")
        assertEquals("Met at the hackathon.", glanceLine(sparse))
    }

    @Test
    fun `a long summary is shortened on a word boundary`() {
        val line = glanceLine(Profile(name = "Sam", summary = "robot arm ".repeat(40)))
        assertTrue(line.length <= 150)
        assertTrue(line.endsWith("…"))
        assertTrue(line.removeSuffix("…").split(' ').all { it == "robot" || it == "arm" })
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
    fun `common interests appear on the full card`() {
        assertTrue(buildCard(sam).displayItems.contains(DisplayItem("In common", "AI wearables")))
    }

    @Test
    fun `glance is in the card json`() {
        val json = AppJson.encodeToString(ProfileCard.serializer(), buildCard(sam))
        assertTrue(json.contains(""""glance":{"name":"Sam Rivera","line":"ML engineer at Acme"""))
        assertFalse(json.contains("null"))
    }
}
