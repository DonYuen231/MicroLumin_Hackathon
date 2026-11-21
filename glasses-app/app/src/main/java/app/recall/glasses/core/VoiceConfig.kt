package app.recall.glasses.core

/** Everything about hands-free capture that is likely to need tuning at the venue. */
data class VoiceConfig(
    val greetings: List<String> = listOf("hello", "hi", "hey", "nice to meet you"),
    val closings: List<String> = listOf(
        "nice meeting you",
        "see you",
        "see you later",
        "bye",
        "goodbye",
    ),
    val silenceStopMs: Long = 20_000,
    val maxRecordingMs: Long = 10 * 60_000,
    val cooldownMs: Long = 30_000,
    /** A short wait after "no face", so a noisy room cannot keep restarting the hot camera. */
    val noFaceRetryMs: Long = 8_000,
    /** A closing phrase this early is more likely a mishearing than a real goodbye. */
    val minRecordingMs: Long = 5_000,
    val photos: Int = 3,
) {
    val allPhrases: List<String> get() = (greetings + closings).distinct()
}
