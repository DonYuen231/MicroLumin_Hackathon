package app.recall.phone.net

import app.recall.phone.core.AppJson
import app.recall.phone.core.BuildProfileRequest
import app.recall.phone.core.EmbedResponse
import app.recall.phone.core.ProblemDetails
import app.recall.phone.core.Profile
import app.recall.phone.core.RefreshRequest
import app.recall.phone.core.RefreshResponse
import app.recall.phone.core.Transcript
import app.recall.phone.data.AppSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.cio.readChannel
import java.io.File
import java.io.IOException
import java.net.URI

/** [title] is the backend's problem title, such as no_face. Null means it could not be reached. */
class BackendException(val title: String?, message: String) : Exception(message)

class BackendClient(private val settings: suspend () -> AppSettings) {
    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(AppJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 95_000
            requestTimeoutMillis = 95_000 // Cloudflare drops requests at 100 s.
        }
    }

    suspend fun health(): Boolean = call {
        val (url, _) = endpoint()
        http.get("$url/healthz").status.isSuccess()
    }

    suspend fun embed(jpeg: ByteArray): EmbedResponse = call {
        val (url, token) = endpoint()
        val response = http.submitFormWithBinaryData(
            url = "$url/v1/faces/embed",
            formData = formData {
                append("image", jpeg, fileHeaders("photo.jpg", "image/jpeg"))
            },
        ) { bearerAuth(token) }
        response.parsed()
    }

    suspend fun transcribe(audio: File): Transcript = call {
        val (url, token) = endpoint()
        val response = http.submitFormWithBinaryData(
            url = "$url/v1/audio/transcribe",
            formData = formData {
                append(
                    "audio",
                    ChannelProvider(audio.length()) { audio.readChannel() },
                    fileHeaders("audio.m4a", "audio/mp4"),
                )
            },
        ) { bearerAuth(token) }
        response.parsed()
    }

    suspend fun buildProfile(request: BuildProfileRequest): Profile = call {
        val (url, token) = endpoint()
        http.post("$url/v1/profiles/build") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.parsed()
    }

    suspend fun refresh(request: RefreshRequest): RefreshResponse = call {
        val (url, token) = endpoint()
        http.post("$url/v1/profiles/refresh") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.parsed()
    }

    private suspend fun endpoint(): Pair<String, String> {
        val current = settings()
        if (current.backendUrl.isBlank() || current.appToken.isBlank()) {
            throw BackendException("not_configured", "Add the backend URL and token in Settings.")
        }
        if (!isSafeBackendUrl(current.backendUrl)) {
            throw BackendException("insecure_url", "The backend URL must use https.")
        }
        return current.backendUrl.trimEnd('/') to current.appToken
    }

    private suspend inline fun <reified T> HttpResponse.parsed(): T {
        if (status.isSuccess()) return body()
        val problem = runCatching {
            AppJson.decodeFromString(ProblemDetails.serializer(), bodyAsText())
        }.getOrNull()
        throw BackendException(
            problem?.title?.ifBlank { null } ?: "http_${status.value}",
            problem?.detail?.ifBlank { null } ?: "The backend returned ${status.value}.",
        )
    }

    private suspend inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw BackendException(null, "The backend could not be reached.")
    }

    private fun fileHeaders(name: String, type: String) = Headers.build {
        append(HttpHeaders.ContentType, type)
        append(HttpHeaders.ContentDisposition, "filename=\"$name\"")
    }

    companion object {
        /** The token must never travel over plain HTTP, except to this device for testing. */
        fun isSafeBackendUrl(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return when (uri.scheme?.lowercase()) {
                "https" -> !uri.host.isNullOrBlank()
                "http" -> uri.host == "127.0.0.1" || uri.host == "localhost"
                else -> false
            }
        }
    }
}
