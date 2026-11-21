package app.recall.glasses.core

/**
 * Spots a dead Wi-Fi link. The hotspot can drop the glasses while Android still reports them as
 * connected, so the only reliable sign is that the phone stops answering.
 */
class LinkWatchdog(
    private val failuresBeforeReconnect: Int = 3,
    private val failuresBetweenRetries: Int = 6,
) {
    private var failures = 0

    /** Returns true when the glasses should drop the connection and join the hotspot again. */
    fun onCheck(networkAlive: Boolean): Boolean {
        if (networkAlive) {
            failures = 0
            return false
        }
        failures++
        val past = failures - failuresBeforeReconnect
        return past >= 0 && past % failuresBetweenRetries == 0
    }
}
