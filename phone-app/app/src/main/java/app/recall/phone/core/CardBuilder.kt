package app.recall.phone.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
private const val MAX_GLANCE_ROW_CHARS = 38
private const val MAX_GLANCE_SUMMARY_CHARS = 150

fun parseInstant(value: String?): Instant? =
    value?.let { runCatching { Instant.parse(it) }.getOrNull() }

fun latestEncounter(profile: Profile): Encounter? =
    profile.encounters.maxByOrNull { parseInstant(it.time) ?: Instant.EPOCH }

fun formatDate(value: String?, zone: ZoneId = ZoneId.systemDefault()): String =
    parseInstant(value)?.atZone(zone)?.format(DATE).orEmpty()

/** Builds the glasses card from README section 7.1. */
fun buildCard(profile: Profile, zone: ZoneId = ZoneId.systemDefault()): ProfileCard {
    val items = buildList {
        latestEncounter(profile)?.let { encounter ->
            val text = listOf(encounter.place, formatDate(encounter.time, zone))
                .filter { it.isNotBlank() }
                .joinToString(", ")
            if (text.isNotBlank()) add(DisplayItem("Last met", text))
        }
        profile.topics.forEach { add(DisplayItem("Talk about", it)) }
        profile.commonInterests.forEach { add(DisplayItem("In common", it)) }
        profile.followUps.forEach { add(DisplayItem("Follow up", it.text)) }
        profile.sinceYouMet.forEach { add(DisplayItem("Since you met", it)) }
        profile.avoid.forEach { add(DisplayItem("Avoid", it)) }
    }
    // Avoid items come last: the glasses only read them when the user scrolls to them.
    val spoken = profile.spoken.ifEmpty { listOf(profile.name) } +
        profile.avoid.map { "Avoid: $it" }
    return ProfileCard(
        name = profile.name,
        headline = profile.headline,
        displayItems = items,
        spokenItems = spoken,
        glance = Glance(profile.name, glanceLine(profile)),
    )
}

/**
 * Up to five one-line rows, which is all the glasses show without scrolling. Avoid gets its own
 * row and is never used as something to talk about. A sparse profile falls back to its summary.
 */
fun glanceLine(profile: Profile): String {
    fun safe(items: List<String>) = items.map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !mentionsAvoid(it, profile.avoid) }

    val rows = listOfNotNull(
        profile.headline.trim().ifEmpty { null }?.let(::fitRow),
        safe(profile.topics)?.let { fitRow("Talk: $it") },
        safe(profile.commonInterests)?.let { fitRow("Both: $it") },
        safe(profile.followUps.map { it.text })?.let { fitRow("Do: $it") },
        profile.avoid.map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?.let { fitRow("Avoid: $it") },
    )
    if (rows.size >= 2) return rows.joinToString("\n")
    val summary = profile.summary.trim()
    if (summary.isNotEmpty() && !mentionsAvoid(summary, profile.avoid)) return fitSummary(summary)
    return rows.firstOrNull().orEmpty()
}

private fun fitRow(text: String): String {
    if (text.length <= MAX_GLANCE_ROW_CHARS) return text
    val cut = text.take(MAX_GLANCE_ROW_CHARS - 1).substringBeforeLast(' ')
    return cut.trimEnd(' ', ',', '.', ';', ':') + "…"
}

/** Fits the glasses' five visible lines, and never cuts a word in half. */
private fun fitSummary(text: String): String {
    if (text.length <= MAX_GLANCE_SUMMARY_CHARS) return text
    val cut = text.take(MAX_GLANCE_SUMMARY_CHARS - 1).substringBeforeLast(' ')
    return cut.trimEnd(' ', ',', '.', ';') + "…"
}

private fun mentionsAvoid(text: String, avoid: List<String>): Boolean =
    avoid.map { it.trim() }.any {
        it.isNotEmpty() && (text.contains(it, ignoreCase = true) || it.contains(text, ignoreCase = true))
    }

fun confirmCard(card: ProfileCard): ProfileCard {
    val question = "Is this ${card.name}?"
    return card.copy(
        headline = question,
        spokenItems = listOf(question) + card.spokenItems,
        glance = Glance(card.name, question),
    )
}
