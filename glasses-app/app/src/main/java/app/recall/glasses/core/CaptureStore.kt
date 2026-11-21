package app.recall.glasses.core

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import org.json.JSONException
import org.json.JSONObject

data class CaptureInfo(
    val id: String,
    val startedAt: String,
    val endedAt: String,
    val photos: Int,
    val hasAudio: Boolean,
)

/**
 * Stores each capture in `<root>/<id>/`. A capture counts as finished only once `meta.json`
 * exists, so one that is still recording is never listed or served.
 */
class CaptureStore(private val root: File) {
    init {
        root.mkdirs()
    }

    @Synchronized
    fun newCapture(at: Instant): String {
        val base = "c_" + ID_TIME.format(at)
        var id = base
        var suffix = 2
        while (File(root, id).exists()) id = "${base}_${suffix++}"
        check(File(root, id).mkdirs()) { "Could not create capture folder" }
        return id
    }

    fun audioFile(id: String): File = File(dir(id), AUDIO)

    fun savePhotos(id: String, photos: List<ByteArray>) {
        photos.forEachIndexed { index, bytes -> File(dir(id), photoName(index)).writeBytes(bytes) }
    }

    fun removeAudio(id: String) {
        audioFile(id).delete()
    }

    @Synchronized
    fun finish(id: String, startedAt: Instant, endedAt: Instant) {
        val meta = JSONObject()
            .put("started_at", utc(startedAt))
            .put("ended_at", utc(endedAt))
        val temp = File(dir(id), "$META.tmp")
        temp.writeText(meta.toString())
        check(temp.renameTo(File(dir(id), META))) { "Could not save capture" }
    }

    @Synchronized
    fun discard(id: String) {
        remove(id)
    }

    @Synchronized
    fun list(): List<CaptureInfo> = root.listFiles().orEmpty()
        .filter { it.isDirectory && SAFE_ID.matches(it.name) }
        .mapNotNull { read(it) }
        .sortedBy { it.id }

    @Synchronized
    fun photo(id: String, index: Int): File? {
        val info = finished(id) ?: return null
        return if (index in 0 until info.photos) File(dir(id), photoName(index)) else null
    }

    @Synchronized
    fun audio(id: String): File? {
        val info = finished(id) ?: return null
        return if (info.hasAudio) audioFile(id) else null
    }

    /** Deletes a finished capture. A capture still recording is left alone. */
    @Synchronized
    fun delete(id: String): Boolean {
        if (finished(id) == null) return false
        return remove(id)
    }

    /**
     * Run at startup, before anything is recording. A capture cut off by a crash keeps its photos.
     * Its audio is dropped, because an MPEG-4 file that was never closed cannot be played.
     */
    @Synchronized
    fun recover(now: Instant) {
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { folder ->
            when {
                folder.name.startsWith(TRASH) -> folder.deleteRecursively()
                !SAFE_ID.matches(folder.name) || File(folder, META).exists() -> Unit
                countPhotos(folder) == 0 -> folder.deleteRecursively()
                else -> {
                    File(folder, AUDIO).delete()
                    File(folder, "$META.tmp").delete()
                    val started = Instant.ofEpochMilli(folder.lastModified())
                    finish(folder.name, minOf(started, now), now)
                }
            }
        }
    }

    private fun finished(id: String): CaptureInfo? {
        if (!SAFE_ID.matches(id)) return null
        val folder = File(root, id)
        return if (folder.isDirectory) read(folder) else null
    }

    private fun read(folder: File): CaptureInfo? {
        val metaFile = File(folder, META)
        if (!metaFile.isFile) return null
        return try {
            val meta = JSONObject(metaFile.readText())
            CaptureInfo(
                id = folder.name,
                startedAt = meta.getString("started_at"),
                endedAt = meta.getString("ended_at"),
                photos = countPhotos(folder),
                hasAudio = File(folder, AUDIO).length() > 0,
            )
        } catch (_: JSONException) {
            null
        }
    }

    /** Counts unbroken photos from 0, so `photos` always equals what can be downloaded. */
    private fun countPhotos(folder: File): Int {
        var count = 0
        while (File(folder, photoName(count)).length() > 0) count++
        return count
    }

    /** Renames first, so a delete cut off halfway cannot leave a capture with missing files. */
    private fun remove(id: String): Boolean {
        val folder = dir(id)
        val trash = File(root, "$TRASH$id")
        trash.deleteRecursively()
        if (!folder.renameTo(trash)) return false
        trash.deleteRecursively()
        return true
    }

    private fun dir(id: String): File {
        require(SAFE_ID.matches(id)) { "Invalid capture ID" }
        return File(root, id)
    }

    private fun photoName(index: Int) = "photo_$index.jpg"

    private fun utc(at: Instant) = at.truncatedTo(ChronoUnit.SECONDS).toString()

    companion object {
        val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        private const val META = "meta.json"
        private const val AUDIO = "audio.m4a"
        private const val TRASH = ".deleting-"
        private val ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)
    }
}
