package app.recall.glasses.core

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serves finished captures to the phone on port 8081. These files are faces and private
 * conversations, so only addresses accepted by [isAllowed] can read or delete them.
 */
class CaptureServer(
    private val store: CaptureStore,
    port: Int = PORT,
    private val isAllowed: (String) -> Boolean,
    private val onChanged: () -> Unit = {},
) : NanoHTTPD(port) {

    fun startServer() = start(SOCKET_READ_TIMEOUT, true)

    override fun serve(session: IHTTPSession): Response {
        if (!isAllowed(session.remoteIpAddress)) {
            return json(Response.Status.FORBIDDEN, """{"ok":false,"reason":"untrusted_device"}""")
        }
        return try {
            route(session.method, session.uri)
        } catch (_: IOException) {
            json(Response.Status.INTERNAL_ERROR, """{"ok":false,"reason":"read_failed"}""")
        }
    }

    // NanoHTTPD's gzip wrapper adds nothing on a hotspot link and is one more thing to break.
    override fun useGzipWhenAccepted(r: Response): Boolean = false

    private fun route(method: Method, uri: String): Response {
        if (uri == "/captures" && method == Method.GET) return list()
        PHOTO.matchEntire(uri)?.let { match ->
            if (method != Method.GET) return notAllowed()
            val index = match.groupValues[2].toIntOrNull() ?: return notFound()
            return file(store.photo(match.groupValues[1], index), "image/jpeg")
        }
        AUDIO.matchEntire(uri)?.let { match ->
            if (method != Method.GET) return notAllowed()
            return file(store.audio(match.groupValues[1]), "audio/mp4")
        }
        CAPTURE.matchEntire(uri)?.let { match ->
            if (method != Method.DELETE) return notAllowed()
            if (!store.delete(match.groupValues[1])) return notFound()
            onChanged()
            return json(Response.Status.OK, """{"ok":true}""")
        }
        return notFound()
    }

    private fun list(): Response {
        val captures = JSONArray()
        store.list().forEach {
            captures.put(
                JSONObject()
                    .put("capture_id", it.id)
                    .put("started_at", it.startedAt)
                    .put("ended_at", it.endedAt)
                    .put("photos", it.photos)
                    .put("has_audio", it.hasAudio),
            )
        }
        return json(Response.Status.OK, JSONObject().put("captures", captures).toString())
    }

    private fun file(file: File?, mime: String): Response {
        if (file == null || !file.isFile) return notFound()
        return newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length())
    }

    private fun notFound() = json(Response.Status.NOT_FOUND, """{"ok":false,"reason":"not_found"}""")

    private fun notAllowed() =
        json(Response.Status.METHOD_NOT_ALLOWED, """{"ok":false,"reason":"method_not_allowed"}""")

    private fun json(status: Response.Status, body: String) =
        newFixedLengthResponse(status, "application/json", body)

    companion object {
        const val PORT = 8081
        private const val ID = "([A-Za-z0-9_-]{1,64})"
        private val CAPTURE = Regex("^/captures/$ID$")
        private val PHOTO = Regex("^/captures/$ID/photos/(\\d{1,3})$")
        private val AUDIO = Regex("^/captures/$ID/audio$")
    }
}
