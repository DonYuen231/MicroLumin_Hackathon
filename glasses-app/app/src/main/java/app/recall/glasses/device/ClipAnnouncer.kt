package app.recall.glasses.device

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import app.recall.glasses.R
import app.recall.glasses.core.Announcer

/**
 * Says "Recording started" and "Recording finished" out loud, so people nearby know. These are
 * bundled clips because the M6 has no text-to-speech engine.
 */
class ClipAnnouncer(context: Context, private val handler: Handler) : Announcer {
    private val appContext = context.applicationContext
    private val vibrator = appContext.getSystemService(Vibrator::class.java)
    private var player: MediaPlayer? = null

    override fun started() = announce(R.raw.recording_started)

    override fun finished() = announce(R.raw.recording_finished)

    fun shutdown() {
        handler.removeCallbacksAndMessages(TOKEN)
        release()
    }

    private fun announce(clip: Int) {
        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(VIBRATE_MS, 200)) }
        handler.removeCallbacksAndMessages(TOKEN)
        // The clip waits for the beep to finish, so the two do not talk over each other.
        handler.postAtTime({ play(clip) }, TOKEN, android.os.SystemClock.uptimeMillis() + AFTER_BEEP_MS)
    }

    private fun play(clip: Int) {
        release()
        try {
            val next = MediaPlayer()
            player = next
            next.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            appContext.resources.openRawResourceFd(clip).use {
                next.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            next.setOnCompletionListener { if (player === it) release() }
            next.prepare()
            next.start()
        } catch (e: Exception) {
            Log.w("RecallCues", "Could not play the recording notice", e)
            release()
        }
    }

    private fun release() {
        player?.release()
        player = null
    }

    private companion object {
        val TOKEN = Any()
        const val VIBRATE_MS = 150L
        const val AFTER_BEEP_MS = 400L
    }
}
