package com.example.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
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

            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

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

            videoCapture = VideoCapture.withOutput(recorder)

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
                    drawLiveHud(
                        canvas = canvas,
                        speed = telemetry.speedKmh,
                        unit = speedUnitLabelProvider?.invoke() ?: "km/h",
                        gpsFixed = telemetry.isGpsFixed,
                        maxSpeed = telemetry.maxSpeedKmh,
                        distanceKm = telemetry.distanceKm
                    )
                } else {
                    drawLiveHud(canvas, 0f, "km/h", false, 0f, 0f)
                }
                true
            }
            liveHudEffect = hudEffect

            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(videoCapture!!)
                .addEffect(hudEffect)
                .build()

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

    private fun drawLiveHud(
        canvas: Canvas,
        speed: Float,
        unit: String,
        gpsFixed: Boolean,
        maxSpeed: Float,
        distanceKm: Float
    ) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        if (w <= 0f || h <= 0f) return

        val scale = (w / 1080f).coerceIn(0.65f, 2.5f)
        val margin = 28f * scale
        val panelW = 300f * scale
        val panelH = 126f * scale
        val left = w - panelW - margin
        val top = h - panelH - margin
        val radius = 18f * scale

        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(205, 7, 13, 20)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(left, top, left + panelW, top + panelH, radius, radius, panel)

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(0, 229, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f * scale
        }
        canvas.drawRoundRect(left, top, left + panelW, top + panelH, radius, radius, border)

        val gpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (gpsFixed) AndroidColor.rgb(0, 230, 118) else AndroidColor.rgb(255, 55, 75)
            style = Paint.Style.FILL
        }
        val gpsX = left + 22f * scale
        val gpsY = top + 23f * scale
        canvas.drawCircle(gpsX, gpsY, 6f * scale, gpsPaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(190, 208, 220)
            textSize = 15f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(if (gpsFixed) "GPS LOCK" else "GPS NO SIGNAL", left + 36f * scale, top + 28f * scale, labelPaint)

        val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 46f * scale
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText(speed.coerceAtLeast(0f).toInt().toString(), left + 18f * scale, top + 78f * scale, speedPaint)

        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(0, 229, 255)
            textSize = 17f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(unit, left + 100f * scale, top + 77f * scale, unitPaint)

        val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(210, 221, 230)
            textSize = 12f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        canvas.drawText("MAX ${maxSpeed.toInt()}   DIST ${String.format(Locale.US, "%.1f", distanceKm)} km", left + 18f * scale, top + 106f * scale, detailPaint)
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
