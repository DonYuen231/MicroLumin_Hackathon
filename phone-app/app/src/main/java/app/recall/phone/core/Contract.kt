package app.recall.phone.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The JSON contract from README section 7 and 8. */
val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class Segment(
    val speaker: String,
    val start: Double,
    val end: Double,
    val text: String,
)

@Serializable
data class Transcript(
    @SerialName("duration_s") val durationS: Double = 0.0,
    val segments: List<Segment> = emptyList(),
)

@Serializable
data class TranscriptIn(val segments: List<Segment>)

@Serializable
data class DetectedFace(
    val bbox: List<Double> = emptyList(),
    @SerialName("det_score") val detScore: Double = 0.0,
    val embedding: List<Float>,
)

@Serializable
data class EmbedResponse(val faces: List<DetectedFace> = emptyList())

@Serializable
data class Encounter(
    val time: String,
    val place: String = "",
    val notes: String = "",
)

@Serializable
data class Profile(
    val name: String = "Unknown",
    val headline: String = "",
    val summary: String = "",
    val encounters: List<Encounter> = emptyList(),
    val topics: List<String> = emptyList(),
    @SerialName("follow_ups") val followUps: List<String> = emptyList(),
    val avoid: List<String> = emptyList(),
    @SerialName("since_you_met") val sinceYouMet: List<String> = emptyList(),
    @SerialName("shared_links") val sharedLinks: List<String> = emptyList(),
    val spoken: List<String> = emptyList(),
)

@Serializable
data class MetAt(val time: String, val place: String = "")

@Serializable
data class BuildProfileRequest(
    val transcript: TranscriptIn,
    @SerialName("wearer_name") val wearerName: String,
    @SerialName("existing_profile") val existingProfile: Profile? = null,
    @SerialName("met_at") val metAt: MetAt,
    @SerialName("shared_links") val sharedLinks: List<String> = emptyList(),
)

@Serializable
data class RefreshRequest(
    @SerialName("person_id") val personId: String,
    val name: String,
    @SerialName("shared_links") val sharedLinks: List<String> = emptyList(),
    val profile: Profile? = null,
)

@Serializable
data class RefreshResponse(@SerialName("since_you_met") val sinceYouMet: List<String> = emptyList())

@Serializable
data class ProblemDetails(
    val type: String = "",
    val title: String = "",
    val status: Int = 0,
    val detail: String = "",
)

@Serializable
data class CaptureSummary(
    @SerialName("capture_id") val captureId: String,
    @SerialName("started_at") val startedAt: String = "",
    @SerialName("ended_at") val endedAt: String = "",
    val photos: Int = 0,
    @SerialName("has_audio") val hasAudio: Boolean = false,
)

@Serializable
data class CaptureList(val captures: List<CaptureSummary> = emptyList())

@Serializable
data class DisplayItem(val label: String, val text: String)

/** The glasses' first screen: who this is and the one most useful thing to say. */
@Serializable
data class Glance(val name: String, val line: String)

@Serializable
data class ProfileCard(
    val name: String,
    val headline: String,
    @SerialName("display_items") val displayItems: List<DisplayItem>,
    @SerialName("spoken_items") val spokenItems: List<String>,
    val glance: Glance? = null,
)

@Serializable
data class RecognizeResponse(
    val match: Boolean,
    val reason: String? = null,
    @SerialName("person_id") val personId: String? = null,
    val score: Float? = null,
    val card: ProfileCard? = null,
)
