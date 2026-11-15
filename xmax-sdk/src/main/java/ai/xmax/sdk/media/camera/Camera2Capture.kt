//
//  Camera2Capture.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.camera

import ai.xmax.sdk.CameraDevice as XmaxCameraDevice
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.MediaDevices
import ai.xmax.sdk.RealtimeCamera
import ai.xmax.sdk.RealtimeCameraRotation
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.cleanupAfterFailure
import ai.xmax.sdk.resolveFrameRotation
import ai.xmax.sdk.media.video.Yuv420VideoFrameConverter
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ln

/**
 * Captures Camera2 YUV images and converts them to orientation-corrected I420 frames.
 *
 * @property context The application context providing camera and display services.
 */
@Suppress("DEPRECATION")
internal class Camera2Capture(
    private val context: Context,
) : CameraCapture {

    /**
     * The system service used to enumerate and open camera devices.
     */
    private val manager = context.getSystemService(CameraManager::class.java)

    /**
     * The system service used to resolve the display orientation.
     */
    private val windowManager = context.getSystemService(WindowManager::class.java)

    /**
     * The currently open or opening camera.
     */
    private var active: Session? = null

    /**
     * Opens the selected lens and waits for the first converted frame.
     *
     * @param format The requested output format.
     * @param selection The camera position or explicit device identifier.
     * @param rotation The clockwise correction relative to automatic orientation.
     * @param onFrame The receiver of converted frames.
     * @param onError The receiver of capture errors after startup.
     * @return The camera that produced the first frame.
     */
    override suspend fun start(
        format: RealtimeVideoFormat,
        selection: RealtimeCamera,
        rotation: RealtimeCameraRotation,
        onFrame: (VideoFrame) -> Unit,
        onError: (XmaxError) -> Unit,
    ): XmaxCameraDevice {
        val configuration = configure(
            format,
            selection,
            rotation,
        )
        stop()

        val session = Session(configuration, format, rotation, onFrame, onError)
        active = session
        try {
            session.open()
            if (withTimeoutOrNull(5_000L) { session.firstFrame.await() } == null) {
                throw cameraError("Timed out waiting for the first camera frame")
            }
        } catch (error: Throwable) {
            cleanupAfterFailure(error, { stop() })
            throw error
        }
        return configuration.camera
    }

    /**
     * Updates rotation on the current camera thread without reopening the lens.
     *
     * @param rotation The clockwise correction relative to automatic orientation.
     * @param onApplied Notifies the media controller before the next frame is processed.
     */
    override suspend fun setRotation(
        rotation: RealtimeCameraRotation,
        onApplied: () -> Unit,
    ) {
        val session = active ?: throw cameraError("Camera capture is not active")
        session.setRotation(rotation, onApplied)
    }

    /**
     * Stops delivery and waits for the device to close so it can be reopened.
     */
    override suspend fun stop() {
        val session = active ?: return
        active = null

        withContext(NonCancellable) {
            session.close()
            if (withTimeoutOrNull(5_000L) { session.closed.await() } == null) {
                throw cameraError("Timed out closing the camera")
            }
        }
    }

    /**
     * Selects a camera and capture size that can produce the requested oriented output.
     *
     * @param format The target dimensions and frame rate.
     * @param selection The requested camera position or device identifier.
     * @param rotation The manual correction used when choosing the input size for an external camera.
     */
    private fun configure(
        format: RealtimeVideoFormat,
        selection: RealtimeCamera,
        rotation: RealtimeCameraRotation,
    ): ConfigurationForCamera {
        val (camera, properties) = resolveCamera(selection)
        val sensorOrientation = properties.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayRotation = windowManager.defaultDisplay.rotation
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val displayIsLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val wantsLandscape = format.width > format.height
        val targetDegrees = if (format.width != format.height && displayIsLandscape != wantsLandscape) {
            (displayDegrees + 90) % 360
        } else {
            displayDegrees
        }
        val relativeRotation = when (camera.position) {
            CameraPosition.FRONT -> (sensorOrientation + targetDegrees) % 360
            CameraPosition.BACK -> (sensorOrientation - targetDegrees + 360) % 360
            null -> sensorOrientation
        }
        val orientation = VideoRotation.entries.first { it.degrees == relativeRotation }
        val sizeRotation = if (camera.position == null) {
            (relativeRotation + rotation.degrees) % 360
        } else {
            relativeRotation
        }
        val swap = sizeRotation % 180 != 0
        val targetWidth = if (swap) format.height else format.width
        val targetHeight = if (swap) format.width else format.height

        val streams = checkNotNull(properties.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val sizes = streams.getOutputSizes(ImageFormat.YUV_420_888)
        val targetArea = targetWidth.toLong() * targetHeight
        val targetAspect = targetWidth.toDouble() / targetHeight
        val frameIntervalNs = 1_000_000_000L / format.fps
        val size = sizes.minBy { size ->
            val duration = streams.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size)
            val ratePenalty = if (duration > frameIntervalNs) 10.0 else 0.0
            val areaRatio = size.width.toLong() * size.height / targetArea.toDouble()
            val aspectRatio = size.width.toDouble() / size.height / targetAspect
            ratePenalty + abs(ln(aspectRatio)) * 2 + abs(ln(areaRatio))
        }
        val ranges = properties.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val fps = ranges.minWithOrNull(compareBy(
            { abs(it.upper - format.fps) },
            { abs(it.lower - format.fps) },
        ))
        return ConfigurationForCamera(
            camera = camera,
            size = size,
            fps = fps,
            orientation = orientation,
            mirrored = camera.position == CameraPosition.FRONT,
            usesBoottime = properties.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
        )
    }

    /**
     * Resolves a connected camera that can produce YUV frames.
     *
     * @param selection The position or Camera2 identifier used to find the device.
     */
    private fun resolveCamera(
        selection: RealtimeCamera,
    ): Pair<XmaxCameraDevice, CameraCharacteristics> {
        val candidates = when (selection) {
            is RealtimeCamera.Device -> manager.cameraIdList.filter { it == selection.id }
            is RealtimeCamera.Position -> manager.cameraIdList.asList()
        }
        for (id in candidates) {
            val properties = manager.getCameraCharacteristics(id)
            val camera = MediaDevices.describeCamera(id, properties)
            if (selection is RealtimeCamera.Position && camera.position != selection.position) continue
            val formats = properties.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (!formats?.getOutputSizes(ImageFormat.YUV_420_888).isNullOrEmpty()) return camera to properties
        }

        val label = when (selection) {
            is RealtimeCamera.Device -> "Camera ${selection.id}"
            is RealtimeCamera.Position -> "The ${selection.position.name.lowercase()} camera"
        }
        throw cameraError("$label is unavailable or does not support YUV capture")
    }

    /**
     * The camera characteristics selected for one capture session.
     *
     * @property camera The selected Android camera.
     * @property size The native YUV stream size.
     * @property fps The hardware exposure frame rate range, if advertised.
     * @property orientation The automatic rotation of the sensor frame.
     * @property mirrored Whether to mirror the oriented front-camera output.
     * @property usesBoottime Whether the sensor timestamps use the system boot-time clock.
     */
    private data class ConfigurationForCamera(
        val camera: XmaxCameraDevice,
        val size: Size,
        val fps: Range<Int>?,
        val orientation: VideoRotation,
        val mirrored: Boolean,
        val usesBoottime: Boolean,
    )

    /**
     * Owns the device, image reader, and callback thread for one open camera.
     *
     * @property configuration The device and sensor configuration.
     * @property format The SDK output format.
     * @param rotation The initial clockwise correction relative to automatic orientation.
     * @property onFrame The frame consumer on the camera thread.
     * @property onError The runtime error consumer on the camera thread.
     */
    private inner class Session(
        private val configuration: ConfigurationForCamera,
        private val format: RealtimeVideoFormat,
        rotation: RealtimeCameraRotation,
        private val onFrame: (VideoFrame) -> Unit,
        private val onError: (XmaxError) -> Unit,
    ) : CameraDevice.StateCallback() {

        /**
         * The worker serializing camera callbacks, frame conversion, and release.
         */
        private val thread = HandlerThread("XmaxSDK-Camera").apply { start() }

        /**
         * The callback handler for this device and image reader.
         */
        private val handler = Handler(thread.looper)

        /**
         * The current sensor-frame rotation, read and updated on the capture thread.
         */
        private var frameRotation = rotation.resolveFrameRotation(
            configuration.orientation,
            configuration.mirrored,
        )

        /**
         * Completion of the first valid frame or the startup failure.
         */
        val firstFrame = CompletableDeferred<Unit>()

        /**
         * Completion of the device's close callback.
         */
        val closed = CompletableDeferred<Unit>()

        /**
         * Whether delivery has been stopped or a capture failure has been reported.
         */
        private val closing = AtomicBoolean(false)

        /**
         * The device owned by this session, accessed on the callback thread.
         */
        private var device: CameraDevice? = null

        /**
         * The configured repeating capture, accessed on the callback thread.
         */
        private var capture: CameraCaptureSession? = null

        /**
         * The reader holding at most two outstanding YUV images.
         */
        private var reader: ImageReader? = null

        /**
         * Whether camera open was accepted and a device callback is still expected.
         */
        private var opening = false

        /**
         * The timestamp of the last submitted frame, in nanoseconds.
         */
        private var lastTimestampNs = Long.MIN_VALUE

        /**
         * The next eligible frame time, in nanoseconds on the capture clock.
         */
        private var nextFrameNs = 0L

        /**
         * Opens the device after the public camera permission check.
         */
        @SuppressLint("MissingPermission")
        fun open() {
            handler.post {
                if (closing.get()) return@post

                try {
                    manager.openCamera(configuration.camera.id, this, handler)
                    opening = true
                } catch (error: Exception) {
                    fail("Could not open the camera", error)
                }
            }
        }

        /**
         * Configures output for the opened device on the capture thread.
         */
        override fun onOpened(
            camera: CameraDevice,
        ) {
            opening = false
            device = camera
            if (closing.get()) {
                camera.close()
                return
            }

            try {
                val imageReader = ImageReader.newInstance(
                    configuration.size.width,
                    configuration.size.height,
                    ImageFormat.YUV_420_888,
                    2,
                )
                reader = imageReader
                imageReader.setOnImageAvailableListener(::onImage, handler)
                camera.createCaptureSession(
                    listOf(imageReader.surface),
                    object : CameraCaptureSession.StateCallback() {

                        /**
                         * Starts the repeating capture request once the output is ready.
                         */
                        override fun onConfigured(
                            session: CameraCaptureSession,
                        ) {
                            if (closing.get()) {
                                session.close()
                                return
                            }

                            capture = session
                            try {
                                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    addTarget(imageReader.surface)
                                    configuration.fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                                }.build()
                                session.setRepeatingRequest(request, null, handler)
                            } catch (error: Exception) {
                                fail("Could not start camera capture", error)
                            }
                        }

                        /**
                         * Closes a session whose output could not be configured.
                         */
                        override fun onConfigureFailed(
                            session: CameraCaptureSession,
                        ) {
                            session.close()
                            fail("Could not configure camera capture")
                        }
                    },
                    handler,
                )
            } catch (error: Exception) {
                fail("Could not configure the camera", error)
            }
        }

        /**
         * Ends capture when Android disconnects the device.
         */
        override fun onDisconnected(
            camera: CameraDevice,
        ) {
            opening = false
            device = camera
            fail("Camera disconnected")
            camera.close()
        }

        /**
         * Reports a device error and closes its resources.
         */
        override fun onError(
            camera: CameraDevice,
            error: Int,
        ) {
            opening = false
            device = camera
            fail("Camera device error ($error)")
            camera.close()
        }

        /**
         * Confirms the device has released its hardware resources.
         */
        override fun onClosed(
            camera: CameraDevice,
        ) {
            finishClosing()
        }

        /**
         * Orders an orientation update between deliveries on this session's capture thread.
         *
         * @param rotation The clockwise correction relative to automatic orientation.
         * @param onApplied Runs before any subsequent frame is delivered.
         */
        suspend fun setRotation(
            rotation: RealtimeCameraRotation,
            onApplied: () -> Unit,
        ): Unit = suspendCancellableCoroutine { continuation ->
            val posted = handler.post {
                if (!continuation.isActive) return@post
                if (closing.get()) {
                    continuation.resumeWithException(cameraError("Camera capture has stopped"))
                    return@post
                }

                frameRotation = rotation.resolveFrameRotation(
                    configuration.orientation,
                    configuration.mirrored,
                )
                onApplied()
                continuation.resume(Unit)
            }
            if (!posted) continuation.resumeWithException(cameraError("Camera capture has stopped"))
        }

        /**
         * Reads the latest image, limits conversion to the requested frame rate, and releases the image.
         *
         * @param source The active YUV image reader.
         */
        private fun onImage(
            source: ImageReader,
        ) {
            if (closing.get()) return

            try {
                val frame = source.acquireLatestImage()?.use { image ->
                    val timestamp = if (configuration.usesBoottime) image.timestamp else SystemClock.elapsedRealtimeNanos()
                    if (timestamp <= lastTimestampNs) return
                    lastTimestampNs = timestamp

                    val interval = 1_000_000_000L / format.fps
                    // Keep the target cadence when the device can only capture at a higher rate.
                    if (timestamp + minOf(interval / 4, 2_000_000L) < nextFrameNs) return
                    nextFrameNs = if (nextFrameNs == 0L) timestamp + interval else maxOf(nextFrameNs + interval, timestamp)

                    Yuv420VideoFrameConverter.convert(
                        image = image,
                        outputWidth = format.width,
                        outputHeight = format.height,
                        rotation = frameRotation,
                        timestampUs = timestamp / 1_000L,
                        mirrored = configuration.mirrored,
                    )
                } ?: return

                if (!closing.get()) {
                    onFrame(frame)
                    firstFrame.complete(Unit)
                }
            } catch (error: Exception) {
                fail("Could not read a camera frame", error)
            }
        }

        /**
         * Completes startup with the error or reports a running capture failure, then closes the device.
         *
         * @param message The operation that failed.
         * @param cause The underlying Android failure, if available.
         */
        private fun fail(
            message: String,
            cause: Throwable? = null,
        ) {
            if (!closing.compareAndSet(false, true)) return

            val error = cameraError(message, cause)
            try {
                if (!firstFrame.completeExceptionally(error)) onError(error)
            } finally {
                release()
            }
        }

        /**
         * Rejects new frames immediately and queues the hardware release.
         */
        fun close() {
            if (closing.compareAndSet(false, true)) handler.post(::release)
        }

        /**
         * Closes the session and device on the capture thread.
         */
        private fun release() {
            capture?.close()
            capture = null
            val camera = device
            if (camera != null) {
                camera.close()
            } else if (!opening) {
                finishClosing()
            }
        }

        /**
         * Releases the reader after the device closes and stops the worker.
         */
        private fun finishClosing() {
            reader?.close()
            reader = null
            device = null
            thread.quitSafely()
            closed.complete(Unit)
        }
    }

    /**
     * Formats an Android camera failure for the media lifecycle.
     *
     * @param message The operation that failed.
     * @param cause The underlying Android failure, if available.
     */
    private fun cameraError(
        message: String,
        cause: Throwable? = null,
    ): XmaxError = XmaxError(XmaxErrorCode.MEDIA_ERROR, message, cause = cause)
}
