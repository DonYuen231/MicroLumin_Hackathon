package app.recall.phone.core

import java.time.Duration
import java.time.Instant

const val DEMO_MS_PER_HOUR = 10_000L

/** Keeps a one-week follow-up from taking 28 minutes to appear during a live demo. */
const val DEMO_MAX_DELAY_MS = 60_000L

/** Real timing counts from when the people met. Demo timing counts from now. */
fun followUpDelayMs(dueInHours: Double, metAt: Instant, now: Instant, demoSpeed: Boolean): Long {
    if (demoSpeed) return (dueInHours * DEMO_MS_PER_HOUR).toLong().coerceAtMost(DEMO_MAX_DELAY_MS)
    val due = metAt.plusSeconds((dueInHours * 3_600).toLong())
    return Duration.between(now, due).toMillis().coerceAtLeast(0)
}

private fun normalized(text: String): String =
    text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

fun sameFollowUp(a: String, b: String): Boolean = normalized(a) == normalized(b)

/** The same follow-up keeps this key across profile updates, so it only reminds once. */
fun followUpKey(personId: String, text: String): String = "followup:$personId:${normalized(text)}"

/** For example, "Follow up with Sam: send the RAG article". */
fun followUpTitle(name: String, text: String): String {
    val firstName = name.trim().substringBefore(' ').ifEmpty { "them" }
    val nameWords = name.lowercase().split(' ')
    val firstWord = text.trim().substringBefore(' ')
    val keepCase = firstWord.lowercase() in nameWords || firstWord.drop(1).any { it.isUpperCase() }
    val action = if (keepCase) text.trim() else text.trim().replaceFirstChar { it.lowercase() }
    val who = if (firstName.equals("Unknown", ignoreCase = true)) "them" else firstName
    return "Follow up with $who: $action"
}

/** The phone edits follow-ups as lines of text. Unchanged lines keep their timing and draft. */
fun mergeEditedFollowUps(original: List<FollowUp>, lines: List<String>): List<FollowUp> =
    lines.map { line ->
        original.firstOrNull { sameFollowUp(it.text, line) }?.copy(text = line) ?: FollowUp(line)
    }
