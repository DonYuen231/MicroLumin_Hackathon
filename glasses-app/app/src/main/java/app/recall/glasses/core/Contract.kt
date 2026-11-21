package app.recall.glasses.core

import java.io.File
import org.json.JSONException
import org.json.JSONObject

enum class Mode(val title: String) {
    HOME("Recall"),
    CAPTURE("Capture"),
    RECOGNIZE("Recognize");

    fun next(): Mode = entries[(ordinal + 1) % entries.size]
}

sealed interface InputAction {
    data class Scroll(val delta: Int) : InputAction
    data object Primary : InputAction
    data object SwitchMode : InputAction
}

data class DisplayItem(val label: String, val text: String)

/** The first screen of a card: the name and the one most useful thing to say. */
data class Glance(val name: String, val line: String)

data class Card(
    val name: String,
    val headline: String,
    val displayItems: List<DisplayItem>,
    val spokenItems: List<String>,
    val glance: Glance? = null,
) {
    fun displayText(): String = buildString {
        append(name)
        if (headline.isNotBlank()) append('\n').append(headline)
        displayItems.forEach { append('\n').append(it.label).append(": ").append(it.text) }
    }
}

sealed interface RecognizeResult {
    data class Match(val card: Card, val score: Double) : RecognizeResult
    data class Failure(val reason: String) : RecognizeResult
}

const val REASON_PHONE_UNREACHABLE = "phone_unreachable"
const val REASON_CAMERA_FAILED = "camera_failed"

fun messageFor(reason: String): String = when (reason) {
    "no_match" -> "No match"
    "no_face" -> "No face found, try again"
    "multiple_faces" -> "Too many faces, aim again"
    "backend_unreachable" -> "Backend offline"
    "invalid_image" -> "Photo failed, try again"
    "untrusted_device" -> "Trust glasses on phone"
    REASON_PHONE_UNREACHABLE -> "Phone not reachable"
    REASON_CAMERA_FAILED -> "Camera failed, try again"
    else -> "Something went wrong, try again"
}

/** Parses a /recognize response. A bad body becomes a failure, so it can never crash the app. */
fun parseRecognize(body: String): RecognizeResult = try {
    val json = JSONObject(body)
    val cardJson = json.optJSONObject("card")
    if (json.optBoolean("match") && cardJson != null) {
        RecognizeResult.Match(parseCard(cardJson), json.optDouble("score", 0.0))
    } else {
        RecognizeResult.Failure(json.optString("reason", "unknown"))
    }
} catch (_: JSONException) {
    RecognizeResult.Failure("unknown")
}

private fun parseCard(json: JSONObject): Card {
    val display = json.optJSONArray("display_items")
    val spoken = json.optJSONArray("spoken_items")
    return Card(
        name = json.optString("name"),
        headline = json.optString("headline"),
        displayItems = (0 until (display?.length() ?: 0)).mapNotNull { index ->
            display?.optJSONObject(index)?.let {
                DisplayItem(it.optString("label"), it.optString("text"))
            }
        },
        spokenItems = (0 until (spoken?.length() ?: 0)).map { spoken!!.optString(it) },
        glance = json.optJSONObject("glance")?.let {
            Glance(it.optString("name"), it.optString("line"))
                .takeIf { glance -> glance.name.isNotBlank() && glance.line.isNotBlank() }
        },
    )
}

/** Converts the little-endian integer from `WifiManager.getDhcpInfo()` to dotted form. */
fun dhcpIntToIp(value: Int): String? {
    if (value == 0) return null
    return listOf(0, 8, 16, 24).joinToString(".") { shift -> ((value ushr shift) and 0xFF).toString() }
}

interface CameraSource {
    /** Returns exactly [count] JPEGs, or throws. The camera is closed again before returning. */
    fun takeStills(count: Int): List<ByteArray>
}

interface AudioSource {
    fun start(file: File)

    /** Returns true when the file holds playable audio. */
    fun stop(): Boolean
}

interface PhoneApi {
    fun recognize(jpeg: ByteArray): RecognizeResult
}

interface Speaker {
    val available: Boolean

    /** Reads the indexed items in order, replacing anything already being read. */
    fun say(items: List<Pair<Int, String>>, onItem: (Int) -> Unit = {})
    fun stop()
    fun shutdown()
}

interface Cues {
    fun recordStart()
    fun recordStop()
    fun match()
    fun noMatch()
}

data class MouseCandidate(
    val address: String,
    val name: String,
    val bonded: Boolean,
    val pointing: Boolean,
)

data class PairingState(
    val status: String = "",
    val devices: List<MouseCandidate> = emptyList(),
)

interface MousePairing {
    fun open(listener: (PairingState) -> Unit)
    fun pair(address: String)
    fun close()

    /** The name of a connected Bluetooth mouse, if there is one. */
    fun connectedName(): String?
}

interface Ui {
    fun render(screen: Screen)
    fun scrollBody(delta: Int)
}

data class Screen(
    val title: String,
    val status: String = "",
    val body: String = "",
    val hint: String = "",
    val crosshair: Boolean = false,
    val recording: Boolean = false,
)
