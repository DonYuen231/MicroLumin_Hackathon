package app.recall.phone.hub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.recall.phone.core.BuildProfileRequest
import app.recall.phone.core.CaptureSummary
import app.recall.phone.core.DetectedFace
import app.recall.phone.core.Encounter
import app.recall.phone.core.MatchDecision
import app.recall.phone.core.MetAt
import app.recall.phone.core.Profile
import app.recall.phone.core.TranscriptIn
import app.recall.phone.core.matchFace
import app.recall.phone.core.parseInstant
import app.recall.phone.data.PeopleRepository
import app.recall.phone.data.SettingsStore
import app.recall.phone.net.BackendClient
import app.recall.phone.net.BackendException
import app.recall.phone.net.GlassesClient
import app.recall.phone.net.GlassesException
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

enum class ItemStatus { WORKING, DONE, FAILED }

data class CaptureItem(
    val captureId: String,
    val step: String,
    val status: ItemStatus = ItemStatus.WORKING,
)

data class CaptureRunState(
    val running: Boolean = false,
    val message: String = "",
    val items: List<CaptureItem> = emptyList(),
)

class CaptureProcessor(
    cacheDir: File,
    private val people: PeopleRepository,
    private val backend: BackendClient,
    private val glasses: GlassesClient,
    private val settings: SettingsStore,
) {
    private val workDir = File(cacheDir, "captures")
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(CaptureRunState())
    val state: StateFlow<CaptureRunState> = mutableState

    suspend fun pullAndProcess() {
        if (!lock.tryLock()) return
        try {
            mutableState.value = CaptureRunState(running = true, message = "Checking the glasses")
            val captures = glasses.list().captures
            if (captures.isEmpty()) {
                mutableState.update { it.copy(message = "No new captures on the glasses.") }
                return
            }
            mutableState.update {
                it.copy(
                    message = "Processing ${captures.size} capture(s)",
                    items = captures.map { c -> CaptureItem(c.captureId, "Waiting") },
                )
            }
            var failed = 0
            for (capture in captures) {
                try {
                    processOne(capture)
                    setStep(capture.captureId, "Saved", ItemStatus.DONE)
                } catch (e: BackendException) {
                    failed++
                    setStep(capture.captureId, e.message.orEmpty(), ItemStatus.FAILED)
                } catch (e: GlassesException) {
                    failed++
                    setStep(capture.captureId, e.message.orEmpty(), ItemStatus.FAILED)
                }
            }
            val summary = if (failed == 0) {
                "All captures saved."
            } else {
                "$failed capture(s) failed and stayed on the glasses. Try again."
            }
            mutableState.update { it.copy(message = summary) }
        } catch (e: GlassesException) {
            mutableState.update { it.copy(message = e.message.orEmpty()) }
        } finally {
            mutableState.update { it.copy(running = false) }
            lock.unlock()
        }
    }

    private suspend fun processOne(capture: CaptureSummary) {
        val id = capture.captureId
        if (!GlassesClient.SAFE_ID.matches(id)) throw GlassesException("Invalid capture ID.")
        if (people.isProcessed(id)) {
            // Saved earlier, but the delete on the glasses did not finish.
            setStep(id, "Removing from glasses")
            glasses.delete(id)
            return
        }
        val dir = File(workDir, id)
        try {
            withContext(Dispatchers.IO) { dir.deleteRecursively(); dir.mkdirs() }

            setStep(id, "Downloading")
            val photos = (0 until capture.photos).map { index ->
                File(dir, "photo_$index.jpg").also { glasses.downloadPhoto(id, index, it) }
            }
            val audio = if (capture.hasAudio) {
                File(dir, "audio.m4a").also { glasses.downloadAudio(id, it) }
            } else {
                null
            }

            setStep(id, "Reading faces")
            val faces = embedPhotos(photos)
            val embeddings = faces.map { it.second.embedding.toFloatArray() }
            val existingId = findExisting(embeddings)
            val existing = existingId?.let { people.person(it) }?.profile
            val current = settings.current()
            val metAt = MetAt(capture.startedAtOrNow(), current.place)

            val profile = if (audio != null) {
                setStep(id, "Transcribing")
                val transcript = backend.transcribe(audio)
                if (transcript.segments.isEmpty()) {
                    withoutConversation(existing, metAt)
                } else {
                    if (current.wearerName.isBlank()) {
                        throw BackendException("not_configured", "Add your name in Settings.")
                    }
                    setStep(id, "Writing profile")
                    backend.buildProfile(
                        BuildProfileRequest(
                            transcript = TranscriptIn(transcript.segments),
                            wearerName = current.wearerName,
                            existingProfile = existing,
                            metAt = metAt,
                            sharedLinks = existing?.sharedLinks.orEmpty(),
                        ),
                    )
                }
            } else {
                withoutConversation(existing, metAt)
            }

            setStep(id, "Saving")
            val thumbnail = faces.firstOrNull()?.let { (file, face) -> makeThumbnail(file, face) }
            people.saveCapture(existingId, profile, embeddings, thumbnail, id)

            setStep(id, "Removing from glasses")
            glasses.delete(id)
        } finally {
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
        }
    }

    private suspend fun embedPhotos(photos: List<File>): List<Pair<File, DetectedFace>> =
        photos.mapNotNull { photo ->
            val bytes = withContext(Dispatchers.IO) { photo.readBytes() }
            try {
                backend.embed(bytes).faces.firstOrNull()?.let { photo to it }
            } catch (e: BackendException) {
                // One blurry photo should not lose the capture.
                if (e.title in PHOTO_SKIP_ERRORS) null else throw e
            }
        }

    /** A close call between two people is not merged, because a wrong merge is hard to undo. */
    private suspend fun findExisting(embeddings: List<FloatArray>): String? {
        val candidates = people.candidates()
        val threshold = settings.current().matchThreshold
        return embeddings
            .map { matchFace(it, candidates, threshold) }
            .filterIsInstance<MatchDecision.Match>()
            .maxByOrNull { it.score }
            ?.takeIf { it.runnerUpId == null }
            ?.personId
    }

    private fun withoutConversation(existing: Profile?, metAt: MetAt): Profile {
        val base = existing ?: Profile()
        return base.copy(encounters = base.encounters + Encounter(metAt.time, metAt.place))
    }

    private fun CaptureSummary.startedAtOrNow(): String =
        parseInstant(startedAt)?.toString() ?: Instant.now().toString()

    private suspend fun makeThumbnail(file: File, face: DetectedFace): ByteArray? =
        withContext(Dispatchers.Default) {
            val source = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
            try {
                val crop = cropAround(source, face.bbox)
                val small = Bitmap.createScaledBitmap(crop, THUMB_PX, THUMB_PX, true)
                ByteArrayOutputStream().use { out ->
                    small.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    out.toByteArray()
                }
            } finally {
                source.recycle()
            }
        }

    private fun cropAround(source: Bitmap, bbox: List<Double>): Bitmap {
        if (bbox.size < 4) {
            val side = minOf(source.width, source.height)
            return Bitmap.createBitmap(
                source,
                (source.width - side) / 2,
                (source.height - side) / 2,
                side,
                side,
            )
        }
        val centerX = (bbox[0] + bbox[2]) / 2
        val centerY = (bbox[1] + bbox[3]) / 2
        val wanted = (maxOf(bbox[2] - bbox[0], bbox[3] - bbox[1]) * 1.5).toInt().coerceAtLeast(1)
        val side = minOf(wanted, source.width, source.height)
        val left = (centerX - side / 2).toInt().coerceIn(0, source.width - side)
        val top = (centerY - side / 2).toInt().coerceIn(0, source.height - side)
        return Bitmap.createBitmap(source, left, top, side, side)
    }

    private fun setStep(id: String, step: String, status: ItemStatus = ItemStatus.WORKING) {
        mutableState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.captureId == id) it.copy(step = step, status = status) else it
                },
            )
        }
    }

    private companion object {
        const val THUMB_PX = 256
        val PHOTO_SKIP_ERRORS = setOf("no_face", "multiple_faces", "invalid_image")
    }
}
