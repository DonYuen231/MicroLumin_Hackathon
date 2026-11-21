package app.recall.glasses.device

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.util.Log
import android.view.TextureView
import app.recall.glasses.core.CameraSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Shows the live camera behind the Recall screen, so a screen recording looks like the wearer's
 * view. Only one app user can hold the camera, so face photos come from this same preview.
 * This runs the camera continuously and heats the M6, so it is only for recording a demo.
 */
@Suppress("DEPRECATION")
class DemoCamera(
    private val view: TextureView,
    private val main: Handler,
) : CameraSource, TextureView.SurfaceTextureListener {
    private var camera: Camera? = null
    private var wanted = false

    @Volatile
    private var stillWidth = 1280

    @Volatile
    private var stillHeight = 960

    init {
        view.surfaceTextureListener = this
    }

    fun start() {
        wanted = true
        view.surfaceTexture?.let(::open)
    }

    fun stop() {
        wanted = false
        close()
    }

    override fun takeStills(count: Int): List<ByteArray> = List(count) { index ->
        if (index > 0) Thread.sleep(BETWEEN_SHOTS_MS)
        val frame = CompletableFuture<Bitmap?>()
        main.post { frame.complete(view.getBitmap(stillWidth, stillHeight)) }
        val bitmap = checkNotNull(frame.get(FRAME_TIMEOUT_S, TimeUnit.SECONDS)) { "No camera frame" }
        try {
            ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun open(texture: SurfaceTexture) {
        if (!wanted || camera != null) return
        try {
            val next = Camera.open()
            next.parameters = next.parameters.apply {
                val size = supportedPreviewSizes
                    .filter { it.width * 3 == it.height * 4 && it.width <= MAX_WIDTH }
                    .maxByOrNull { it.width }
                    ?: supportedPreviewSizes.first()
                setPreviewSize(size.width, size.height)
                stillWidth = size.width
                stillHeight = size.height
                if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO in supportedFocusModes) {
                    focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                }
            }
            next.setPreviewTexture(texture)
            next.startPreview()
            camera = next
        } catch (e: Exception) {
            Log.e(TAG, "Could not start the demo camera", e)
        }
    }

    private fun close() {
        val current = camera ?: return
        camera = null
        runCatching { current.stopPreview() }
        current.release()
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) =
        open(texture)

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        close()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    private companion object {
        const val TAG = "RecallDemo"
        const val MAX_WIDTH = 1280
        const val JPEG_QUALITY = 90
        const val BETWEEN_SHOTS_MS = 250L
        const val FRAME_TIMEOUT_S = 3L
    }
}
