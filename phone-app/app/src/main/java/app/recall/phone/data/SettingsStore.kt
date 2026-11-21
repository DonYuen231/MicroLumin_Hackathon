package app.recall.phone.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.recall.phone.core.AppJson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val DEFAULT_THRESHOLD = 0.45f

data class AppSettings(
    val backendUrl: String = "",
    val appToken: String = "",
    val glassesAddress: String = "",
    val wearerName: String = "",
    val place: String = "Worn Hackathon",
    val matchThreshold: Float = DEFAULT_THRESHOLD,
    /** For live demos: one hour of follow-up timing passes in 10 seconds. */
    val demoSpeed: Boolean = false,
)

/** Optional setup file pushed over adb, so the long token need not be typed on the phone. */
@Serializable
private data class Provisioning(
    @SerialName("backend_url") val backendUrl: String? = null,
    @SerialName("app_token") val appToken: String? = null,
    @SerialName("glasses_address") val glassesAddress: String? = null,
    @SerialName("wearer_name") val wearerName: String? = null,
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val backendUrl = stringPreferencesKey("backend_url")
        val appToken = stringPreferencesKey("app_token")
        val glassesAddress = stringPreferencesKey("glasses_address")
        val wearerName = stringPreferencesKey("wearer_name")
        val place = stringPreferencesKey("place")
        val threshold = floatPreferencesKey("match_threshold")
        val demoSpeed = booleanPreferencesKey("demo_speed")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map(::fromPreferences)

    suspend fun current(): AppSettings = settings.first()

    suspend fun save(value: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.backendUrl] = value.backendUrl.trim().trimEnd('/')
            prefs[Keys.appToken] = value.appToken.trim()
            prefs[Keys.glassesAddress] = value.glassesAddress.trim()
            prefs[Keys.wearerName] = value.wearerName.trim()
            prefs[Keys.place] = value.place.trim()
            prefs[Keys.threshold] = value.matchThreshold
            prefs[Keys.demoSpeed] = value.demoSpeed
        }
    }

    suspend fun trustGlasses(address: String) {
        context.dataStore.edit { it[Keys.glassesAddress] = address }
    }

    /** Applies and removes files/provision.json if it exists. */
    suspend fun applyProvisioning() {
        val file = File(context.filesDir, "provision.json")
        val text = withContext(Dispatchers.IO) {
            if (file.exists()) runCatching { file.readText() }.getOrNull() else null
        } ?: return
        try {
            val incoming = AppJson.decodeFromString(Provisioning.serializer(), text)
            val now = current()
            save(
                now.copy(
                    backendUrl = incoming.backendUrl ?: now.backendUrl,
                    appToken = incoming.appToken ?: now.appToken,
                    glassesAddress = incoming.glassesAddress ?: now.glassesAddress,
                    wearerName = incoming.wearerName ?: now.wearerName,
                ),
            )
        } catch (_: IllegalArgumentException) {
            // A malformed file is ignored and still removed below.
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    private fun fromPreferences(prefs: Preferences) = AppSettings(
        backendUrl = prefs[Keys.backendUrl].orEmpty(),
        appToken = prefs[Keys.appToken].orEmpty(),
        glassesAddress = prefs[Keys.glassesAddress].orEmpty(),
        wearerName = prefs[Keys.wearerName].orEmpty(),
        place = prefs[Keys.place] ?: "Worn Hackathon",
        matchThreshold = prefs[Keys.threshold] ?: DEFAULT_THRESHOLD,
        demoSpeed = prefs[Keys.demoSpeed] ?: false,
    )
}
