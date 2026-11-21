package app.recall.phone.hub

import app.recall.phone.core.MatchDecision
import app.recall.phone.core.RecognizeResponse
import app.recall.phone.core.buildCard
import app.recall.phone.core.confirmCard
import app.recall.phone.core.matchFace
import app.recall.phone.data.PeopleRepository
import app.recall.phone.data.SettingsStore
import app.recall.phone.net.BackendClient
import app.recall.phone.net.BackendException

class Recognizer(
    private val people: PeopleRepository,
    private val backend: BackendClient,
    private val settings: SettingsStore,
) {
    suspend fun recognize(jpeg: ByteArray): RecognizeResponse {
        val embedding = try {
            backend.embed(jpeg).faces.firstOrNull()?.embedding
                ?: return RecognizeResponse(match = false, reason = "no_face")
        } catch (e: BackendException) {
            return when (e.title) {
                "no_face", "multiple_faces" -> RecognizeResponse(false, reason = e.title)
                else -> backupOrUnreachable()
            }
        }

        val threshold = settings.current().matchThreshold
        return when (
            val decision = matchFace(embedding.toFloatArray(), people.candidates(), threshold)
        ) {
            is MatchDecision.NoMatch -> RecognizeResponse(false, reason = "no_match")
            is MatchDecision.Match -> {
                val person = people.person(decision.personId)
                    ?: return RecognizeResponse(false, reason = "no_match")
                val card = buildCard(person.profile)
                RecognizeResponse(
                    match = true,
                    personId = person.id,
                    score = decision.score,
                    card = if (decision.runnerUpId != null) confirmCard(card) else card,
                )
            }
        }
    }

    /** The stage backup from README section 13. It is off until a person is chosen. */
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
