package com.example.ui.dialogs

import android.content.Intent
import android.net.Uri
import android.widget.VideoView
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
    onDeleteRide: (RideSession) -> Unit,
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

            // Playback action only. The saved recording already contains its live HUD.
            Button(
                onClick = onPlay,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CockpitBlack,
                    contentColor = HudWhite
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, CockpitBorder),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
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
