package app.recall.glasses.device

import android.os.SystemClock
import android.util.Log
import app.recall.glasses.core.PhoneApi
import app.recall.glasses.core.REASON_PHONE_UNREACHABLE
import app.recall.glasses.core.RecognizeResult
import app.recall.glasses.core.parseRecognize
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID

/** Calls the phone. [host] is `ip` or `ip:port`, or null when there is no Wi-Fi. */
class HttpPhoneClient(private val host: () -> String?) : PhoneApi {

    override fun recognize(jpeg: ByteArray): RecognizeResult {
        val base = baseUrl() ?: return RecognizeResult.Failure(REASON_PHONE_UNREACHABLE)
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return sendImage(base, jpeg)
            } catch (_: SocketTimeoutException) {
                // Already waited the full timeout, so a retry would only keep the wearer waiting.
                Log.w(TAG, "recognize timed out on attempt $attempt")
                break
            } catch (e: IOException) {
                Log.w(TAG, "recognize failed on attempt $attempt: ${e.javaClass.simpleName}")
            }
        }
        return RecognizeResult.Failure(REASON_PHONE_UNREACHABLE)
    }

    private fun sendImage(base: String, jpeg: ByteArray): RecognizeResult {
        val boundary = "recall-" + UUID.randomUUID()
        val head = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"image\"; filename=\"photo.jpg\"\r\n" +
            "Content-Type: image/jpeg\r\n\r\n"
        val headBytes = head.toByteArray()
        val tailBytes = "\r\n--$boundary--\r\n".toByteArray()
        val startedMs = SystemClock.elapsedRealtime()
        val connection = open("$base/recognize", READ_TIMEOUT_MS).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setFixedLengthStreamingMode(headBytes.size + jpeg.size + tailBytes.size)
        }
        try {
            connection.outputStream.use {
                it.write(headBytes)
                it.write(jpeg)
                it.write(tailBytes)
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes().decodeToString() }.orEmpty()
            val elapsed = SystemClock.elapsedRealtime() - startedMs
            Log.i(TAG, "recognize status=$status in ${elapsed}ms")
            return parseRecognize(body)
        } finally {
            connection.disconnect()
        }
    }

    override fun captureReady() {
        val base = baseUrl() ?: return
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                val connection = open("$base/captures/ready", LINK_TIMEOUT_MS).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setFixedLengthStreamingMode(0)
                }
                try {
                    connection.outputStream.close()
                    val status = connection.responseCode
                    Log.i(TAG, "capture ready status=$status")
                    if (status in 200..299) return
                } finally {
                    connection.disconnect()
                }
            } catch (e: IOException) {
                Log.w(TAG, "capture ready failed on attempt $attempt: ${e.javaClass.simpleName}")
            }
        }
    }

    /** A quick check for the Home screen. A phone that has not trusted the glasses is false. */
    fun isReachable(): Boolean {
        val base = baseUrl() ?: return false
        return try {
            val connection = open("$base/health", LINK_TIMEOUT_MS)
            try {
                connection.responseCode == 200
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun baseUrl(): String? {
        val target = host() ?: return null
        return if (':' in target) "http://$target" else "http://$target:$PHONE_PORT"
    }

    /** Each request gets a new connection, because a reused one may already be closed. */
    private fun open(url: String, readTimeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            setRequestProperty("Connection", "close")
        }

    private companion object {
        const val TAG = "RecallPhone"
        const val MAX_ATTEMPTS = 2
        const val LINK_TIMEOUT_MS = 2_000
        const val PHONE_PORT = 8080
        const val CONNECT_TIMEOUT_MS = 3_000

        // Short, so a Wi-Fi hiccup costs one quick retry instead of the whole conversation.
        const val READ_TIMEOUT_MS = 12_000
    }
}
