package app.recall.phone.hub

import android.util.Log
import app.recall.phone.core.EmbedResponse
import app.recall.phone.core.MatchDecision
import app.recall.phone.core.RecognizeResponse
import app.recall.phone.core.buildCard
import app.recall.phone.core.confirmCard
import app.recall.phone.core.matchFace
import app.recall.phone.data.PeopleRepository
import app.recall.phone.data.SettingsStore
import app.recall.phone.net.BackendClient
import app.recall.phone.net.BackendException
import kotlinx.coroutines.delay

class Recognizer(
    private val people: PeopleRepository,
    private val backend: BackendClient,
    private val settings: SettingsStore,
) {
    suspend fun recognize(jpeg: ByteArray): RecognizeResponse {
        val embedding = try {
            embedWithRetry(jpeg).faces.firstOrNull()?.embedding
                ?: return RecognizeResponse(match = false, reason = "no_face")
        } catch (e: BackendException) {
            Log.i(TAG, "No face vector: ${e.title}")
            return when (e.title) {
                "no_face", "multiple_faces" -> RecognizeResponse(false, reason = e.title)
                else -> backupOrUnreachable()
            }
        }

        val threshold = settings.current().matchThreshold
        return when (
            val decision = matchFace(embedding.toFloatArray(), people.candidates(), threshold)
        ) {
            is MatchDecision.NoMatch -> {
                Log.i(TAG, "No match. Best score ${decision.bestScore}, threshold $threshold")
                RecognizeResponse(false, reason = "no_match")
            }
            is MatchDecision.Match -> {
                Log.i(TAG, "Match. Score ${decision.score}, close call ${decision.runnerUpId != null}")
                val person = people.person(decision.personId)
                    ?: return RecognizeResponse(false, reason = "no_match")
                val card = buildCard(person.profile)
                RecognizeResponse(
                    match = true,
                    personId = person.id,
                    score = decision.score,
                    // A locked profile always shows its prepared card, even on a close call.
                    card = if (decision.runnerUpId != null && !person.profile.locked) {
                        confirmCard(card)
                    } else {
                        card
                    },
                    capturePersonId = person.id.takeIf { decision.runnerUpId == null },
                )
            }
        }
    }

    /** A tunnel connection can go stale while idle, so a network failure gets one more try. */
    private suspend fun embedWithRetry(jpeg: ByteArray): EmbedResponse = try {
        backend.embed(jpeg)
    } catch (e: BackendException) {
        if (e.title != null) throw e
        delay(RETRY_DELAY_MS)
        backend.embed(jpeg)
    }

    private companion object {
        const val TAG = "RecallMatch"
        const val RETRY_DELAY_MS = 300L
    }

    /** The demo backup card. It is off until a person is chosen. */
    private suspend fun backupOrUnreachable(): RecognizeResponse {
        val backup = people.demoBackup()
            ?: return RecognizeResponse(false, reason = "backend_unreachable")
        return RecognizeResponse(
            match = true,
            personId = backup.id,
            score = 0f,
            card = buildCard(backup.profile),
        )
    }
}
