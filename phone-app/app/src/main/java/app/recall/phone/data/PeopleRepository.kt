package app.recall.phone.data

import app.recall.phone.core.AppJson
import app.recall.phone.core.FaceCandidate
import app.recall.phone.core.NamedPerson
import app.recall.phone.core.Profile
import app.recall.phone.core.ProfileSeed
import app.recall.phone.core.latestEncounter
import app.recall.phone.core.toBytes
import app.recall.phone.core.toFloats
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class PersonRecord(
    val id: String,
    val profile: Profile,
    val thumbnailPath: String?,
    val lastMetAt: String?,
    val createdAt: String,
    val isDemoBackup: Boolean,
)

class PeopleRepository(private val dao: RecallDao, filesDir: File) {
    private val thumbsDir = File(filesDir, "thumbs")

    fun observePeople(): Flow<List<PersonRecord>> =
        dao.observePeople().map { people -> people.map(::toRecord) }

    suspend fun person(id: String): PersonRecord? = dao.person(id)?.let(::toRecord)

    suspend fun demoBackup(): PersonRecord? = dao.demoBackup()?.let(::toRecord)

    suspend fun candidates(): List<FaceCandidate> =
        dao.allVectors().groupBy { it.personId }.map { (personId, vectors) ->
            FaceCandidate(personId, vectors.map { it.vector.toFloats() })
        }

    suspend fun namedPeople(): List<NamedPerson> =
        dao.observePeople().first().map { NamedPerson(it.id, it.name) }

    suspend fun isProcessed(captureId: String): Boolean = dao.isProcessed(captureId)

    suspend fun saveCapture(
        existingId: String?,
        profile: Profile,
        embeddings: List<FloatArray>,
        thumbnail: ByteArray?,
        captureId: String,
    ): String {
        val existing = existingId?.let { dao.person(it) }
        val id = existing?.id ?: "p_${UUID.randomUUID().toString().take(8)}"
        val thumbnailPath = thumbnail?.let { writeThumbnail(id, it) } ?: existing?.thumbnailPath
        // The backend does not know this field, so a new conversation must not erase it.
        val kept = existing?.let { toRecord(it).profile.commonInterests }.orEmpty()
        val merged = if (profile.commonInterests.isEmpty()) {
            profile.copy(commonInterests = kept)
        } else {
            profile
        }
        val person = PersonEntity(
            id = id,
            name = profile.name,
            profileJson = AppJson.encodeToString(Profile.serializer(), merged),
            thumbnailPath = thumbnailPath,
            lastMetAt = latestEncounter(profile)?.time,
            createdAt = existing?.createdAt ?: Instant.now().toString(),
            isDemoBackup = existing?.isDemoBackup ?: false,
        )
        val vectors = embeddings.map { FaceVectorEntity(personId = id, vector = it.toBytes()) }
        dao.saveCapture(person, vectors, captureId)
        return id
    }

    suspend fun updateProfile(id: String, profile: Profile) {
        val current = dao.person(id) ?: return
        dao.upsertPerson(
            current.copy(
                name = profile.name,
                profileJson = AppJson.encodeToString(Profile.serializer(), profile),
                lastMetAt = latestEncounter(profile)?.time,
            ),
        )
    }

    /** Applies and removes a prepared profile. Meeting history and links are kept. */
    suspend fun applySeed(file: File) {
        val text = withContext(Dispatchers.IO) {
            if (file.exists()) runCatching { file.readText() }.getOrNull() else null
        } ?: return
        try {
            val seed = AppJson.decodeFromString(ProfileSeed.serializer(), text)
            val person = dao.observePeople().first()
                .firstOrNull { it.name.equals(seed.name, ignoreCase = true) } ?: return
            val current = toRecord(person).profile
            updateProfile(
                person.id,
                seed.profile.copy(
                    encounters = current.encounters,
                    sinceYouMet = current.sinceYouMet,
                    sharedLinks = current.sharedLinks,
                ),
            )
        } catch (_: IllegalArgumentException) {
            // A malformed file is ignored and still removed below.
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    suspend fun setDemoBackup(id: String, enabled: Boolean) = dao.setDemoBackup(id, enabled)

    suspend fun delete(id: String) {
        val person = dao.person(id)
        dao.deletePerson(id)
        withContext(Dispatchers.IO) { person?.thumbnailPath?.let { File(it).delete() } }
    }

    private suspend fun writeThumbnail(id: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            thumbsDir.mkdirs()
            File(thumbsDir, "$id.jpg").apply { writeBytes(bytes) }.absolutePath
        }

    private fun toRecord(entity: PersonEntity): PersonRecord = PersonRecord(
        id = entity.id,
        profile = runCatching {
            AppJson.decodeFromString(Profile.serializer(), entity.profileJson)
        }.getOrElse { Profile(name = entity.name) },
        thumbnailPath = entity.thumbnailPath,
        lastMetAt = entity.lastMetAt,
        createdAt = entity.createdAt,
        isDemoBackup = entity.isDemoBackup,
    )
}
