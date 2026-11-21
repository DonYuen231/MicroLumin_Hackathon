package app.recall.glasses.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkWatchdogTest {
    @Test
    fun aHealthyLinkNeverReconnects() {
        val watchdog = LinkWatchdog()
        repeat(20) { assertFalse(watchdog.onCheck(true)) }
    }

    @Test
    fun reconnectsOnTheThirdFailedCheck() {
        val watchdog = LinkWatchdog()
        assertFalse(watchdog.onCheck(false))
        assertFalse(watchdog.onCheck(false))
        assertTrue(watchdog.onCheck(false))
    }

    @Test
    fun leavesTimeToJoinBeforeTryingAgain() {
        val watchdog = LinkWatchdog()
        val reconnects = (1..15).filter { watchdog.onCheck(false) }
        assertEquals(listOf(3, 9, 15), reconnects)
    }

    @Test
    fun oneGoodCheckResetsTheCount() {
        val watchdog = LinkWatchdog()
        repeat(2) { watchdog.onCheck(false) }
        watchdog.onCheck(true)
        assertFalse(watchdog.onCheck(false))
        assertFalse(watchdog.onCheck(false))
        assertTrue(watchdog.onCheck(false))
    }
}
