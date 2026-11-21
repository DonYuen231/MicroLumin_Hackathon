package app.recall.phone.core

import java.time.ZoneOffset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private val query = floatArrayOf(1f, 0f)
    private fun at(score: Float) =
        floatArrayOf(score, kotlin.math.sqrt(1f - score * score))

    @Test
    fun picksBestVectorForEachPerson() {
        val people = listOf(
            FaceCandidate("a", listOf(at(0.2f), at(0.9f))),
            FaceCandidate("b", listOf(at(0.6f))),
        )
        val match = matchFace(query, people, 0.45f) as MatchDecision.Match
        assertEquals("a", match.personId)
        assertEquals(0.9f, match.score, 0.001f)
        assertNull(match.runnerUpId)
    }

    @Test
    fun belowThresholdIsNoMatch() {
        val decision = matchFace(query, listOf(FaceCandidate("a", listOf(at(0.44f)))), 0.45f)
        assertTrue(decision is MatchDecision.NoMatch)
    }

    @Test
    fun closeSecondPlaceIsAmbiguous() {
        val people = listOf(
            FaceCandidate("a", listOf(at(0.70f))),
            FaceCandidate("b", listOf(at(0.67f))),
        )
        val match = matchFace(query, people, 0.45f) as MatchDecision.Match
        assertEquals("b", match.runnerUpId)
    }

    @Test
    fun wrongSizedVectorsAreIgnored() {
        val people = listOf(FaceCandidate("a", listOf(floatArrayOf(1f, 0f, 0f))))
        assertEquals(MatchDecision.NoMatch(null), matchFace(query, people, 0.45f))
    }

    @Test
    fun vectorBytesRoundTrip() {
        val vector = floatArrayOf(0.25f, -0.5f, 0.125f)
        assertArrayEquals(vector, vector.toBytes().toFloats(), 0f)
    }

    @Test
    fun cardKeepsAvoidLastAndFormatsLastMet() {
        val card = buildCard(
            Profile(
                name = "Sam Rivera",
                headline = "ML engineer",
                encounters = listOf(
                    Encounter("2026-01-02T10:00:00Z", "Meetup"),
                    Encounter("2026-11-21T15:30:00Z", "Worn Hackathon"),
                ),
                topics = listOf("Robotics"),
                followUps = listOf("Send the article"),
                sinceYouMet = listOf("New role"),
                avoid = listOf("Layoffs"),
                spoken = listOf("Sam Rivera, ML engineer."),
            ),
            ZoneOffset.UTC,
        )
        assertEquals("Worn Hackathon, Nov 21, 2026", card.displayItems.first().text)
        assertEquals(
            listOf("Last met", "Talk about", "Follow up", "Since you met", "Avoid"),
            card.displayItems.map { it.label },
        )
        assertEquals(listOf("Sam Rivera, ML engineer.", "Avoid: Layoffs"), card.spokenItems)
    }

    @Test
    fun recognizeJsonMatchesContract() {
        val json = AppJson.encodeToString(
            RecognizeResponse.serializer(),
            RecognizeResponse(match = false, reason = "no_match"),
        )
        assertEquals("""{"match":false,"reason":"no_match"}""", json)
        assertFalse(json.contains("card"))
    }
}
