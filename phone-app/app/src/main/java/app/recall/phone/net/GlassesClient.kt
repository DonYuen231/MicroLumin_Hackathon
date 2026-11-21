package app.recall.phone.net

import app.recall.phone.core.AppJson
import app.recall.phone.core.CaptureList
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.copyAndClose
import java.io.File
import java.io.IOException
import java.net.InetAddress

class GlassesException(message: String) : Exception(message)

class GlassesClient(private val address: suspend () -> String) {
    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 30_000
            requestTimeoutMillis = 120_000
        }
    }

    suspend fun list(): CaptureList = call {
        val response = http.get("${base()}/captures")
        if (!response.status.isSuccess()) fail("list captures", response.status.value)
        AppJson.decodeFromString(CaptureList.serializer(), response.bodyAsText())
    }

    suspend fun downloadPhoto(captureId: String, index: Int, dest: File) =
        download("${base()}/captures/${safeId(captureId)}/photos/$index", dest)

    suspend fun downloadAudio(captureId: String, dest: File) =
        download("${base()}/captures/${safeId(captureId)}/audio", dest)

    suspend fun delete(captureId: String) = call {
        val response = http.delete("${base()}/captures/${safeId(captureId)}")
        if (!response.status.isSuccess()) fail("delete capture", response.status.value)
    }

    private suspend fun download(url: String, dest: File) = call {
        http.prepareGet(url).execute { response ->
            if (!response.status.isSuccess()) fail("download file", response.status.value)
            response.bodyAsChannel().copyAndClose(dest.writeChannel())
        }
        Unit
    }

    private suspend fun base(): String {
        val value = address().trim()
        if (value.isBlank()) throw GlassesException("Add the glasses address in Settings.")
        if (!isPrivateAddress(value)) {
            throw GlassesException("The glasses address must be a private IP address.")
        }
        return if (':' in value) "http://$value" else "http://$value:$GLASSES_PORT"
    }

    private fun safeId(id: String): String {
        if (!SAFE_ID.matches(id)) throw GlassesException("The glasses sent an invalid capture ID.")
        return id
    }

    private fun fail(action: String, status: Int): Nothing =
        throw GlassesException("Could not $action on the glasses ($status).")

    private suspend inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw GlassesException("The glasses could not be reached.")
    }

    companion object {
        const val GLASSES_PORT = 8081
        val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        fun isPrivateAddress(value: String): Boolean {
            val host = value.substringBefore(':')
            if (!IPV4.matches(host) || host.split('.').any { it.toInt() > 255 }) return false
            val ip = InetAddress.getByName(host) // An IP literal, so this makes no DNS lookup.
            return ip.isSiteLocalAddress || ip.isLoopbackAddress
        }
    }
}
