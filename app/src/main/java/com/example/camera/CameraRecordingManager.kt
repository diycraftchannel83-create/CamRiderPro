package com.example.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
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
                val telemetry = telemetryProvider?.invoke()
                if (telemetry != null) {
                    drawLiveHud(canvas, telemetry, speedUnitLabelProvider?.invoke() ?: "km/h", activeCamera?.cameraInfo?.getSensorRotationDegrees(currentTargetRotation) ?: 0)
                } else {
                    drawLiveHud(canvas, GpsTelemetry(), speedUnitLabelProvider?.invoke() ?: "km/h", activeCamera?.cameraInfo?.getSensorRotationDegrees(currentTargetRotation) ?: 0)
                }
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
    private fun drawLiveHud(canvas: Canvas, telemetry: GpsTelemetry, unit: String, rotationDegrees: Int) {
        val rawWidth = canvas.width.toFloat()
        val rawHeight = canvas.height.toFloat()
        if (rawWidth <= 0f || rawHeight <= 0f) return

        // Counter-rotate the HUD against CameraX sensor rotation so it stays upright
        // after the MP4 rotation metadata is applied by video players.
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        val canvasSave = canvas.save()
        when (normalizedRotation) {
            90 -> canvas.rotate(-90f, rawWidth / 2f, rawHeight / 2f)
            180 -> canvas.rotate(-180f, rawWidth / 2f, rawHeight / 2f)
            270 -> canvas.rotate(-270f, rawWidth / 2f, rawHeight / 2f)
        }
        val quarterTurn = normalizedRotation == 90 || normalizedRotation == 270
        val w = if (quarterTurn) rawHeight else rawWidth
        val h = if (quarterTurn) rawWidth else rawHeight

        val scale = (w / 1080f).coerceIn(0.55f, 2.6f)
        val cyan = AndroidColor.rgb(69, 244, 255)
        val green = AndroidColor.rgb(0, 245, 160)
        val red = AndroidColor.rgb(255, 48, 79)
        val white = AndroidColor.rgb(245, 251, 255)
        val muted = AndroidColor.rgb(190, 211, 222)

        fun paint(color: Int, size: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size * scale
                textAlign = align
                typeface = Typeface.create(if (bold) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }

        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(92, 4, 12, 20) }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(185, cyan ushr 16 and 0xff, cyan ushr 8 and 0xff, cyan and 0xff)
            style = Paint.Style.STROKE
            strokeWidth = 1.3f * scale
        }
        val top = 18f * scale
        val margin = 24f * scale
        val header = RectF(margin, top, w - margin, top + 42f * scale)
        canvas.drawRoundRect(header, 16f * scale, 16f * scale, panelPaint)
        canvas.drawRoundRect(header, 16f * scale, 16f * scale, borderPaint)

        val recPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red }
        canvas.drawCircle(margin + 15f * scale, top + 21f * scale, 6f * scale, recPaint)
        canvas.drawText("AUTO REC", margin + 30f * scale, top + 27f * scale, paint(green, 15f, true))
        canvas.drawText("1080p  60FPS  H.265", margin + 145f * scale, top + 26f * scale, paint(white, 11f))
        val gpsColor = if (telemetry.isGpsFixed) green else red
        canvas.drawCircle(w - margin - 175f * scale, top + 21f * scale, 5f * scale, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gpsColor })
        canvas.drawText(if (telemetry.isGpsFixed) "GPS LOCK" else "GPS SEARCH", w - margin - 162f * scale, top + 26f * scale, paint(white, 11f))
        val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val batteryLevel = batteryIntent?.getIntExtra("level", -1) ?: -1
        val batteryScale = batteryIntent?.getIntExtra("scale", 100) ?: 100
        val batteryPct = if (batteryLevel >= 0 && batteryScale > 0) batteryLevel * 100 / batteryScale else 0
        canvas.drawText("BAT $batteryPct%", w - margin - 8f * scale, top + 26f * scale, paint(green, 11f, true, Paint.Align.RIGHT))

        // Supporting telemetry at upper left/right.
        canvas.drawText("ALT  ${telemetry.altitudeMeters.toInt()} m", margin + 4f * scale, top + 72f * scale, paint(white, 12f, true))
        canvas.drawText("SAT  ${telemetry.satellitesCount}", margin + 4f * scale, top + 94f * scale, paint(muted, 11f))
        canvas.drawText(
            String.format(Locale.US, "%.5f, %.5f", telemetry.latitude, telemetry.longitude),
            w - margin - 4f * scale, top + 72f * scale, paint(white, 11f, true, Paint.Align.RIGHT)
        )

        // Large analog 0–200 km/h dial, with the needle driven by the current GPS sample.
        val radius = minOf(w * 0.285f, h * 0.265f).coerceAtLeast(95f * scale)
        val cx = w * 0.5f
        val cy = if (h > w) h * 0.66f else h * 0.57f
        val dialRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cyan
            style = Paint.Style.STROKE
            strokeWidth = 3f * scale
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(dialRect, 135f, 270f, false, arc)
        val redArc = Paint(arc).apply { color = red; strokeWidth = 4f * scale }
        canvas.drawArc(dialRect, 135f + 270f * .82f, 48.6f, false, redArc)

        for (i in 0..100) {
            val angle = Math.toRadians(135.0 + 270.0 * i / 100.0)
            val major = i % 10 == 0
            val mediumTick = i % 5 == 0
            val inner = radius - (if (major) 19f else if (mediumTick) 13f else 7f) * scale
            val outer = radius - 2f * scale
            val color = if (i >= 82) red else cyan
            val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = if (major) color else AndroidColor.argb(125, AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color))
                strokeWidth = (if (major) 2.7f else 1f) * scale
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(
                cx + kotlin.math.cos(angle).toFloat() * inner,
                cy + kotlin.math.sin(angle).toFloat() * inner,
                cx + kotlin.math.cos(angle).toFloat() * outer,
                cy + kotlin.math.sin(angle).toFloat() * outer,
                tickPaint
            )
            if (major) {
                val labelRadius = radius - 38f * scale
                val labelX = cx + kotlin.math.cos(angle).toFloat() * labelRadius
                val labelY = cy + kotlin.math.sin(angle).toFloat() * labelRadius + 5f * scale
                canvas.drawText((i * 2).toString(), labelX, labelY, paint(white, 13f, true, Paint.Align.CENTER))
            }
        }

        val speed = telemetry.speedKmh.coerceIn(0f, 200f)
        val needleAngle = Math.toRadians(135.0 + 270.0 * speed / 200f)
        val tipX = cx + kotlin.math.cos(needleAngle).toFloat() * radius * .77f
        val tipY = cy + kotlin.math.sin(needleAngle).toFloat() * radius * .77f
        val tailX = cx - kotlin.math.cos(needleAngle).toFloat() * radius * .12f
        val tailY = cy - kotlin.math.sin(needleAngle).toFloat() * radius * .12f
        canvas.drawLine(tailX, tailY, tipX, tipY, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(95, 255, 48, 79); strokeWidth = 12f * scale; strokeCap = Paint.Cap.ROUND })
        canvas.drawLine(tailX, tailY, tipX, tipY, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red; strokeWidth = 4.5f * scale; strokeCap = Paint.Cap.ROUND })
        canvas.drawCircle(cx, cy, 12f * scale, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(15, 25, 34) })
        canvas.drawCircle(cx, cy, 12f * scale, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; style = Paint.Style.STROKE; strokeWidth = 1.5f * scale })
        canvas.drawText(speed.toInt().toString(), cx, cy + 66f * scale, paint(white, 52f, true, Paint.Align.CENTER))
        canvas.drawText(unit, cx, cy + 89f * scale, paint(cyan, 14f, true, Paint.Align.CENTER))

        // Compact transparent bottom telemetry strip.
        val footerTop = h - 72f * scale
        val footer = RectF(margin, footerTop, w - margin, h - 12f * scale)
        canvas.drawRoundRect(footer, 9f * scale, 9f * scale, panelPaint)
        canvas.drawRoundRect(footer, 9f * scale, 9f * scale, borderPaint)
        val date = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault()).format(Date())
        canvas.drawText(date, margin + 10f * scale, footerTop + 23f * scale, paint(white, 10f, true))
        val stats = listOf(
            "LAT/LON" to String.format(Locale.US, "%.4f / %.4f", telemetry.latitude, telemetry.longitude),
            "ALT" to "${telemetry.altitudeMeters.toInt()} m",
            "DIST" to String.format(Locale.US, "%.2f km", telemetry.distanceKm),
            "MAX" to "${telemetry.maxSpeedKmh.toInt()} km/h",
            "AVG" to "${telemetry.avgSpeedKmh.toInt()} km/h"
        )
        val statStart = margin + 210f * scale
        val statWidth = (w - margin - statStart - 8f * scale) / stats.size
        stats.forEachIndexed { index, stat ->
            val x = statStart + index * statWidth
            canvas.drawText(stat.first, x, footerTop + 20f * scale, paint(muted, 8.5f, true))
            canvas.drawText(stat.second, x, footerTop + 39f * scale, paint(if (index >= 3) green else white, 9.5f, true))
            if (index > 0) {
                canvas.drawLine(x - 5f * scale, footerTop + 9f * scale, x - 5f * scale, footer.bottom - 8f * scale,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(90, 69, 244, 255); strokeWidth = scale })
            }
        }
        canvas.restoreToCount(canvasSave)
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
