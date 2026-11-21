package app.recall.glasses.device

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Handler
import android.util.Log
import app.recall.glasses.core.SpeechGate
import app.recall.glasses.core.VoiceEvents
import app.recall.glasses.core.VoiceMic
import java.io.File
import java.nio.ByteOrder
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

private const val TAG = "RecallVoice"
private const val SAMPLE_RATE = 16_000
private const val FRAME_SAMPLES = 1_600 // 100 ms
private const val SPEECH_EVENT_MS = 500L
private const val STABLE_FRAMES = 2
private const val CARRY_MS = 3_000L

/**
 * Owns the microphone while the app is open. Android gives the mic to one recorder at a time,
 * so the same audio feeds the keyword listener and, only during a recording, the AAC file.
 * Audio heard while waiting for a greeting is never stored.
 */
class VoskMic(
    private val modelDir: File,
    phrases: List<String>,
    private val main: Handler,
    private val onModelLoaded: () -> Unit,
) : VoiceMic {
    private val grammar = JSONArray(phrases + "[unk]").toString()
    private val writerLock = Any()
    private var writer: AacFileWriter? = null
    private var thread: Thread? = null

    @Volatile
    private var model: Model? = null

    @Volatile
    private var running = false

    @Volatile
    private var clearRequested = false

    override val ready: Boolean get() = model != null
    override val listening: Boolean get() = running

    fun load() {
        Thread({
            try {
                if (File(modelDir, "am/final.mdl").isFile) {
                    model = Model(modelDir.absolutePath)
                    Log.i(TAG, "Speech model loaded")
                } else {
                    Log.w(TAG, "Speech model is missing at $modelDir")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not load the speech model", e)
            }
            main.post(onModelLoaded)
        }, "recall-model").start()
    }

    override fun startListening(events: VoiceEvents) {
        val loaded = model ?: return
        if (running) return
        running = true
        thread = Thread({ listen(loaded, events) }, "recall-mic").apply { start() }
    }

    override fun stopListening() {
        running = false
        thread?.join(2_000)
        thread = null
    }

    override fun clearHeard() {
        clearRequested = true
    }

    override fun startFile(file: File) {
        check(running) { "The microphone is not running" }
        synchronized(writerLock) {
            writer?.close()
            writer = AacFileWriter(file)
        }
    }

    override fun stopFile(): Boolean = synchronized(writerLock) {
        val current = writer ?: return false
        writer = null
        current.close()
    }

    fun shutdown() {
        stopListening()
        model?.close()
        model = null
    }

    private fun listen(loaded: Model, events: VoiceEvents) {
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        var record: AudioRecord? = null
        var recognizer: Recognizer? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, FRAME_SAMPLES * 8),
            )
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            recognizer = Recognizer(loaded, SAMPLE_RATE.toFloat(), grammar)
            record.startRecording()
            pump(record, recognizer, events)
        } catch (e: Exception) {
            Log.e(TAG, "Listening stopped", e)
        } finally {
            running = false
            synchronized(writerLock) {
                writer?.close()
                writer = null
            }
            runCatching { record?.stop() }
            record?.release()
            recognizer?.close()
        }
    }

    private fun pump(record: AudioRecord, recognizer: Recognizer, events: VoiceEvents) {
        val buffer = ShortArray(FRAME_SAMPLES)
        val gate = SpeechGate()
        var lastText = ""
        var candidate = ""
        var stableFrames = 0
        var carry = ""
        var carryAtMs = 0L
        var lastSpeechEvent = 0L
        while (running) {
            val count = record.read(buffer, 0, buffer.size)
            check(count >= 0) { "Microphone read failed: $count" }
            if (count == 0) continue
            synchronized(writerLock) { writer?.write(buffer, count) }

            if (clearRequested) {
                clearRequested = false
                recognizer.reset()
                lastText = ""
                candidate = ""
                stableFrames = 0
                carry = ""
            }
            if (gate.isSpeech(level(buffer, count))) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastSpeechEvent >= SPEECH_EVENT_MS) {
                    lastSpeechEvent = nowMs
                    main.post { events.onSpeech() }
                }
            }

            val finished = recognizer.acceptWaveForm(buffer, count)
            val words = if (finished) {
                JSONObject(recognizer.result).optString("text")
            } else {
                JSONObject(recognizer.partialResult).optString("partial")
            }
            // A pause at the comma can split a phrase, so the last finished part carries over.
            val clockMs = System.currentTimeMillis()
            val carried = carry.takeIf { words.isNotBlank() && clockMs - carryAtMs <= CARRY_MS }
            val text = listOfNotNull(carried, words.ifBlank { null }).joinToString(" ")
            // A partial guess flickers in noise, so it must hold steady before it counts.
            if (text == candidate) {
                stableFrames++
            } else {
                candidate = text
                stableFrames = 0
            }
            val settled = finished || stableFrames >= STABLE_FRAMES
            if (settled && text.isNotBlank() && text != lastText) {
                Log.d(TAG, "heard: $text")
                lastText = text
                main.post { events.onPhrase(text) }
            }
            if (finished) {
                if (words.isNotBlank()) {
                    carry = words
                    carryAtMs = clockMs
                }
                lastText = ""
                candidate = ""
                stableFrames = 0
            }
        }
    }

    private fun level(samples: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / count)
    }
}

/** Writes mono 16 kHz AAC at 48 kbps, the format the backend already accepts. */
private class AacFileWriter(file: File) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxing = false
    private var wroteAudio = false
    private var samples = 0L
    private var closed = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1)
            .apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 48_000)
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                )
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SAMPLES * 2)
            }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun write(pcm: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val index = codec.dequeueInputBuffer(INPUT_WAIT_US)
            if (index < 0) {
                drain(false)
                continue
            }
            val input = checkNotNull(codec.getInputBuffer(index))
            input.clear()
            val part = minOf(count - offset, input.remaining() / 2)
            input.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, offset, part)
            codec.queueInputBuffer(index, 0, part * 2, timestampUs(), 0)
            samples += part
            offset += part
            drain(false)
        }
    }

    /** Returns true when the file holds playable audio. */
    fun close(): Boolean {
        if (closed) return wroteAudio
        closed = true
        try {
            val index = codec.dequeueInputBuffer(CLOSE_WAIT_US)
            if (index >= 0) {
                codec.queueInputBuffer(
                    index,
                    0,
                    0,
                    timestampUs(),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                drain(true)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not finish the audio file", e)
        }
        runCatching { codec.stop() }
        codec.release()
        val saved = wroteAudio && runCatching {
            if (muxing) muxer.stop()
            muxing
        }.getOrDefault(false)
        runCatching { muxer.release() }
        wroteAudio = saved
        return saved
    }

    private fun drain(untilEnd: Boolean) {
        val deadline = System.nanoTime() + CLOSE_DRAIN_NS
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) CLOSE_WAIT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEnd || System.nanoTime() > deadline) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxing) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                }
                index >= 0 -> {
                    val output = checkNotNull(codec.getOutputBuffer(index))
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (muxing && info.size > 0 && !isConfig) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        muxer.writeSampleData(track, output, info)
                        wroteAudio = true
                    }
                    val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (ended) return
                }
            }
        }
    }

    private fun timestampUs() = samples * 1_000_000 / SAMPLE_RATE

    private companion object {
        const val INPUT_WAIT_US = 10_000L
        const val CLOSE_WAIT_US = 20_000L
        const val CLOSE_DRAIN_NS = 2_000_000_000L
    }
}
