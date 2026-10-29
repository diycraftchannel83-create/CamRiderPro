package com.example.ui.dialogs

import android.content.Intent
import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.example.data.model.RideSession
import com.example.export.ExportState
import com.example.ui.theme.CockpitBlack
import com.example.ui.theme.CockpitBorder
import com.example.ui.theme.CockpitCard
import com.example.ui.theme.CockpitSurface
import com.example.ui.theme.HudMuted
import com.example.ui.theme.HudWhite
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.NeonYellow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RideHistoryDialog(
    rides: List<RideSession>,
    exportState: ExportState,
    onDeleteRide: (RideSession) -> Unit,
    onExportWithSpeedometer: (RideSession) -> Unit,
    onResetExportState: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedVideoForPlayback by remember { mutableStateOf<RideSession?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .testTag("ride_history_dialog"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CockpitSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CockpitBorder)
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = NeonGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "DATA & ANALISIS PERFORMA",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            color = HudWhite
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = HudMuted
                        )
                    }
                }

                // If export is in progress, show Export Status Card
                AnimatedVisibility(visible = exportState !is ExportState.Idle) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .background(CockpitBlack, RoundedCornerShape(12.dp))
                            .border(1.dp, NeonGreen, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        when (exportState) {
                            is ExportState.Exporting -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = NeonGreen,
                                        strokeWidth = 2.5.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Mengekspor video dengan spedometer...",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = HudWhite
                                        )
                                        Text(
                                            text = "Progress: ${exportState.progressPercent}%",
                                            fontSize = 11.sp,
                                            color = NeonGreen,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                            is ExportState.Success -> {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = NeonGreen,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Video Berhasil Diekspor dengan Spedometer!",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = NeonGreen
                                        )
                                    }
                                    Text(
                                        text = exportState.outputDisplayPath,
                                        fontSize = 11.sp,
                                        color = NeonCyan,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Button(
                                            onClick = {
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "video/mp4"
                                                    putExtra(Intent.EXTRA_STREAM, exportState.outputUri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(
                                                    Intent.createChooser(shareIntent, "Bagikan Video RiderCam")
                                                )
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NeonGreen,
                                                contentColor = Color.Black
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.padding(end = 6.dp)
                                        ) {
                                            Text("Bagikan Video", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Button(
                                            onClick = onResetExportState,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = CockpitCard,
                                                contentColor = HudWhite
                                            ),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Tutup", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                            is ExportState.Error -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            tint = NeonRed,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = exportState.errorMessage,
                                            fontSize = 11.sp,
                                            color = NeonRed
                                        )
                                    }
                                    Button(
                                        onClick = onResetExportState,
                                        colors = ButtonDefaults.buttonColors(containerColor = CockpitCard),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text("OK", fontSize = 10.sp)
                                    }
                                }
                            }
                            is ExportState.Idle -> {}
                        }
                    }
                }

                // If video playback is selected, show embedded video player preview with Speedometer overlay!
                selectedVideoForPlayback?.let { session ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(Color.Black, RoundedCornerShape(12.dp))
                            .border(1.5.dp, NeonGreen, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    val videoUri = if (session.videoPath.startsWith("content://")) {
                                        Uri.parse(session.videoPath)
                                    } else {
                                        Uri.fromFile(File(session.videoPath))
                                    }
                                    setVideoURI(videoUri)
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = true
                                        start()
                                    }
                                }
                            },
                            modifier = Modifier.matchParentSize()
                        )

                        // Live Speedometer Cockpit HUD burned onto player preview
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        ) {
                            // Top left Playback badge
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .background(CockpitBlack.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "● PLAYBACK HUD 4K",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonGreen
                                )
                            }

                            // Bottom Cockpit Gauge Overlay on video
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .background(CockpitBlack.copy(alpha = 0.88f), RoundedCornerShape(10.dp))
                                    .border(1.dp, NeonGreen.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val cruisingSpeed = (session.maxSpeedKmh * 0.72f).toInt()
                                    Text(
                                        text = "$cruisingSpeed",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Black,
                                        color = HudWhite,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text("km/h", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = NeonGreen)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "GEAR 4  •  MAX ${session.maxSpeedKmh.toInt()} km/h",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NeonYellow
                                    )
                                    Text(
                                        text = String.format("LAT %.4f LON %.4f", session.endLat, session.endLon),
                                        fontSize = 8.sp,
                                        color = NeonCyan,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = session.title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonGreen
                        )
                        Button(
                            onClick = { selectedVideoForPlayback = null },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CockpitCard,
                                contentColor = HudWhite
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Tutup Pemutar", fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (rides.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.DirectionsBike,
                                contentDescription = null,
                                tint = HudMuted,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Belum ada rekaman perjalanan",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = HudWhite
                            )
                            Text(
                                text = "Tersimpan otomatis di 0://Movies/RiderCam/",
                                fontSize = 11.sp,
                                color = HudMuted
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(350.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(rides, key = { it.id }) { ride ->
                            RideSessionItem(
                                ride = ride,
                                onPlay = { selectedVideoForPlayback = ride },
                                onExport = { onExportWithSpeedometer(ride) },
                                onDelete = { onDeleteRide(ride) },
                                onShare = {
                                    try {
                                        val shareUri = if (ride.videoPath.startsWith("content://")) {
                                            Uri.parse(ride.videoPath)
                                        } else {
                                            val file = File(ride.videoPath)
                                            if (file.exists()) {
                                                FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    file
                                                )
                                            } else {
                                                Uri.parse(ride.videoPath)
                                            }
                                        }
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "video/*"
                                            putExtra(Intent.EXTRA_STREAM, shareUri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(shareIntent, "Bagikan Video RiderCam")
                                        )
                                    } catch (_: Exception) {
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RideSessionItem(
    ride: RideSession,
    onPlay: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    val dateStr = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ride.timestamp))
    val hours = ride.durationSeconds / 3600
    val minutes = (ride.durationSeconds % 3600) / 60
    val seconds = ride.durationSeconds % 60
    val durationText = String.format("%02d:%02d:%02d", hours, minutes, seconds)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(CockpitCard, RoundedCornerShape(14.dp))
            .border(1.dp, CockpitBorder, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = ride.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = HudWhite
                    )
                    Text(
                        text = dateStr,
                        fontSize = 11.sp,
                        color = HudMuted,
                        fontFamily = FontFamily.Monospace
                    )
                    if (ride.fileSizeFormatted.isNotEmpty()) {
                        Text(
                            text = "📁 ${ride.fileSizeFormatted}",
                            fontSize = 10.sp,
                            color = NeonCyan,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                Row {
                    IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = NeonCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = NeonRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Metrics row: Duration, Distance, Max Speed, Avg Speed
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CockpitBlack, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricItem("DURASI", durationText, NeonGreen)
                MetricItem("JARAK", String.format("%.1f km", ride.distanceKm), HudWhite)
                MetricItem("MAX", String.format("%.0f km/h", ride.maxSpeedKmh), NeonYellow)
                MetricItem("AVG", String.format("%.0f km/h", ride.avgSpeedKmh), NeonCyan)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Primary Actions: Play Video & Export Video with Speedometer Overlay
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPlay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CockpitBlack,
                        contentColor = HudWhite
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CockpitBorder),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = NeonGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Putar Video", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onExport,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonGreen,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1.3f).testTag("btn_export_speedometer")
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Export Speedometer",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Ekspor + Spedometer",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = HudMuted)
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            color = valueColor,
            fontFamily = FontFamily.Monospace
        )
    }
}
