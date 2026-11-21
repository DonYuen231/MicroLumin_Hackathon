package app.recall.phone.core

/** Below this, the photo clearly shows someone else who happens to have the same name. */
const val NAME_MATCH_MIN_FACE_SCORE = 0.2f

data class NamedPerson(val id: String, val name: String)

/** A first name alone is too common to identify someone, so this needs at least two words. */
fun normalizedFullName(name: String): String? {
    val words = name.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}'-]+"), " ")
        .trim()
        .split(' ')
        .filter { it.isNotEmpty() }
    val placeholder = "unknown" in words || (words.size == 2 && words[0] == "person")
    return if (words.size < 2 || placeholder) null else words.joinToString(" ")
}

/**
 * The backup when the face did not match. It needs exactly one saved person with the same full
 * name, and [faceScore] can veto it when a photo clearly shows a different person.
 */
fun matchByName(
    name: String,
    people: List<NamedPerson>,
    faceScore: (String) -> Float?,
): String? {
    val wanted = normalizedFullName(name) ?: return null
    val person = people.filter { normalizedFullName(it.name) == wanted }.singleOrNull()
        ?: return null
    val score = faceScore(person.id)
    return person.id.takeIf { score == null || score >= NAME_MATCH_MIN_FACE_SCORE }
}

/** The best similarity between any new photo and any of this person's saved vectors. */
fun bestFaceScore(embeddings: List<FloatArray>, candidate: FaceCandidate?): Float? =
    candidate?.vectors.orEmpty().flatMap { saved ->
        embeddings.filter { it.size == saved.size }.map { dot(it, saved) }
    }.maxOrNull()
