package app.recall.phone.core

/**
 * Stops automatic sync from retrying a broken capture forever, because each retry can spend
 * transcription and LLM calls. The manual button calls [reset] to try everything again.
 */
class SyncRetryPolicy(private val maxAttempts: Int = 3) {
    private val failures = mutableMapOf<String, Int>()

    fun shouldTry(captureId: String): Boolean = (failures[captureId] ?: 0) < maxAttempts

    fun recordFailure(captureId: String) {
        failures[captureId] = (failures[captureId] ?: 0) + 1
    }

    fun recordSuccess(captureId: String) {
        failures.remove(captureId)
    }

    fun reset() = failures.clear()
}
