package app.recall.phone.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
private const val MAX_GLANCE_CHARS = 60

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
        profile.followUps.forEach { add(DisplayItem("Follow up", it)) }
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

/** A promise the wearer owes comes first, then a topic. An avoid item is never shown here. */
fun glanceLine(profile: Profile): String {
    val candidates = profile.followUps + profile.topics
    val line = candidates
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !mentionsAvoid(it, profile.avoid) }
        ?: profile.headline.trim()
    return line.take(MAX_GLANCE_CHARS)
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
