package app.recall.glasses.core

/** Everything about hands-free capture that is likely to need tuning at the venue. */
data class VoiceConfig(
    /** The whole phrase must be heard, in order. Short words misfire in a loud room. */
    val greetings: List<String> = listOf("hello nice to meet you"),
    val closings: List<String> = listOf("goodbye see you next time"),
    /** People pause at the comma, so the listener must also know each half. */
    val phraseParts: List<String> = listOf(
        "hello",
        "nice to meet you",
        "goodbye",
        "see you next time",
    ),
    val startPrompt: String = "Hello, nice to meet you",
    val endPrompt: String = "Goodbye, see you next time",
    val silenceStopMs: Long = 20_000,
    val maxRecordingMs: Long = 10 * 60_000,
    /** Short, so the app is ready for the next person without any reset. */
    val cooldownMs: Long = 5_000,
    /** A short wait after "no face", so a noisy room cannot keep restarting the hot camera. */
    val noFaceRetryMs: Long = 8_000,
    /** A closing phrase this early is more likely a mishearing than a real goodbye. */
    val minRecordingMs: Long = 5_000,
    /** Audio is only kept this long while waiting to confirm a face. */
    val confirmTimeoutMs: Long = 15_000,
    val photos: Int = 3,
    /** If a tap catches no face, it keeps looking during the recording, within camera heat limits. */
    val faceRetryMs: Long = 3_000,
    val faceRetries: Int = 6,
) {
    val allPhrases: List<String> get() = (greetings + closings + phraseParts).distinct()
}
