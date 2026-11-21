package app.recall.glasses.core

import kotlin.math.abs

/** Turns one finger's down, move, and up into a tap, long press, or swipe. */
class GestureTracker(
    // The touchpad is scaled up to the display, so a still finger can jump about 40 px.
    private val swipePx: Float = 80f,
    private val longPressMs: Long = 600,
) {
    private var active = false
    private var startX = 0f
    private var startY = 0f
    private var startMs = 0L
    private var moved = false

    fun down(x: Float, y: Float, timeMs: Long) {
        active = true
        moved = false
        startX = x
        startY = y
        startMs = timeMs
    }

    fun move(x: Float, y: Float) {
        if (active && (abs(x - startX) >= swipePx || abs(y - startY) >= swipePx)) moved = true
    }

    fun cancel() {
        active = false
    }

    fun up(x: Float, y: Float, timeMs: Long): InputAction? {
        if (!active) return null
        move(x, y)
        active = false
        if (moved) {
            val dx = x - startX
            val dy = y - startY
            // Many glasses touchpads run along the temple, so a sideways swipe scrolls too.
            val travel = if (abs(dy) >= abs(dx)) -dy else dx
            return InputAction.Scroll(if (travel > 0) 1 else -1)
        }
        return if (timeMs - startMs >= longPressMs) InputAction.SwitchMode else InputAction.Primary
    }
}

/**
 * Drops repeats of a click. One mouse click can arrive as a touch event, a button event,
 * and a Back key, and each must count only once.
 */
class ClickDebouncer(private val windowMs: Long = 250) {
    private val last = mutableMapOf<InputAction, Long>()

    fun accept(action: InputAction, timeMs: Long): Boolean {
        if (action is InputAction.Scroll) return true
        val previous = last[action]
        if (previous != null && timeMs - previous < windowMs) return false
        last[action] = timeMs
        return true
    }
}

/** Ranks scan results: real mice first, then unclassified devices. Phones and audio are hidden. */
object MouseRanking {
    private const val MAJOR_MASK = 0x1F00
    private const val MAJOR_PERIPHERAL = 0x0500
    private const val MAJOR_UNCATEGORIZED = 0x1F00
    private const val POINTING_BIT = 0x80

    fun isPointing(deviceClass: Int): Boolean =
        deviceClass and MAJOR_MASK == MAJOR_PERIPHERAL && deviceClass and POINTING_BIT != 0

    /** Bluetooth LE mice often report no class at all, so those stay listed if they have a name. */
    fun isCandidate(deviceClass: Int, name: String?): Boolean {
        if (isPointing(deviceClass)) return true
        val major = deviceClass and MAJOR_MASK
        return !name.isNullOrBlank() && (major == 0 || major == MAJOR_UNCATEGORIZED)
    }

    fun sort(devices: Collection<MouseCandidate>): List<MouseCandidate> = devices.sortedWith(
        compareByDescending<MouseCandidate> { it.pointing }
            .thenByDescending { it.bonded }
            .thenBy { it.name.lowercase() },
    )
}
