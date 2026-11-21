package app.recall.phone.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockedProfileTest {
    @Test
    fun `a profile saved before locks existed is unlocked`() {
        val profile = AppJson.decodeFromString(Profile.serializer(), """{"name":"Sam"}""")
        assertFalse(profile.locked)
    }

    @Test
    fun `the lock survives being saved and loaded`() {
        val locked = Profile(name = "Avery", locked = true, commonInterests = listOf("AI wearables"))
        val json = AppJson.encodeToString(Profile.serializer(), locked)
        val loaded = AppJson.decodeFromString(Profile.serializer(), json)
        assertTrue(loaded.locked)
        assertEquals(locked, loaded)
    }

    @Test
    fun `a seed can lock a profile`() {
        val json = """{"name":"Avery","profile":{"name":"Avery","locked":true}}"""
        assertTrue(AppJson.decodeFromString(ProfileSeed.serializer(), json).profile.locked)
    }
}
