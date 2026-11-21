package app.recall.glasses.device

import app.recall.glasses.core.PhoneApi
import app.recall.glasses.core.REASON_PHONE_UNREACHABLE
import app.recall.glasses.core.RecognizeResult
import app.recall.glasses.core.parseRecognize
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Calls the phone. [host] is `ip` or `ip:port`, or null when there is no Wi-Fi. */
class HttpPhoneClient(private val host: () -> String?) : PhoneApi {

    override fun recognize(jpeg: ByteArray): RecognizeResult {
        val target = host() ?: return RecognizeResult.Failure(REASON_PHONE_UNREACHABLE)
        val base = if (':' in target) "http://$target" else "http://$target:$PHONE_PORT"
        val boundary = "recall-" + UUID.randomUUID()
        val head = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"image\"; filename=\"photo.jpg\"\r\n" +
            "Content-Type: image/jpeg\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        val headBytes = head.toByteArray()
        val tailBytes = tail.toByteArray()

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$base/recognize").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setFixedLengthStreamingMode(headBytes.size + jpeg.size + tailBytes.size)
            }
            connection.outputStream.use {
                it.write(headBytes)
                it.write(jpeg)
                it.write(tailBytes)
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            parseRecognize(stream?.use { it.readBytes().decodeToString() }.orEmpty())
        } catch (_: IOException) {
            RecognizeResult.Failure(REASON_PHONE_UNREACHABLE)
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val PHONE_PORT = 8080
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
