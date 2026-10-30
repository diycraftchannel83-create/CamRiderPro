package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GpsTelemetry
import com.example.data.model.RecordingSettings
import com.example.ui.theme.CockpitBlack
import com.example.ui.theme.CockpitBorder
import com.example.ui.theme.CockpitCard
import com.example.ui.theme.HudMuted
import com.example.ui.theme.HudWhite
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.NeonYellow
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TelemetryOverlayHeader(
    isRecording: Boolean,
    recordingDurationSeconds: Long,
    settings: RecordingSettings,
    telemetry: GpsTelemetry,
    isNightBoostActive: Boolean,
    modifier: Modifier = Modifier
) {
    var currentTimeString by remember {
        mutableStateOf(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentTimeString = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            delay(1000)
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "rec_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        // Left: Recording Pill & Tech Specs
        Column {
            Row(
                modifier = Modifier
                    .background(
                        color = CockpitBlack.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .border(
                        1.dp,
                        if (isRecording) NeonRed.copy(alpha = 0.7f) else CockpitBorder,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .testTag("recording_status_pill"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRecording) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .alpha(pulseAlpha)
                            .background(NeonRed, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "REC",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        color = NeonRed,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    val hours = recordingDurationSeconds / 3600
                    val minutes = (recordingDurationSeconds % 3600) / 60
                    val seconds = recordingDurationSeconds % 60
                    val timeFormatted = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                    Text(
                        text = timeFormatted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = HudWhite,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(NeonGreen, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "VIDEO RES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonGreen
                    )
                }
            }

            Spacer(modifier = Modifier.size(3.dp))

            // Tech specs info (Resolution, FPS, Codec, Night Boost)
            Row(
                modifier = Modifier
                    .background(CockpitCard.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${settings.resolution.label.split(" ").first()} ${settings.targetFps}FPS H.265",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = HudWhite,
                    fontFamily = FontFamily.Monospace
                )
                if (isNightBoostActive) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Bedtime,
                        contentDescription = "Night Boost",
                        tint = NeonCyan,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = " NIGHT HDR",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        color = NeonCyan
                    )
                }
            }
        }

        // Right: GPS, Battery, Clock HUD
        Row(
            modifier = Modifier
                .background(
                    color = CockpitBlack.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(12.dp)
                )
                .border(1.dp, CockpitBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // GPS Status
            Icon(
                imageVector = if (telemetry.isGpsFixed) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                contentDescription = "GPS Status",
                tint = if (telemetry.isGpsFixed) NeonGreen else NeonYellow,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "GPS",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (telemetry.isGpsFixed) NeonGreen else NeonYellow
            )

            Spacer(modifier = Modifier.width(10.dp))

            // Battery Mock
            Icon(
                imageVector = Icons.Default.BatteryFull,
                contentDescription = "Battery",
                tint = NeonGreen,
                modifier = Modifier.size(15.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            // Clock
            Text(
                text = currentTimeString,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = HudWhite,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
