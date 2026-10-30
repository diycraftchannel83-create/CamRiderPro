package com.example.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.graphics.PorterDuff
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
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

    // CameraX OverlayEffect composes with OpenGL. Keep GL off the UI thread and
    // rasterize the expensive HUD only 8 times/second instead of on every frame.
    private val hudGlThread = HandlerThread("CamRider-OpenGL").apply { start() }
    private val hudGlHandler = Handler(hudGlThread.looper)
    private val hudRenderThread = HandlerThread("CamRider-HUD-Raster").apply { start() }
    private val hudRenderHandler = Handler(hudRenderThread.looper)
    private val hudBitmapLock = Any()
    private var cachedHudBitmap: Bitmap? = null
    private var spareHudBitmap: Bitmap? = null
    private var cachedHudWidth = 0
    private var cachedHudHeight = 0
    private var cachedHudLastRenderMs = 0L
    private var hudRenderPending = false

    /** Supplies the latest GPS values to the encoder-side HUD for every video frame. */
    var telemetryProvider: (() -> GpsTelemetry)? = null
    var speedUnitLabelProvider: (() -> String)? = null
    var videoProfileLabelProvider: (() -> String)? = null
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
                0, // Real-time HUD: render immediately without buffering camera frames.
                hudGlHandler
            ) { throwable ->
                Log.e("CameraRecordingManager", "OpenGL HUD effect failed", throwable)
            }
            hudEffect.setOnDrawListener { frame ->
                val outputCanvas = frame.overlayCanvas
                outputCanvas.drawColor(AndroidColor.TRANSPARENT, PorterDuff.Mode.CLEAR)
                val frameWidth = outputCanvas.width
                val frameHeight = outputCanvas.height
                val now = SystemClock.elapsedRealtime()

                // Rasterize a snapshot on a worker thread. Each video frame only copies
                // the cached bitmap into CameraX's OpenGL overlay texture.
                synchronized(hudBitmapLock) {
                    val sizeChanged = frameWidth != cachedHudWidth || frameHeight != cachedHudHeight
                    val stale = now - cachedHudLastRenderMs >= 125L
                    if ((sizeChanged || stale) && !hudRenderPending && frameWidth > 0 && frameHeight > 0) {
                        hudRenderPending = true
                        val crop = android.graphics.Rect(frame.cropRect)
                        val rotation = frame.rotationDegrees
                        val replaceBuffers = sizeChanged || cachedHudBitmap == null || spareHudBitmap == null
                        val reusableTarget = if (replaceBuffers) null else spareHudBitmap
                        hudRenderHandler.post {
                            var rendered: Bitmap? = reusableTarget
                            var newSpare: Bitmap? = null
                            try {
                                if (rendered == null) {
                                    rendered = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
                                    newSpare = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
                                } else {
                                    rendered.eraseColor(AndroidColor.TRANSPARENT)
                                }
                                val targetBitmap = rendered
                                val rasterCanvas = Canvas(targetBitmap)
                                val telemetry = telemetryProvider?.invoke() ?: GpsTelemetry()
                                drawLiveHud(
                                    canvas = rasterCanvas,
                                    telemetry = telemetry,
                                    unit = speedUnitLabelProvider?.invoke() ?: "km/h",
                                    cropRect = crop,
                                    rotationDegrees = rotation
                                )
                                synchronized(hudBitmapLock) {
                                    val previousActive = cachedHudBitmap
                                    val previousSpare = spareHudBitmap
                                    cachedHudBitmap = targetBitmap
                                    spareHudBitmap = if (replaceBuffers) newSpare else previousActive
                                    cachedHudWidth = frameWidth
                                    cachedHudHeight = frameHeight
                                    cachedHudLastRenderMs = SystemClock.elapsedRealtime()
                                    hudRenderPending = false
                                    rendered = null
                                    newSpare = null
                                    if (replaceBuffers) {
                                        previousActive?.recycle()
                                        previousSpare?.recycle()
                                    }
                                }
                            } catch (error: Throwable) {
                                Log.e("CameraRecordingManager", "HUD raster update failed", error)
                                synchronized(hudBitmapLock) { hudRenderPending = false }
                            } finally {
                                rendered?.recycle()
                                newSpare?.recycle()
                            }
                        }
                    }
                    // Lock protects the cached bitmap from being recycled while copied.
                    cachedHudBitmap?.let { outputCanvas.drawBitmap(it, 0f, 0f, null) }
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
        val yellow = AndroidColor.rgb(255, 211, 64)
        val white = AndroidColor.rgb(245, 251, 255)
        val muted = AndroidColor.rgb(175, 202, 215)
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(190, 4, 12, 20) }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cyan; style = Paint.Style.STROKE; strokeWidth = 1.6f * scale
            setShadowLayer(5f * scale, 0f, 0f, cyan)
        }
        fun drawText(value: String, x: Float, y: Float, size: Float, color: Int = white,
                     bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; textSize = size * scale; textAlign = align
                typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
                if (bold) setShadowLayer(4f * scale, 0f, 0f, color)
            }
            value.split("\n").forEachIndexed { index, line ->
                canvas.drawText(line, x, y + index * size * 1.18f * scale, p)
            }
        }
        fun drawPanel(rect: RectF, radius: Float = 12f, color: Int = cyan) {
            canvas.drawRoundRect(rect, radius * scale, radius * scale, panelPaint)
            canvas.drawRoundRect(rect, radius * scale, radius * scale,
                Paint(borderPaint).apply { this.color = color; setShadowLayer(6f * scale, 0f, 0f, color) })
        }

        val margin = 18f * scale
        val top = 12f * scale
        val headerHeight = 46f * scale

        // LAYER 1 — status, video profile, GPS, battery and clock.
        drawPanel(RectF(margin, top, margin + 190f * scale, top + headerHeight), 14f, green)
        canvas.drawCircle(margin + 17f * scale, top + 23f * scale, 6f * scale,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red; setShadowLayer(8f * scale, 0f, 0f, red) })
        drawText("VIDEO REC", margin + 31f * scale, top + 29f * scale, 13f, red, true)
        drawPanel(RectF(margin, top + 52f * scale, margin + 235f * scale, top + 82f * scale), 8f, cyan)
        drawText(videoProfileLabelProvider?.invoke() ?: "1080p  60FPS  H.265", margin + 10f * scale, top + 73f * scale, 11f, white, true)

        val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra("level", -1) ?: -1
        val batteryScale = battery?.getIntExtra("scale", 100) ?: 100
        val batteryText = if (level >= 0 && batteryScale > 0) (level * 100 / batteryScale).toString() + "%" else "--"
        val rightPanel = RectF(w - margin - 286f * scale, top, w - margin, top + headerHeight)
        val gpsColor = if (telemetry.isGpsFixed) green else yellow
        drawPanel(rightPanel, 14f, gpsColor)
        canvas.drawCircle(rightPanel.left + 24f * scale, top + 23f * scale, 6f * scale,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gpsColor; setShadowLayer(7f * scale, 0f, 0f, gpsColor) })
        drawText("GPS", rightPanel.left + 37f * scale, top + 29f * scale, 12f, gpsColor, true)
        val bx = rightPanel.left + 91f * scale
        val by = top + 14f * scale
        val batteryOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; style = Paint.Style.STROKE; strokeWidth = 2f * scale }
        canvas.drawRoundRect(RectF(bx, by, bx + 22f * scale, by + 18f * scale), 3f * scale, 3f * scale, batteryOutline)
        canvas.drawRect(bx + 22f * scale, by + 5f * scale, bx + 26f * scale, by + 13f * scale, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green })
        val fill = (level.coerceIn(0, 100) / 100f) * 16f
        canvas.drawRect(bx + 3f * scale, by + 3f * scale, bx + (3f + fill) * scale, by + 15f * scale, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green })
        drawText(batteryText, bx + 33f * scale, top + 29f * scale, 11f, green, true)
        drawText(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
            rightPanel.right - 10f * scale, top + 29f * scale, 13f, white, true, Paint.Align.RIGHT)

        // LAYER 2 — left compass/trip panel and central analog speedometer.
        val compassPanel = RectF(margin, top + 94f * scale, margin + 218f * scale, top + 390f * scale)
        drawPanel(compassPanel, 12f, cyan)
        drawText("NAVIGATION", compassPanel.centerX(), compassPanel.top + 20f * scale, 10f, muted, true, Paint.Align.CENTER)
        val compassCx = compassPanel.centerX()
        val compassCy = compassPanel.top + 112f * scale
        val compassRadius = 78f * scale
        canvas.drawCircle(compassCx, compassCy, compassRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(225, 23, 39, 57) })
        canvas.drawCircle(compassCx, compassCy, compassRadius, Paint(borderPaint).apply { color = AndroidColor.rgb(65, 91, 116) })
        canvas.drawCircle(compassCx, compassCy, compassRadius * .68f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(90, 69, 244, 255); style = Paint.Style.STROKE; strokeWidth = scale
        })
        canvas.drawCircle(compassCx, compassCy, compassRadius * .34f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(90, 69, 244, 255); style = Paint.Style.STROKE; strokeWidth = scale
        })
        val bearing = Math.toRadians(telemetry.bearingDegrees.toDouble())
        canvas.drawLine(compassCx, compassCy,
            compassCx + kotlin.math.sin(bearing).toFloat() * compassRadius * .72f,
            compassCy - kotlin.math.cos(bearing).toFloat() * compassRadius * .72f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; strokeWidth = 5f * scale; strokeCap = Paint.Cap.ROUND; setShadowLayer(8f * scale, 0f, 0f, green) })
        drawText("N", compassCx, compassCy - compassRadius + 16f * scale, 12f, red, true, Paint.Align.CENTER)
        drawText("S", compassCx, compassCy + compassRadius - 7f * scale, 11f, white, true, Paint.Align.CENTER)
        drawText("W", compassCx - compassRadius + 10f * scale, compassCy + 4f * scale, 10f, white, true, Paint.Align.CENTER)
        drawText("E", compassCx + compassRadius - 10f * scale, compassCy + 4f * scale, 10f, white, true, Paint.Align.CENTER)
        val distanceY = compassPanel.top + 224f * scale
        drawPanel(RectF(compassPanel.left + 18f * scale, distanceY - 22f * scale,
            compassPanel.right - 18f * scale, distanceY + 12f * scale), 18f, green)
        drawText(String.format(Locale.US, "%.2f km", telemetry.distanceKm),
            compassPanel.centerX(), distanceY + 1f * scale, 13f, green, true, Paint.Align.CENTER)
        drawText("ALT", compassPanel.left + 18f * scale, compassPanel.bottom - 24f * scale, 10f, muted, true)
        drawText(telemetry.altitudeMeters.toInt().toString() + " m", compassPanel.left + 54f * scale,
            compassPanel.bottom - 24f * scale, 12f, white, true)
        drawText("SAT", compassPanel.right - 72f * scale, compassPanel.bottom - 24f * scale, 10f, muted, true)
        drawText(telemetry.satellitesCount.toString(), compassPanel.right - 20f * scale,
            compassPanel.bottom - 24f * scale, 12f, green, true, Paint.Align.RIGHT)

        val unitMultiplier = if (unit.equals("mph", ignoreCase = true)) 0.621371f else 1f
        val speed = (telemetry.speedKmh * unitMultiplier).coerceIn(0f, 200f)
        val radius = minOf(w * .275f, h * .175f).coerceAtLeast(105f * scale)
        val cx = w * .53f
        val cy = if (h > w) h * .35f else h * .50f
        val dial = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawCircle(cx, cy, radius + 13f * scale, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(220, 3, 10, 17) })
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cyan; style = Paint.Style.STROKE; strokeWidth = 5f * scale; strokeCap = Paint.Cap.ROUND
            setShadowLayer(11f * scale, 0f, 0f, cyan)
        }
        canvas.drawArc(dial, 135f, 270f, false, arc)
        canvas.drawArc(dial, 135f + 270f * .82f, 48.6f, false,
            Paint(arc).apply { color = red; strokeWidth = 6f * scale; setShadowLayer(12f * scale, 0f, 0f, red) })
        for (i in 0..40) {
            val angle = Math.toRadians(135.0 + 270.0 * i / 40.0)
            val major = i % 5 == 0
            val inner = radius - (if (major) 20f else 10f) * scale
            val outer = radius - 3f * scale
            val tickColor = if (i >= 33) red else cyan
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tickColor; strokeWidth = (if (major) 3f else 1.5f) * scale; strokeCap = Paint.Cap.ROUND
                if (major) setShadowLayer(5f * scale, 0f, 0f, tickColor)
            }
            canvas.drawLine(cx + kotlin.math.cos(angle).toFloat() * inner,
                cy + kotlin.math.sin(angle).toFloat() * inner,
                cx + kotlin.math.cos(angle).toFloat() * outer,
                cy + kotlin.math.sin(angle).toFloat() * outer, tick)
            if (major) drawText((i * 5).toString(), cx + kotlin.math.cos(angle).toFloat() * (radius - 42f * scale),
                cy + kotlin.math.sin(angle).toFloat() * (radius - 42f * scale) + 5f * scale, 12f, cyan, true, Paint.Align.CENTER)
        }
        val needleAngle = Math.toRadians(135.0 + 270.0 * speed / 200f)
        val tipX = cx + kotlin.math.cos(needleAngle).toFloat() * radius * .78f
        val tipY = cy + kotlin.math.sin(needleAngle).toFloat() * radius * .78f
        val tailX = cx - kotlin.math.cos(needleAngle).toFloat() * radius * .12f
        val tailY = cy - kotlin.math.sin(needleAngle).toFloat() * radius * .12f
        canvas.drawLine(tailX, tailY, tipX, tipY, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(120, 255, 48, 79); strokeWidth = 13f * scale; strokeCap = Paint.Cap.ROUND
        })
        canvas.drawLine(tailX, tailY, tipX, tipY, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = red; strokeWidth = 5f * scale; strokeCap = Paint.Cap.ROUND; setShadowLayer(9f * scale, 0f, 0f, red)
        })
        canvas.drawCircle(cx, cy, 12f * scale, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(10, 22, 31) })
        canvas.drawCircle(cx, cy, 12f * scale, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cyan; style = Paint.Style.STROKE; strokeWidth = 2f * scale; setShadowLayer(6f * scale, 0f, 0f, cyan)
        })
        drawText(speed.toInt().toString(), cx, cy + 24f * scale, 58f, white, true, Paint.Align.CENTER)
        drawText(unit.uppercase(Locale.ROOT), cx, cy + 56f * scale, 15f, cyan, true, Paint.Align.CENTER)
        drawText("GEAR / RPM  --", cx, cy + 91f * scale, 12f, green, true, Paint.Align.CENTER)

        // LAYER 3 — trip statistics; this same HUD is encoded into each CameraX frame.
        val footer = RectF(margin, h - 124f * scale, w - margin, h - 12f * scale)
        drawPanel(footer, 12f, AndroidColor.rgb(77, 101, 123))
        val date = SimpleDateFormat("yyyy-MM-dd\nHH:mm:ss", Locale.getDefault()).format(Date())
        val cells = listOf(
            "DATE / TIME" to date,
            "LAT / LON" to String.format(Locale.US, "%.5f\n%.5f", telemetry.latitude, telemetry.longitude),
            "ALT" to (telemetry.altitudeMeters.toInt().toString() + " m"),
            "DIST" to String.format(Locale.US, "%.2f km", telemetry.distanceKm),
            "MAX" to ((telemetry.maxSpeedKmh * unitMultiplier).toInt().toString() + " " + unit),
            "AVG" to ((telemetry.avgSpeedKmh * unitMultiplier).toInt().toString() + " " + unit)
        )
        val cellWidth = footer.width() / cells.size
        cells.forEachIndexed { index, cell ->
            val left = footer.left + index * cellWidth
            val center = left + cellWidth * .5f
            drawText(cell.first, center, footer.top + 34f * scale, 9f, muted, true, Paint.Align.CENTER)
            drawText(cell.second, center, footer.top + 61f * scale, 11f,
                if (index == 3) yellow else if (index >= 4) green else white, true, Paint.Align.CENTER)
            if (index > 0) canvas.drawLine(left, footer.top + 15f * scale, left, footer.bottom - 12f * scale,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(100, 69, 244, 255); strokeWidth = scale })
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
        hudRenderHandler.removeCallbacksAndMessages(null)
        hudGlHandler.removeCallbacksAndMessages(null)
        synchronized(hudBitmapLock) {
            cachedHudBitmap?.recycle()
            spareHudBitmap?.recycle()
            cachedHudBitmap = null
            spareHudBitmap = null
            cachedHudWidth = 0
            cachedHudHeight = 0
            hudRenderPending = false
        }
        hudRenderThread.quitSafely()
        hudGlThread.quitSafely()
    }
}
