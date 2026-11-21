package app.recall.glasses.device

import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import app.recall.glasses.core.ClickDebouncer
import app.recall.glasses.core.GestureTracker
import app.recall.glasses.core.InputAction

/** Maps mouse, touchpad, and key events to app actions. Raw events are logged for the device check. */
class InputMapper(private val emit: (InputAction) -> Unit) {
    private val gestures = GestureTracker()
    private val debouncer = ClickDebouncer()

    fun onKey(event: KeyEvent): Boolean {
        val action = keyAction(event.keyCode) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.d(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} source=${event.source}")
            if (event.repeatCount == 0 || action is InputAction.Scroll) send(action, event.eventTime)
        }
        return true // The Up event is consumed too, or Android would still run Back.
    }

    fun onTouch(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val secondary = event.buttonState and MotionEvent.BUTTON_SECONDARY != 0
                send(if (secondary) InputAction.SwitchMode else InputAction.Primary, event.eventTime)
            }
            return true
        }
        trackGesture(event)
        return true
    }

    fun onGenericMotion(event: MotionEvent): Boolean {
        Log.v(TAG, "motion ${MotionEvent.actionToString(event.actionMasked)} source=${event.source}")
        if (event.isFromSource(InputDevice.SOURCE_TOUCHPAD) && !event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            trackGesture(event)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (wheel != 0f) {
                    // Wheel up is positive on Android, and it should move back up the card.
                    send(InputAction.Scroll(if (wheel > 0) -1 else 1), event.eventTime)
                    return true
                }
            }
            MotionEvent.ACTION_BUTTON_PRESS -> when (event.actionButton) {
                MotionEvent.BUTTON_PRIMARY -> {
                    send(InputAction.Primary, event.eventTime)
                    return true
                }
                MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_BACK -> {
                    send(InputAction.SwitchMode, event.eventTime)
                    return true
                }
            }
        }
        return false
    }

    private fun trackGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> gestures.down(event.x, event.y, event.eventTime)
            MotionEvent.ACTION_MOVE -> gestures.move(event.x, event.y)
            MotionEvent.ACTION_UP ->
                gestures.up(event.x, event.y, event.eventTime)?.let { send(it, event.eventTime) }
            MotionEvent.ACTION_CANCEL -> gestures.cancel()
        }
    }

    private fun send(action: InputAction, timeMs: Long) {
        if (debouncer.accept(action, timeMs)) emit(action)
    }

    private fun keyAction(code: Int): InputAction? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP ->
            InputAction.Scroll(-1)
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN ->
            InputAction.Scroll(1)
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A -> InputAction.Primary
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_MENU -> InputAction.SwitchMode
        else -> null
    }

    private companion object {
        const val TAG = "RecallInput"
    }
}
