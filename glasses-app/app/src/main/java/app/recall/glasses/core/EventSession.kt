package app.recall.glasses.core

import kotlin.math.max

sealed interface EventStep {
    data object StartCapture : EventStep
    data object Stop : EventStep

    /** The phrase was deliberately ignored, so it must not count again later. */
    data object ClearHeard : EventStep
}

/** The rules for when a greeting starts a recording and what ends it. Holds no device state. */
class EventSession(private val config: VoiceConfig = VoiceConfig()) {
    private enum class Phase { WAITING, CHECKING, RECORDING, STOPPING }

    private var phase = Phase.WAITING
    private var ignoreUntilMs = 0L
    private var recordingSinceMs = 0L
    private var lastSpeechMs = 0L

    fun onPhrase(text: String, nowMs: Long): EventStep? = when (phase) {
        Phase.WAITING -> when {
            !isGreeting(text, config.greetings) -> null
            nowMs < ignoreUntilMs -> EventStep.ClearHeard
            else -> {
                phase = Phase.CHECKING
                EventStep.StartCapture
            }
        }
        Phase.RECORDING -> when {
            !containsPhrase(text, config.closings) -> null
            nowMs - recordingSinceMs < config.minRecordingMs -> EventStep.ClearHeard
            else -> {
                phase = Phase.STOPPING
                EventStep.Stop
            }
        }
        Phase.CHECKING, Phase.STOPPING -> null
    }

    /** A tap is deliberate, so it skips the wait after the last recording. */
    fun onTapStart(): Boolean {
        if (phase != Phase.WAITING) return false
        phase = Phase.CHECKING
        return true
    }

    fun onSpeech(nowMs: Long) {
        lastSpeechMs = nowMs
    }

    fun onTick(nowMs: Long): EventStep? {
        if (phase != Phase.RECORDING) return null
        val tooLong = nowMs - recordingSinceMs >= config.maxRecordingMs
        val silent = nowMs - lastSpeechMs >= config.silenceStopMs
        if (!tooLong && !silent) return null
        phase = Phase.STOPPING
        return EventStep.Stop
    }

    fun onRecording(nowMs: Long) {
        phase = Phase.RECORDING
        recordingSinceMs = nowMs
        lastSpeechMs = nowMs
    }

    fun onStopping() {
        phase = Phase.STOPPING
    }

    fun onNoFace(nowMs: Long) {
        phase = Phase.WAITING
        ignoreUntilMs = nowMs + config.noFaceRetryMs
    }

    fun onFinished(nowMs: Long) {
        phase = Phase.WAITING
        ignoreUntilMs = nowMs + config.cooldownMs
    }
}

/** Matches whole words, so "this" never counts as "hi". */
fun containsPhrase(text: String, phrases: List<String>): Boolean {
    val heard = " " + normalizeWords(text) + " "
    return phrases.any { heard.contains(" " + normalizeWords(it) + " ") }
}

/**
 * A one-word greeting must open what was said, because "hi" is easily misheard in the middle of
 * other people's speech. A longer phrase like "nice to meet you" can appear anywhere.
 */
fun isGreeting(text: String, greetings: List<String>): Boolean {
    val heard = normalizeWords(text)
    return greetings.map(::normalizeWords).any { phrase ->
        if (' ' in phrase) {
            " $heard ".contains(" $phrase ")
        } else {
            heard == phrase || heard.startsWith("$phrase ")
        }
    }
}

private fun normalizeWords(text: String): String =
    text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

/** Tells speech from steady room noise by tracking a slowly moving noise floor. */
class SpeechGate(private val ratio: Double = 2.5, private val minimumLevel: Double = 200.0) {
    private var floor = -1.0

    fun isSpeech(level: Double): Boolean {
        floor = when {
            floor < 0 -> level
            level < floor -> floor * 0.9 + level * 0.1
            else -> floor * 0.995 + level * 0.005
        }
        return level > max(floor * ratio, minimumLevel)
    }
}
