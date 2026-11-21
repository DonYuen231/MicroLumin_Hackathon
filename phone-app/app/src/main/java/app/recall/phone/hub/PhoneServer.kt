package app.recall.phone.hub

import app.recall.phone.core.AppJson
import app.recall.phone.core.RecognizeResponse
import app.recall.phone.data.SettingsStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.flow.MutableStateFlow

/** Live hub status for the Settings screen. */
object HubStatus {
    val running = MutableStateFlow(false)
    val blockedAddress = MutableStateFlow<String?>(null)
}

/** The server the glasses call on port 8080. Only the trusted glasses address may use it. */
class PhoneServer(
    private val recognizer: Recognizer,
    private val settings: SettingsStore,
) {
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            install(
                createApplicationPlugin("TrustedClient") {
                    onCall { call ->
                        val client = normalizeIp(call.request.origin.remoteAddress)
                        if (!isTrusted(client)) {
                            HubStatus.blockedAddress.value = client
                            call.respondText(
                                """{"ok":false,"reason":"untrusted_device"}""",
                                ContentType.Application.Json,
                                HttpStatusCode.Forbidden,
                            )
                        }
                    }
                },
            )
            routing {
                get("/health") { call.respondJson("""{"ok":true}""") }
                post("/recognize") { handleRecognize(call) }
            }
        }.also { it.start(wait = false) }
        HubStatus.running.value = true
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 300, timeoutMillis = 1_000)
        server = null
        HubStatus.running.value = false
    }

    private suspend fun handleRecognize(call: ApplicationCall) {
        val image = try {
            readImage(call)
        } catch (_: Exception) {
            null
        }
        if (image == null || image.isEmpty()) {
            call.respondJson(
                """{"match":false,"reason":"invalid_image"}""",
                HttpStatusCode.BadRequest,
            )
            return
        }
        val result = recognizer.recognize(image)
        call.respondJson(AppJson.encodeToString(RecognizeResponse.serializer(), result))
    }

    private suspend fun readImage(call: ApplicationCall): ByteArray? {
        var image: ByteArray? = null
        call.receiveMultipart(formFieldLimit = MAX_IMAGE_BYTES).forEachPart { part ->
            if (part is PartData.FileItem && part.name == "image") {
                image = part.provider().toByteArray()
            }
            part.dispose()
        }
        return image
    }

    private suspend fun ApplicationCall.respondJson(
        text: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respondText(text, ContentType.Application.Json, status)

    private suspend fun isTrusted(client: String): Boolean {
        if (client == "127.0.0.1" || client == "::1") return true
        val trusted = settings.current().glassesAddress.substringBefore(':').trim()
        return trusted.isNotEmpty() && trusted == client
    }

    private fun normalizeIp(value: String): String = when {
        value.startsWith("::ffff:") -> value.removePrefix("::ffff:")
        value == "0:0:0:0:0:0:0:1" -> "::1"
        else -> value
    }

    companion object {
        const val PORT = 8080
        private const val MAX_IMAGE_BYTES = 2L * 1024 * 1024
    }
}
