package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GpsTelemetry
import com.example.data.model.OverlayConfig
import com.example.data.model.SpeedUnit
import com.example.ui.theme.CockpitBlack
import com.example.ui.theme.HudMuted
import com.example.ui.theme.HudWhite
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TelemetryVideoBannerOverlay(
    telemetry: GpsTelemetry,
    speedUnit: SpeedUnit,
    overlayConfig: OverlayConfig,
    modifier: Modifier = Modifier
) {
    var dateTimeFormatted by remember {
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
    }

    LaunchedEffect(Unit) {
        while (true) {
            dateTimeFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            delay(1000)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CockpitBlack.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("telemetry_video_banner"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Date & Time + GPS Coordinates
        Column {
            if (overlayConfig.showDateTime) {
                Text(
                    text = dateTimeFormatted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = HudWhite,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (overlayConfig.showCoordinates) {
                val coordsText = String.format(
                    "LAT: %.5f  LON: %.5f  ALT: %dm",
                    telemetry.latitude,
                    telemetry.longitude,
                    telemetry.altitudeMeters.toInt()
                )
                Text(
                    text = coordsText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NeonCyan,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Right: Branding Watermark & Speed Summary
        Column(horizontalAlignment = Alignment.End) {
            if (overlayConfig.showWatermark) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Rider",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        color = HudWhite
                    )
                    Text(
                        text = "Cam",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        color = NeonGreen
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "HUD",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = HudMuted
                    )
                }
            }
            val displaySpeed = telemetry.speedKmh * speedUnit.multiplierFromKmh
            Text(
                text = "${displaySpeed.toInt()} ${speedUnit.label}",
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = NeonGreen,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
