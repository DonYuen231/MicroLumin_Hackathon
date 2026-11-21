package app.recall.phone.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameMatchTest {
    private val people = listOf(
        NamedPerson("p_sam", "Sam Rivera"),
        NamedPerson("p_jo", "Jordan Lee"),
    )

    @Test
    fun `the same full name matches`() {
        assertEquals("p_sam", matchByName("Sam Rivera", people) { null })
    }

    @Test
    fun `case and punctuation do not matter`() {
        assertEquals("p_sam", matchByName("  sam   RIVERA. ", people) { null })
    }

    @Test
    fun `a first name alone never matches`() {
        assertNull(matchByName("Sam", people) { null })
        assertNull(matchByName("Sam", listOf(NamedPerson("p_1", "Sam"))) { null })
    }

    @Test
    fun `a different last name does not match`() {
        assertNull(matchByName("Sam Rivers", people) { null })
    }

    @Test
    fun `placeholder names never match`() {
        val saved = listOf(NamedPerson("p_1", "Unknown Person"), NamedPerson("p_2", "Person A"))
        assertNull(matchByName("Unknown Person", saved) { null })
        assertNull(matchByName("Person A", saved) { null })
    }

    @Test
    fun `two saved people with the same name are not guessed between`() {
        val twins = people + NamedPerson("p_other", "Sam Rivera")
        assertNull(matchByName("Sam Rivera", twins) { null })
    }

    @Test
    fun `a photo of clearly someone else vetoes the name`() {
        assertNull(matchByName("Sam Rivera", people) { 0.19f })
        assertEquals("p_sam", matchByName("Sam Rivera", people) { 0.2f })
    }

    @Test
    fun `a locked profile matches on first or full name`() {
        val locked = listOf(NamedPerson("p_avery", "Avery"))
        assertEquals("p_avery", matchLockedName("Avery", locked))
        assertEquals("p_avery", matchLockedName("avery smith", locked))
        assertNull(matchLockedName("Sam", locked))
    }

    @Test
    fun `placeholder names never match a locked profile`() {
        val locked = listOf(NamedPerson("p_1", "Unknown"), NamedPerson("p_2", "Person A"))
        assertNull(matchLockedName("Unknown", locked))
        assertNull(matchLockedName("Person B", locked))
    }

    @Test
    fun `best face score uses every photo and vector`() {
        val candidate = FaceCandidate("p", listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
        val photos = listOf(floatArrayOf(0.6f, 0.8f), floatArrayOf(1f, 0f))
        assertEquals(1f, bestFaceScore(photos, candidate)!!, 0.001f)
        assertNull(bestFaceScore(photos, null))
        assertNull(bestFaceScore(emptyList(), candidate))
    }
}
