package app.recall.phone.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

const val AMBIGUITY_MARGIN = 0.05f
const val MAX_VECTORS_PER_PERSON = 10

data class FaceCandidate(val personId: String, val vectors: List<FloatArray>)

sealed interface MatchDecision {
    /** [runnerUpId] is set when another person scored within [AMBIGUITY_MARGIN]. */
    data class Match(val personId: String, val score: Float, val runnerUpId: String?) : MatchDecision

    data class NoMatch(val bestScore: Float?) : MatchDecision
}

/** Vectors are L2-normalized, so cosine similarity is a dot product. */
fun dot(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "Vector sizes differ" }
    var sum = 0f
    for (i in a.indices) sum += a[i] * b[i]
    return sum
}

fun matchFace(
    query: FloatArray,
    people: List<FaceCandidate>,
    threshold: Float,
): MatchDecision {
    val ranked = people.mapNotNull { person ->
        person.vectors
            .filter { it.size == query.size }
            .maxOfOrNull { dot(query, it) }
            ?.let { person.personId to it }
    }.sortedByDescending { it.second }

    val best = ranked.firstOrNull() ?: return MatchDecision.NoMatch(null)
    if (best.second < threshold) return MatchDecision.NoMatch(best.second)
    val runnerUp = ranked.getOrNull(1)?.takeIf { best.second - it.second <= AMBIGUITY_MARGIN }
    return MatchDecision.Match(best.first, best.second, runnerUp?.first)
}

fun FloatArray.toBytes(): ByteArray {
    val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    forEach(buffer::putFloat)
    return buffer.array()
}

fun ByteArray.toFloats(): FloatArray {
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(buffer.remaining()).also(buffer::get)
}
