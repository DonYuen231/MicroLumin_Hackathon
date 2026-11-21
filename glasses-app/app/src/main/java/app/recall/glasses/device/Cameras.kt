package app.recall.glasses.device

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ExifInterface
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import app.recall.glasses.core.CameraSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

private const val TAG = "RecallCamera"
private const val MAX_LONG_SIDE = 1280
private const val MAX_JPEG_BYTES = 1_000_000
private const val SETTLE_MS = 700L
private const val BETWEEN_SHOTS_MS = 250L
private const val TIMEOUT_S = 6L

/** Tries Camera2 and falls back to Camera1, because the M6's camera support is unconfirmed. */
class FallbackCameraSource(
    private val primary: CameraSource,
    private val backup: CameraSource,
) : CameraSource {
    private var usePrimary = true

    override fun takeStills(count: Int): List<ByteArray> {
        if (usePrimary) {
            try {
                return primary.takeStills(count)
            } catch (e: Exception) {
                Log.w(TAG, "Camera2 failed, switching to Camera1", e)
                usePrimary = false
            }
        }
        return backup.takeStills(count)
    }
}

@SuppressLint("MissingPermission")
class Camera2Source(context: Context) : CameraSource {
    private val manager = context.getSystemService(CameraManager::class.java)

    override fun takeStills(count: Int): List<ByteArray> {
        val cameraId = chooseCamera()
        val traits = manager.getCameraCharacteristics(cameraId)
        val map = checkNotNull(traits.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val size = chooseSize(map.getOutputSizes(ImageFormat.JPEG).orEmpty().toList())
        val rotation = traits.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val previewSize = map.getOutputSizes(SurfaceTexture::class.java).orEmpty()
            .minByOrNull { it.width * it.height } ?: Size(640, 480)

        val thread = HandlerThread("recall-camera").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, count + 1)
        val texture = SurfaceTexture(10).apply {
            setDefaultBufferSize(previewSize.width, previewSize.height)
        }
        val preview = Surface(texture)
        var device: CameraDevice? = null
        try {
            val shots = LinkedBlockingQueue<ByteArray>()
            reader.setOnImageAvailableListener({ source ->
                source.acquireNextImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    shots.add(ByteArray(buffer.remaining()).also { buffer.get(it) })
                }
            }, handler)

            val camera = openCamera(cameraId, handler).also { device = it }
            val session = openSession(camera, listOf(preview, reader.surface), handler)
            val repeating = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
            }.build()
            session.setRepeatingRequest(repeating, null, handler)
            Thread.sleep(SETTLE_MS) // Lets auto exposure and focus settle.

            val still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_ORIENTATION, rotation)
                set(CaptureRequest.JPEG_QUALITY, 90.toByte())
            }.build()

            return List(count) { index ->
                if (index > 0) Thread.sleep(BETWEEN_SHOTS_MS)
                session.capture(still, null, handler)
                val jpeg = checkNotNull(shots.poll(TIMEOUT_S, TimeUnit.SECONDS)) { "Camera timed out" }
                normalizeJpeg(jpeg)
            }
        } finally {
            runCatching { device?.close() }
            reader.close()
            preview.release()
            texture.release()
            thread.quitSafely()
        }
    }

    private fun chooseCamera(): String {
        val ids = manager.cameraIdList
        check(ids.isNotEmpty()) { "No camera" }
        return ids.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.first()
    }

    private fun chooseSize(sizes: List<Size>): Size {
        check(sizes.isNotEmpty()) { "No JPEG sizes" }
        return sizes.filter { max(it.width, it.height) >= MAX_LONG_SIDE }
            .minByOrNull { it.width * it.height }
            ?: sizes.maxBy { it.width * it.height }
    }

    private fun openCamera(id: String, handler: Handler): CameraDevice {
        val result = CompletableFuture<CameraDevice>()
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (!result.complete(camera)) camera.close()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                result.completeExceptionally(IllegalStateException("Camera disconnected"))
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                result.completeExceptionally(IllegalStateException("Camera error $error"))
            }
        }, handler)
        return result.get(TIMEOUT_S, TimeUnit.SECONDS)
    }

    @Suppress("DEPRECATION")
    private fun openSession(
        camera: CameraDevice,
        surfaces: List<Surface>,
        handler: Handler,
    ): CameraCaptureSession {
        val result = CompletableFuture<CameraCaptureSession>()
        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                result.complete(session)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                result.completeExceptionally(IllegalStateException("Camera setup failed"))
            }
        }, handler)
        return result.get(TIMEOUT_S, TimeUnit.SECONDS)
    }
}

@Suppress("DEPRECATION")
class Camera1Source : CameraSource {
    override fun takeStills(count: Int): List<ByteArray> {
        val info = Camera.CameraInfo()
        val cameraId = (0 until Camera.getNumberOfCameras()).firstOrNull {
            Camera.getCameraInfo(it, info)
            info.facing == Camera.CameraInfo.CAMERA_FACING_BACK
        } ?: 0
        Camera.getCameraInfo(cameraId, info)

        val texture = SurfaceTexture(11)
        val camera = Camera.open(cameraId)
        try {
            camera.parameters = camera.parameters.apply {
                val sizes = supportedPictureSizes
                val best = sizes.filter { max(it.width, it.height) >= MAX_LONG_SIDE }
                    .minByOrNull { it.width * it.height }
                    ?: sizes.maxBy { it.width * it.height }
                setPictureSize(best.width, best.height)
                pictureFormat = ImageFormat.JPEG
                jpegQuality = 90
                setRotation(info.orientation)
            }
            camera.setPreviewTexture(texture)
            camera.startPreview()
            Thread.sleep(SETTLE_MS)
            return List(count) { index ->
                if (index > 0) {
                    camera.startPreview()
                    Thread.sleep(BETWEEN_SHOTS_MS)
                }
                val shot = CompletableFuture<ByteArray>()
                camera.takePicture(null, null) { data, _ -> shot.complete(data) }
                normalizeJpeg(shot.get(TIMEOUT_S, TimeUnit.SECONDS))
            }
        } finally {
            runCatching { camera.stopPreview() }
            camera.release()
            texture.release()
        }
    }
}

/** Applies any EXIF rotation and enforces the contract: 1280 px long side, under 1 MB. */
fun normalizeJpeg(jpeg: ByteArray): ByteArray {
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(jpeg))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    val longSide = max(bounds.outWidth, bounds.outHeight)
    check(longSide > 0) { "Unreadable JPEG" }
    if (degrees == 0f && longSide <= MAX_LONG_SIDE && jpeg.size < MAX_JPEG_BYTES) return jpeg

    var sample = 1
    while (longSide / (sample * 2) >= MAX_LONG_SIDE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options))
    val scale = minOf(1f, MAX_LONG_SIDE.toFloat() / max(decoded.width, decoded.height))
    val matrix = Matrix().apply {
        postScale(scale, scale)
        postRotate(degrees)
    }
    val output = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    try {
        var quality = 85
        while (true) {
            val bytes = ByteArrayOutputStream().use {
                output.compress(Bitmap.CompressFormat.JPEG, quality, it)
                it.toByteArray()
            }
            if (bytes.size < MAX_JPEG_BYTES || quality <= 40) return bytes
            quality -= 15
        }
    } finally {
        if (output !== decoded) output.recycle()
        decoded.recycle()
    }
}
