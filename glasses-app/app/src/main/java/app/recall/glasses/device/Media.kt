package app.recall.glasses.device

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.recall.glasses.core.AudioSource
import app.recall.glasses.core.Cues
import app.recall.glasses.core.Speaker
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

class RecorderAudioSource : AudioSource {
    private var recorder: MediaRecorder? = null

    @Suppress("DEPRECATION")
    override fun start(file: File) {
        val next = MediaRecorder()
        try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioChannels(1)
            next.setAudioSamplingRate(16_000)
            next.setAudioEncodingBitRate(48_000)
            next.setOutputFile(file.absolutePath)
            next.prepare()
            next.start()
            recorder = next
        } catch (e: Exception) {
            next.release()
            throw e
        }
    }

    override fun stop(): Boolean {
        val current = recorder ?: return false
        recorder = null
        return try {
            current.stop()
            true
        } catch (e: RuntimeException) {
            // MediaRecorder throws here when it captured no audio at all.
            Log.w("RecallAudio", "Recording produced no audio", e)
            false
        } finally {
            current.release()
        }
    }
}

class AndroidSpeaker(context: Context, private val onReady: () -> Unit) : Speaker {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session = 0
    private var callback: (Int) -> Unit = {}

    @Volatile
    override var available = false
        private set

    private val tts = TextToSpeech(context.applicationContext) { status ->
        mainHandler.post {
            available = status == TextToSpeech.SUCCESS
            if (available) attachListener()
            onReady()
        }
    }

    private fun attachListener() {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                val parts = utteranceId.split(':')
                val id = parts.getOrNull(0)?.toIntOrNull() ?: return
                val index = parts.getOrNull(1)?.toIntOrNull() ?: return
                mainHandler.post { if (id == session) callback(index) }
            }

            override fun onDone(utteranceId: String) = Unit

            @Deprecated("Required by the abstract class")
            override fun onError(utteranceId: String) = Unit
        })
    }

    override fun say(items: List<Pair<Int, String>>, onItem: (Int) -> Unit) {
        if (!available) return
        session++
        callback = onItem
        tts.stop()
        items.forEach { (index, text) ->
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, "$session:$index")
        }
    }

    override fun stop() {
        session++
        if (available) tts.stop()
    }

    override fun shutdown() {
        stop()
        tts.shutdown()
    }
}

class ToneCues : Cues {
    private val executor = Executors.newSingleThreadExecutor()

    override fun recordStart() = play(Tone(880, 880, 140))

    override fun recordStop() = play(Tone(880, 880, 110), Tone(0, 0, 80), Tone(880, 880, 110))

    override fun match() = play(Tone(520, 1040, 260))

    override fun noMatch() = play(Tone(220, 180, 320))

    private data class Tone(val startHz: Int, val endHz: Int, val ms: Int)

    private fun play(vararg tones: Tone) {
        executor.execute {
            runCatching { render(tones.toList()) }
                .onFailure { Log.w("RecallCues", "Could not play a cue", it) }
        }
    }

    private fun render(tones: List<Tone>) {
        val samples = ShortArray(tones.sumOf { it.ms } * SAMPLE_RATE / 1000)
        var position = 0
        var phase = 0.0
        for (tone in tones) {
            val length = tone.ms * SAMPLE_RATE / 1000
            for (i in 0 until length) {
                val progress = i.toDouble() / length
                val hz = tone.startHz + (tone.endHz - tone.startHz) * progress
                phase += 2 * PI * hz / SAMPLE_RATE
                val fade = min(1.0, min(i, length - i) / (SAMPLE_RATE * 0.005))
                samples[position++] = if (hz == 0.0) 0 else (sin(phase) * fade * 20_000).toInt().toShort()
            }
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(samples, 0, samples.size)
            track.play()
            Thread.sleep(tones.sumOf { it.ms }.toLong() + 40)
            track.stop()
        } finally {
            track.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 22_050
    }
}
