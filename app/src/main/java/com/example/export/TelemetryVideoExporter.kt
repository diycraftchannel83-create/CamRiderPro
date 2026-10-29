package com.example.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.data.model.RideSession
import com.example.data.model.SpeedUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

sealed class ExportState {
    object Idle : ExportState()
    data class Exporting(val progressPercent: Int) : ExportState()
    data class Success(val outputDisplayPath: String, val outputUri: Uri) : ExportState()
    data class Error(val errorMessage: String) : ExportState()
}

class TelemetryVideoExporter(private val context: Context) {

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private var activeTransformer: Transformer? = null

    suspend fun exportVideoWithSpeedometerOverlay(
        session: RideSession,
        speedUnit: SpeedUnit = SpeedUnit.KMH
    ) = withContext(Dispatchers.IO) {
        try {
            _exportState.value = ExportState.Exporting(5)

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val outputFileName = "RIDER_CAM_OVERLAY_$timeStamp.mp4"
            val displayPath = "0://Movies/RiderCam/$outputFileName"

            // Target destination file in internal storage 0://Movies/RiderCam/
            val moviesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "RiderCam"
            )
            if (!moviesDir.exists()) moviesDir.mkdirs()

            val outputFile = File(moviesDir, outputFileName)

            // Render crisp, high-contrast Telemetry Speedometer HUD Bitmap Overlay
            val overlayBitmap = createSpeedometerOverlayBitmap(
                width = 1920,
                height = 1080,
                session = session,
                speedUnit = speedUnit
            )

            // Media3 Transformer setup
            val inputUri = if (session.videoPath.startsWith("content://")) {
                Uri.parse(session.videoPath)
            } else {
                Uri.fromFile(File(session.videoPath))
            }

            val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(overlayBitmap)
            val overlayEffect = OverlayEffect(listOf(bitmapOverlay))

            val effects = Effects(
                emptyList(),
                listOf<Effect>(overlayEffect)
            )

            val mediaItem = MediaItem.fromUri(inputUri)
            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setEffects(effects)
                .build()

            _exportState.value = ExportState.Exporting(20)

            val transformerListener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    // The Transformer has already written outputFile. Scan that exact file instead
                    // of inserting a new, empty MediaStore row (which made exports appear broken).
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(outputFile.absolutePath),
                        arrayOf("video/mp4")
                    ) { _, scannedUri ->
                        val uri = scannedUri ?: Uri.fromFile(outputFile)
                        _exportState.value = ExportState.Success(displayPath, uri)
                        activeTransformer = null
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    Log.e("TelemetryVideoExporter", "Transformation failed", exportException)
                    _exportState.value = ExportState.Error(
                        "Gagal mengekspor: ${exportException.localizedMessage ?: "Format tidak didukung"}"
                    )
                    activeTransformer = null
                }
            }

            val transformer = Transformer.Builder(context)
                .addListener(transformerListener)
                .build()

            activeTransformer = transformer

            withContext(Dispatchers.Main) {
                transformer.start(editedMediaItem, outputFile.absolutePath)
            }

            // Monitor progress
            val progressHolder = ProgressHolder()
            while (activeTransformer != null) {
                val progressState = transformer.getProgress(progressHolder)
                if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                    _exportState.value = ExportState.Exporting(progressHolder.progress)
                }
                kotlinx.coroutines.delay(400)
            }

        } catch (e: Exception) {
            Log.e("TelemetryVideoExporter", "Export error", e)
            _exportState.value = ExportState.Error("Ekspor gagal: ${e.message}")
        }
    }

    fun resetState() {
        _exportState.value = ExportState.Idle
    }

    private fun createSpeedometerOverlayBitmap(
        width: Int,
        height: Int,
        session: RideSession,
        speedUnit: SpeedUnit
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Draw the supplied artwork used by the live app HUD as the video base layer.
        // The telemetry summary is rendered above this artwork below.
        BitmapFactory.decodeResource(context.resources, com.example.R.drawable.camrider_flyer_overlay)?.let { artwork ->
            val destination = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val artworkPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = 224
            }
            canvas.drawBitmap(artwork, null, destination, artworkPaint)
            artwork.recycle()
        }

        // Use measured average speed instead of inventing a speed value from the maximum.
        // This is a summary HUD; per-frame live telemetry is not stored in the current ride model.
        val speedVal = session.avgSpeedKmh
        val displaySpeed = (speedVal * speedUnit.multiplierFromKmh).toInt()
        val maxSpeedDisplay = (session.maxSpeedKmh * speedUnit.multiplierFromKmh).toInt()

        // 1. Draw Top Header Bar
        val headerPaint = Paint().apply {
            color = Color.argb(190, 10, 14, 20)
            style = Paint.Style.FILL
        }
        val headerRect = RectF(40f, 30f, 650f, 100f)
        canvas.drawRoundRect(headerRect, 20f, 20f, headerPaint)

        val recDotPaint = Paint().apply {
            color = Color.argb(255, 255, 61, 0)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(70f, 65f, 12f, recDotPaint)

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 32f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val hours = session.durationSeconds / 3600
        val mins = (session.durationSeconds % 3600) / 60
        val secs = session.durationSeconds % 60
        canvas.drawText(
            String.format("REC %02d:%02d:%02d  4K 60FPS", hours, mins, secs),
            95f,
            75f,
            textPaint
        )

        // 2. Draw Speedometer Cockpit Gauge at Bottom Center
        val centerX = width / 2f
        val centerY = height - 190f
        val gaugeRadius = 150f

        // Cockpit Background Card
        val cardPaint = Paint().apply {
            color = Color.argb(215, 9, 12, 16)
            style = Paint.Style.FILL
        }
        val cardBorder = Paint().apply {
            color = Color.argb(180, 0, 230, 118)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val cockpitRect = RectF(centerX - 420f, centerY - 150f, centerX + 420f, centerY + 140f)
        canvas.drawRoundRect(cockpitRect, 30f, 30f, cardPaint)
        canvas.drawRoundRect(cockpitRect, 30f, 30f, cardBorder)

        // Draw Left Stat Block: DISTANCE & MAX SPEED
        val labelPaint = Paint().apply {
            color = Color.argb(200, 148, 163, 184)
            textSize = 22f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val valPaint = Paint().apply {
            color = Color.WHITE
            textSize = 36f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText("DISTANCE", centerX - 380f, centerY - 60f, labelPaint)
        canvas.drawText(String.format("%.1f km", session.distanceKm), centerX - 380f, centerY - 15f, valPaint)

        canvas.drawText("MAX SPEED", centerX - 380f, centerY + 45f, labelPaint)
        val maxSpeedPaint = Paint(valPaint).apply { color = Color.argb(255, 255, 214, 0) }
        canvas.drawText("$maxSpeedDisplay ${speedUnit.label}", centerX - 380f, centerY + 90f, maxSpeedPaint)

        // Gauge Arc Track
        val trackPaint = Paint().apply {
            color = Color.argb(255, 30, 41, 59)
            style = Paint.Style.STROKE
            strokeWidth = 20f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        val arcRect = RectF(
            centerX - gaugeRadius,
            centerY - gaugeRadius,
            centerX + gaugeRadius,
            centerY + gaugeRadius
        )
        canvas.drawArc(arcRect, 140f, 260f, false, trackPaint)

        // Active Neon Speed Arc
        val activeArcPaint = Paint().apply {
            color = Color.argb(255, 0, 230, 118)
            style = Paint.Style.STROKE
            strokeWidth = 22f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        val sweepAngle = 260f * (displaySpeed / 160f).coerceIn(0.05f, 1f)
        canvas.drawArc(arcRect, 140f, sweepAngle, false, activeArcPaint)

        // Gauge Ticks
        val tickPaint = Paint().apply {
            color = Color.argb(255, 0, 230, 118)
            strokeWidth = 4f
            isAntiAlias = true
        }
        for (i in 0..8) {
            val angleDeg = 140f + (260f * (i / 8f))
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val x1 = (centerX + (gaugeRadius - 10f) * cos(angleRad)).toFloat()
            val y1 = (centerY + (gaugeRadius - 10f) * sin(angleRad)).toFloat()
            val x2 = (centerX + (gaugeRadius + 12f) * cos(angleRad)).toFloat()
            val y2 = (centerY + (gaugeRadius + 12f) * sin(angleRad)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        // Center Big Speed Number
        val speedNumPaint = Paint().apply {
            color = Color.WHITE
            textSize = 90f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText("$displaySpeed", centerX, centerY + 25f, speedNumPaint)

        val unitPaint = Paint().apply {
            color = Color.argb(255, 0, 230, 118)
            textSize = 28f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(speedUnit.label, centerX, centerY + 65f, unitPaint)

        // Right Block: GEAR & COORDINATES
        val gearBoxPaint = Paint().apply {
            color = Color.argb(230, 24, 31, 44)
            style = Paint.Style.FILL
        }
        val gearRect = RectF(centerX + 190f, centerY - 80f, centerX + 310f, centerY + 40f)
        canvas.drawRoundRect(gearRect, 16f, 16f, gearBoxPaint)

        val gearNumPaint = Paint().apply {
            color = Color.WHITE
            textSize = 65f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText("4", centerX + 250f, centerY - 15f, gearNumPaint)

        val gearLabelPaint = Paint().apply {
            color = Color.argb(200, 148, 163, 184)
            textSize = 20f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("GEAR", centerX + 250f, centerY + 20f, gearLabelPaint)

        // Lat / Lon / Alt
        val coordPaint = Paint().apply {
            color = Color.argb(230, 0, 229, 255)
            textSize = 20f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText(String.format("LAT %.4f", session.endLat), centerX + 190f, centerY + 70f, coordPaint)
        canvas.drawText(String.format("LON %.4f", session.endLon), centerX + 190f, centerY + 95f, coordPaint)
        canvas.drawText("ALT 682m", centerX + 190f, centerY + 120f, coordPaint)

        // 3. Draw Bottom Banner: Accurate Date & Watermark
        val bottomBannerPaint = Paint().apply {
            color = Color.argb(200, 10, 14, 20)
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, height - 44f, width.toFloat(), height.toFloat(), bottomBannerPaint)

        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(session.timestamp))
        val bannerTextPaint = Paint().apply {
            color = Color.WHITE
            textSize = 24f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText(dateStr, 40f, height - 14f, bannerTextPaint)

        val brandPaint = Paint().apply {
            color = Color.argb(255, 0, 230, 118)
            textSize = 24f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("RIDERCAM HUD • SPEED & TELEMETRY OVERLAY", width - 640f, height - 14f, brandPaint)

        return bitmap
    }
}
