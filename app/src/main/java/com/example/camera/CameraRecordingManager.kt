package com.example.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.util.Log
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExposureState
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.data.model.VideoResolution
import com.example.data.model.GpsTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraRecordingManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var previewUseCase: Preview? = null
    private var currentTargetRotation: Int = Surface.ROTATION_0
    private var liveHudEffect: OverlayEffect? = null

    /** Supplies the latest GPS values to the encoder-side HUD for every video frame. */
    var telemetryProvider: (() -> GpsTelemetry)? = null
    var speedUnitLabelProvider: (() -> String)? = null
    private var activeRecording: Recording? = null

    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    private var isTorchOn = false
    private var isNightMode = false

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSeconds = MutableStateFlow(0L)
    val recordingDurationSeconds: StateFlow<Long> = _recordingDurationSeconds.asStateFlow()

    private val _currentLens = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val currentLens: StateFlow<Int> = _currentLens.asStateFlow()

    private val _isNightBoostActive = MutableStateFlow(false)
    val isNightBoostActive: StateFlow<Boolean> = _isNightBoostActive.asStateFlow()

    private val _isTorchActive = MutableStateFlow(false)
    val isTorchActive: StateFlow<Boolean> = _isTorchActive.asStateFlow()

    private var currentVideoFile: File? = null
    private var recordingStartTime = 0L

    var onRecordingSaved: ((videoUri: String, displayPath: String, durationSeconds: Long) -> Unit)? = null
    var onRecordingError: ((String) -> Unit)? = null

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        resolution: VideoResolution = VideoResolution.RES_1080P
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                startCamera(lifecycleOwner, previewView, resolution)
            } catch (e: Exception) {
                Log.e("CameraRecordingManager", "Failed to get CameraProvider", e)
                onRecordingError?.invoke("Kamera tidak dapat dimulai: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        resolution: VideoResolution
    ) {
        val provider = cameraProvider ?: return

        try {
            provider.unbindAll()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(currentLensFacing)
                .build()

            val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
            currentTargetRotation = targetRotation

            val preview = Preview.Builder()
                .setTargetRotation(targetRotation)
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
            previewUseCase = preview

            // Quality selector matching user choice
            val quality = when (resolution) {
                VideoResolution.RES_4K -> Quality.UHD
                VideoResolution.RES_1080P -> Quality.FHD
                VideoResolution.RES_720P -> Quality.HD
            }

            val qualitySelector = QualitySelector.from(
                quality,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.Builder(recorder)
                .setTargetRotation(targetRotation)
                .build()

            // OverlayEffect runs inside CameraX's video pipeline, so these pixels are encoded
            // into the MP4 itself instead of being added to the Compose preview only.
            liveHudEffect?.close()
            val hudEffect = OverlayEffect(
                CameraEffect.VIDEO_CAPTURE,
                0,
                Handler(Looper.getMainLooper())
            ) { throwable ->
                Log.e("CameraRecordingManager", "Live HUD effect failed", throwable)
            }
            hudEffect.setOnDrawListener { frame ->
                val canvas = frame.overlayCanvas
                canvas.drawColor(AndroidColor.TRANSPARENT, PorterDuff.Mode.CLEAR)
                val telemetry = telemetryProvider?.invoke() ?: GpsTelemetry()
                // Use the exact transformation metadata attached to this output frame.
                // Sensor rotation can differ from the crop/rotation CameraX applies here.
                drawLiveHud(
                    canvas = canvas,
                    telemetry = telemetry,
                    unit = speedUnitLabelProvider?.invoke() ?: "km/h",
                    cropRect = frame.cropRect,
                    rotationDegrees = frame.rotationDegrees
                )
                true
            }
            liveHudEffect = hudEffect

            val useCaseGroupBuilder = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(videoCapture!!)
                .addEffect(hudEffect)

            // Share PreviewView's viewport with VideoCapture so portrait recordings
            // use the same crop/aspect framing as the on-screen camera preview.
            previewView.viewPort?.let { viewPort ->
                useCaseGroupBuilder.setViewPort(viewPort)
            }
            val useCaseGroup = useCaseGroupBuilder.build()

            activeCamera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                useCaseGroup
            )

            applyNightModeSettings()

        } catch (e: Exception) {
            Log.e("CameraRecordingManager", "Error binding camera lifecycle", e)
            onRecordingError?.invoke("Gagal mengaktifkan kamera: ${e.localizedMessage}")
        }
    }

    /**
     * Draws the same transparent cyan/neon HUD used by the camera screen directly onto
     * CameraX's VIDEO_CAPTURE overlay canvas. These pixels are therefore encoded in every
     * recorded frame and survive sharing/exporting the original MP4.
     */
    private fun drawLiveHud(
        canvas: Canvas,
        telemetry: GpsTelemetry,
        unit: String,
        cropRect: android.graphics.Rect,
        rotationDegrees: Int
    ) {
        val cropWidth = cropRect.width().toFloat()
        val cropHeight = cropRect.height().toFloat()
        if (cropWidth <= 0f || cropHeight <= 0f) return

        // Respect the crop and rotation of this exact CameraX output frame.
        val rotation = ((rotationDegrees % 360) + 360) % 360
        val rotated = rotation == 90 || rotation == 270
        val w = if (rotated) cropHeight else cropWidth
        val h = if (rotated) cropWidth else cropHeight
        val save = canvas.save()
        canvas.clipRect(cropRect)
        val transform = Matrix()
        when (rotation) {
            90 -> transform.setValues(floatArrayOf(0f, 1f, cropRect.left.toFloat(), -1f, 0f, cropRect.bottom.toFloat(), 0f, 0f, 1f))
            180 -> transform.setValues(floatArrayOf(-1f, 0f, cropRect.right.toFloat(), 0f, -1f, cropRect.bottom.toFloat(), 0f, 0f, 1f))
            270 -> transform.setValues(floatArrayOf(0f, -1f, cropRect.right.toFloat(), 1f, 0f, cropRect.top.toFloat(), 0f, 0f, 1f))
            else -> transform.setValues(floatArrayOf(1f, 0f, cropRect.left.toFloat(), 0f, 1f, cropRect.top.toFloat(), 0f, 0f, 1f))
        }
        canvas.concat(transform)

        val scale = (w / if (h > w) 720f else 1080f).coerceIn(0.85f, 2.6f)
        val cyan = AndroidColor.rgb(69, 244, 255)
        val green = AndroidColor.rgb(0, 245, 160)
        val red = AndroidColor.rgb(255, 48, 79)
        val white = AndroidColor.rgb(245, 251, 255)
        val muted = AndroidColor.rgb(175, 202, 215)
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(176, 4, 12, 20) }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cyan; style = Paint.Style.STROKE; strokeWidth = 1.5f * scale
        }
        fun drawText(value: String, x: Float, y: Float, size: Float, color: Int = white,
                     bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; textSize = size * scale; textAlign = align
                typeface = Typeface.create(Typeface.MONOSPACE,
                    if (bold) Typeface.BOLD else Typeface.NORMAL)
                if (bold) setShadowLayer(3.5f * scale, 0f, 0f, color)
            }
            canvas.drawText(value, x, y, p)
        }
        fun drawPanel(rect: RectF, radius: Float = 10f) {
            canvas.drawRoundRect(rect, radius * scale, radius * scale, panelPaint)
            canvas.drawRoundRect(rect, radius * scale, radius * scale, borderPaint)
        }

        val margin = 18f * scale
        val top = 12f * scale
        val headerBottom = top + 42f * scale
        drawPanel(RectF(margin, top, w - margin, headerBottom), 13f)

        // Header: recording status, video format, GPS state and live device battery.
        val battery = context.registerReceiver(null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra("level", -1) ?: -1
        val batteryScale = battery?.getIntExtra("scale", 100) ?: 100
        val batteryText = if (level >= 0 && batteryScale > 0) (level * 100 / batteryScale).toString() + "%" else "--"
        canvas.drawCircle(margin + 13f * scale, top + 21f * scale, 6f * scale,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red })
        drawText("REC", margin + 27f * scale, top + 26f * scale, 14f, red, true)
        drawText("1080p  60FPS  H.265", w * .5f, top + 26f * scale, 11f, white, true, Paint.Align.CENTER)
        val gpsColor = if (telemetry.isGpsFixed) green else red
        canvas.drawCircle(w - margin - 116f * scale, top + 21f * scale, 5f * scale,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gpsColor })
        drawText("GPS", w - margin - 105f * scale, top + 26f * scale, 11f, white, true)
        drawText(batteryText, w - margin - 7f * scale, top + 26f * scale, 11f, green, true, Paint.Align.RIGHT)

        // Compass + live altitude/satellite panel in the upper-left.
        val compassCx = margin + 48f * scale
        val compassCy = headerBottom + 65f * scale
        val compassRadius = 39f * scale
        canvas.drawCircle(compassCx, compassCy, compassRadius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(170, 3, 13, 22) })
        canvas.drawCircle(compassCx, compassCy, compassRadius, borderPaint)
        val bearing = Math.toRadians(telemetry.bearingDegrees.toDouble())
        canvas.drawLine(compassCx, compassCy,
            compassCx + kotlin.math.sin(bearing).toFloat() * compassRadius * .68f,
            compassCy - kotlin.math.cos(bearing).toFloat() * compassRadius * .68f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; strokeWidth = 4f * scale; strokeCap = Paint.Cap.ROUND })
        drawText("N", compassCx, compassCy - compassRadius + 13f * scale, 9f, red, true, Paint.Align.CENTER)
        drawText("S", compassCx, compassCy + compassRadius - 4f * scale, 9f, white, true, Paint.Align.CENTER)
        drawText("W", compassCx - compassRadius + 7f * scale, compassCy + 3f * scale, 8f, white, true, Paint.Align.CENTER)
        drawText("E", compassCx + compassRadius - 7f * scale, compassCy + 3f * scale, 8f, white, true, Paint.Align.CENTER)

        val infoLeft = compassCx + compassRadius + 12f * scale
        val infoRight = minOf(w * .43f, infoLeft + 142f * scale)
        val infoTop = headerBottom + 18f * scale
        drawPanel(RectF(infoLeft, infoTop, infoRight, infoTop + 102f * scale), 9f)
        drawText("ALTITUDE", infoLeft + 10f * scale, infoTop + 27f * scale, 9f, muted, true)
        drawText(telemetry.altitudeMeters.toInt().toString() + " m", infoRight - 9f * scale, infoTop + 29f * scale, 15f, cyan, true, Paint.Align.RIGHT)
        drawText("SATELLITES", infoLeft + 10f * scale, infoTop + 61f * scale, 9f, muted, true)
        drawText(telemetry.satellitesCount.toString(), infoRight - 9f * scale, infoTop + 63f * scale, 15f, green, true, Paint.Align.RIGHT)
        drawText("HEADING", infoLeft + 10f * scale, infoTop + 94f * scale, 9f, muted, true)
        drawText(telemetry.bearingDegrees.toInt().toString() + "°", infoRight - 9f * scale, infoTop + 96f * scale, 15f, AndroidColor.rgb(255, 211, 64), true, Paint.Align.RIGHT)

        // Upper-right panel shows real GPS coordinates, not invented weather/location data.
        val rightPanel = RectF(w * .68f, infoTop, w - margin, infoTop + 102f * scale)
        drawPanel(rightPanel, 9f)
        drawText(if (telemetry.isGpsFixed) "GPS LOCKED" else "SEARCHING GPS",
            rightPanel.centerX(), infoTop + 26f * scale, 11f, gpsColor, true, Paint.Align.CENTER)
        drawText(String.format(Locale.US, "LAT %.5f", telemetry.latitude),
            rightPanel.centerX(), infoTop + 57f * scale, 12f, cyan, true, Paint.Align.CENTER)
        drawText(String.format(Locale.US, "LON %.5f", telemetry.longitude),
            rightPanel.centerX(), infoTop + 86f * scale, 12f, green, true, Paint.Align.CENTER)

        // Analog speedometer is placed in the upper half, not at the bottom.
        val radius = minOf(w * .19f, h * .115f).coerceAtLeast(68f * scale)
        val cx = w * .5f
        val cy = if (h > w) h * .285f else h * .30f
        val dial = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawCircle(cx, cy, radius + 9f * scale,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(205, 3, 10, 17) })
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cyan; style = Paint.Style.STROKE; strokeWidth = 3f * scale; strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(dial, 135f, 270f, false, arc)
        canvas.drawArc(dial, 135f + 270f * .82f, 48.6f, false,
            Paint(arc).apply { color = red; strokeWidth = 4f * scale })
        for (i in 0..100) {
            val angle = Math.toRadians(135.0 + 270.0 * i / 100.0)
            val major = i % 10 == 0
            val mediumTick = i % 5 == 0
            val inner = radius - (if (major) 17f else if (mediumTick) 11f else 6f) * scale
            val outer = radius - 2f * scale
            val color = if (i >= 82) red else cyan
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = if (major) color else AndroidColor.argb(125, AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color))
                strokeWidth = (if (major) 2.4f else 1f) * scale; strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(cx + kotlin.math.cos(angle).toFloat() * inner,
                cy + kotlin.math.sin(angle).toFloat() * inner,
                cx + kotlin.math.cos(angle).toFloat() * outer,
                cy + kotlin.math.sin(angle).toFloat() * outer, tick)
            if (major) {
                val labelRadius = radius - 31f * scale
                drawText((i * 2).toString(),
                    cx + kotlin.math.cos(angle).toFloat() * labelRadius,
                    cy + kotlin.math.sin(angle).toFloat() * labelRadius + 4f * scale,
                    13f, cyan, true, Paint.Align.CENTER)
            }
        }
        val speed = telemetry.speedKmh.coerceIn(0f, 200f)
        val needleAngle = Math.toRadians(135.0 + 270.0 * speed / 200f)
        val tipX = cx + kotlin.math.cos(needleAngle).toFloat() * radius * .76f
        val tipY = cy + kotlin.math.sin(needleAngle).toFloat() * radius * .76f
        val tailX = cx - kotlin.math.cos(needleAngle).toFloat() * radius * .12f
        val tailY = cy - kotlin.math.sin(needleAngle).toFloat() * radius * .12f
        canvas.drawLine(tailX, tailY, tipX, tipY,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(100, 255, 48, 79); strokeWidth = 10f * scale; strokeCap = Paint.Cap.ROUND })
        canvas.drawLine(tailX, tailY, tipX, tipY,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red; strokeWidth = 4f * scale; strokeCap = Paint.Cap.ROUND })
        canvas.drawCircle(cx, cy, 10f * scale,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(10, 22, 31) })
        canvas.drawCircle(cx, cy, 10f * scale,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; style = Paint.Style.STROKE; strokeWidth = 1.5f * scale })
        drawText(speed.toInt().toString(), cx, cy + 57f * scale, 46f, green, true, Paint.Align.CENTER)
        drawText(unit, cx, cy + 81f * scale, 14f, cyan, true, Paint.Align.CENTER)

        // Permanent bottom telemetry strip, inset from all frame edges.
        val footerHeight = 94f * scale
        val footerBottom = h - 10f * scale
        val footerTop = footerBottom - footerHeight
        drawPanel(RectF(margin, footerTop, w - margin, footerBottom), 9f)
        val date = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault()).format(Date())
        drawText(date, margin + 10f * scale, footerTop + 22f * scale, 12f, cyan, true)
        val stats = listOf(
            "ALT" to (telemetry.altitudeMeters.toInt().toString() + " m"),
            "DIST" to String.format(Locale.US, "%.2f km", telemetry.distanceKm),
            "MAX" to (telemetry.maxSpeedKmh.toInt().toString() + " km/h"),
            "AVG" to (telemetry.avgSpeedKmh.toInt().toString() + " km/h"),
            "SAT" to telemetry.satellitesCount.toString()
        )
        val statWidth = (w - 2f * margin) / stats.size
        stats.forEachIndexed { index, stat ->
            val centerX = margin + statWidth * (index + .5f)
            drawText(stat.first, centerX, footerTop + 51f * scale, 10f, muted, true, Paint.Align.CENTER)
            val valueColor = when (index) {
                0 -> cyan
                1 -> AndroidColor.rgb(255, 211, 64)
                2 -> red
                3 -> green
                else -> AndroidColor.rgb(255, 211, 64)
            }
            drawText(stat.second, centerX, footerTop + 80f * scale, 13f,
                valueColor, true, Paint.Align.CENTER)
            if (index > 0) {
                val dividerX = margin + statWidth * index
                canvas.drawLine(dividerX, footerTop + 35f * scale, dividerX, footerBottom - 7f * scale,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(100, 69, 244, 255); strokeWidth = scale })
            }
        }
        canvas.restoreToCount(save)
    }

    /**
     * Keep CameraX output rotation in sync with the device/display orientation.
     * Updating both use cases lets the MP4 carry the correct portrait/landscape
     * rotation metadata instead of always being encoded as landscape.
     */
    fun updateTargetRotation(rotation: Int) {
        if (rotation == currentTargetRotation) return
        currentTargetRotation = rotation
        try {
            previewUseCase?.targetRotation = rotation
            videoCapture?.targetRotation = rotation
            Log.i("CameraRecordingManager", "Updated video target rotation: $rotation")
        } catch (e: Exception) {
            Log.e("CameraRecordingManager", "Failed to update target rotation", e)
        }
    }

    fun switchCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView, resolution: VideoResolution) {
        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        _currentLens.value = currentLensFacing
        startCamera(lifecycleOwner, previewView, resolution)
    }

    fun toggleNightMode() {
        isNightMode = !isNightMode
        _isNightBoostActive.value = isNightMode
        applyNightModeSettings()
    }

    private fun applyNightModeSettings() {
        val camera = activeCamera ?: return
        val control = camera.cameraControl
        val info = camera.cameraInfo

        val exposureState: ExposureState = info.exposureState
        if (exposureState.isExposureCompensationSupported) {
            val range = exposureState.exposureCompensationRange
            val targetIndex = if (isNightMode) {
                // Boost exposure compensation towards max positive EV for enhanced night visibility
                (range.upper * 0.75f).toInt()
            } else {
                0
            }
            try {
                control.setExposureCompensationIndex(targetIndex)
            } catch (e: Exception) {
                Log.w("CameraRecordingManager", "Could not set exposure compensation", e)
            }
        }
    }

    fun toggleTorch() {
        val camera = activeCamera ?: return
        if (camera.cameraInfo.hasFlashUnit()) {
            isTorchOn = !isTorchOn
            camera.cameraControl.enableTorch(isTorchOn)
            _isTorchActive.value = isTorchOn
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(audioEnabled: Boolean = true) {
        val vc = videoCapture ?: run {
            onRecordingError?.invoke("Kamera belum siap untuk merekam")
            return
        }

        if (activeRecording != null || _isRecording.value) return

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "RIDER_CAM_$timeStamp.mp4"
        val storageDisplayPath = "0://Movies/RiderCam/$fileName"

        // Ensure Movies/RiderCam directory exists on internal storage
        try {
            val moviesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "RiderCam"
            )
            if (!moviesDir.exists()) {
                moviesDir.mkdirs()
            }
        } catch (e: Exception) {
            Log.w("CameraRecordingManager", "Could not create Movies/RiderCam directory", e)
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RiderCam")
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        recordingStartTime = System.currentTimeMillis()

        var pending = vc.output.prepareRecording(context, outputOptions)
        if (audioEnabled) {
            try {
                pending = pending.withAudioEnabled()
            } catch (e: SecurityException) {
                Log.w("CameraRecordingManager", "Audio permission not granted, recording without audio", e)
            }
        }

        activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    _isRecording.value = true
                    _recordingDurationSeconds.value = 0L
                }
                is VideoRecordEvent.Status -> {
                    val durationNanos = event.recordingStats.recordedDurationNanos
                    _recordingDurationSeconds.value = durationNanos / 1_000_000_000L
                }
                is VideoRecordEvent.Finalize -> {
                    _isRecording.value = false
                    val duration = _recordingDurationSeconds.value
                    if (!event.hasError()) {
                        val outputUri = event.outputResults.outputUri
                        onRecordingSaved?.invoke(outputUri.toString(), storageDisplayPath, duration)
                    } else {
                        Log.e("CameraRecordingManager", "Video recording error: ${event.error}")
                        onRecordingError?.invoke("Perekaman berhenti: error code ${event.error}")
                    }
                    activeRecording = null
                    _recordingDurationSeconds.value = 0L
                }
            }
        }
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
        _isRecording.value = false
    }

    fun release() {
        stopRecording()
        cameraProvider?.unbindAll()
        liveHudEffect?.close()
        liveHudEffect = null
    }
}
