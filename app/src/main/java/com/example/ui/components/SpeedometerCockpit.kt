package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GpsTelemetry
import com.example.data.model.SpeedUnit
import com.example.ui.theme.CockpitBlack
import com.example.ui.theme.CockpitBorder
import com.example.ui.theme.CockpitCard
import com.example.ui.theme.HudMuted
import com.example.ui.theme.HudWhite
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.NeonYellow
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SpeedometerCockpit(
    telemetry: GpsTelemetry,
    speedUnit: SpeedUnit,
    showGearRpm: Boolean = true,
    isLandscape: Boolean = true,
    modifier: Modifier = Modifier
) {
    val displaySpeed = telemetry.speedKmh * speedUnit.multiplierFromKmh
    val maxDisplaySpeed = telemetry.maxSpeedKmh * speedUnit.multiplierFromKmh
    val avgDisplaySpeed = telemetry.avgSpeedKmh * speedUnit.multiplierFromKmh

    val animatedSpeed by animateFloatAsState(
        targetValue = displaySpeed,
        animationSpec = tween(durationMillis = 300),
        label = "speed"
    )

    if (isLandscape) {
        // Landscape layout as shown in main rider cockpit
        Row(
            modifier = modifier
                .background(
                    color = CockpitBlack.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(24.dp)
                )
                .border(1.dp, CockpitBorder, RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Left Stats Card: Distance & Max Speed
            Column(
                modifier = Modifier
                    .background(CockpitCard.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
                    .border(1.dp, CockpitBorder.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "DISTANCE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = HudMuted,
                    letterSpacing = 1.sp
                )
                Text(
                    text = String.format("%.1f km", telemetry.distanceKm),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = HudWhite,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "MAX SPEED",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = HudMuted,
                    letterSpacing = 1.sp
                )
                Text(
                    text = String.format("%d %s", maxDisplaySpeed.toInt(), speedUnit.label),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = NeonYellow,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Center Speedometer Gauge Arc & Digital Readout
            SpeedometerGauge(
                currentSpeed = animatedSpeed,
                unitLabel = speedUnit.label,
                gaugeSize = 140.dp
            )

            Spacer(modifier = Modifier.width(14.dp))

            // Right: Gear Indicator & Lat/Lon/Alt telemetry
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showGearRpm) {
                    Column(
                        modifier = Modifier
                            .background(CockpitCard.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
                            .border(1.dp, CockpitBorder.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = telemetry.gear,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = if (telemetry.gear == "N") NeonGreen else HudWhite,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "GEAR",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = HudMuted,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }

                // Coordinates info block
                Column(
                    modifier = Modifier.padding(start = 4.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    TelemetryMiniRow("LAT", String.format("%.4f", telemetry.latitude))
                    TelemetryMiniRow("LON", String.format("%.4f", telemetry.longitude))
                    TelemetryMiniRow("ALT", "${telemetry.altitudeMeters.toInt()} m")
                }
            }
        }
    } else {
        // Portrait Layout
        Column(
            modifier = modifier
                .background(
                    color = CockpitBlack.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(20.dp)
                )
                .border(1.dp, CockpitBorder, RoundedCornerShape(20.dp))
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                SpeedometerGauge(
                    currentSpeed = animatedSpeed,
                    unitLabel = speedUnit.label,
                    gaugeSize = 130.dp
                )

                if (showGearRpm) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(
                        modifier = Modifier
                            .background(CockpitCard.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                            .border(1.dp, CockpitBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = telemetry.gear,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            color = if (telemetry.gear == "N") NeonGreen else HudWhite,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "GEAR",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = HudMuted
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Trip Stats Row: DIST, MAX, AVG
            Row(
                modifier = Modifier
                    .background(CockpitCard.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatTriple("DIST", String.format("%.1f km", telemetry.distanceKm))
                Text("•", color = CockpitBorder, modifier = Modifier.padding(horizontal = 6.dp))
                StatTriple("MAX", String.format("%d %s", maxDisplaySpeed.toInt(), speedUnit.label))
                Text("•", color = CockpitBorder, modifier = Modifier.padding(horizontal = 6.dp))
                StatTriple("AVG", String.format("%d %s", avgDisplaySpeed.toInt(), speedUnit.label))
            }
        }
    }
}

@Composable
fun SpeedometerGauge(
    currentSpeed: Float,
    unitLabel: String,
    gaugeSize: androidx.compose.ui.unit.Dp,
    maxGaugeSpeed: Float = 160f
) {
    Box(
        modifier = Modifier
            .size(gaugeSize)
            .testTag("speedometer_gauge"),
        contentAlignment = Alignment.Center
    ) {
        val sweepFraction = (currentSpeed / maxGaugeSpeed).coerceIn(0f, 1f)

        Canvas(modifier = Modifier.size(gaugeSize)) {
            val strokeWidth = 10.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val arcSize = Size(diameter, diameter)
            val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)

            val startAngle = 140f
            val totalSweep = 260f

            // Background Track Arc
            drawArc(
                color = Color(0xFF1E293B),
                startAngle = startAngle,
                sweepAngle = totalSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Dynamic Active Speed Arc with Neon Gradient
            if (sweepFraction > 0.01f) {
                val activeSweep = totalSweep * sweepFraction
                val gradientBrush = Brush.sweepGradient(
                    0.0f to NeonGreen,
                    0.6f to NeonYellow,
                    0.85f to NeonRed,
                    1.0f to NeonRed
                )

                drawArc(
                    brush = gradientBrush,
                    startAngle = startAngle,
                    sweepAngle = activeSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth + 1.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Outer Tick marks
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = diameter / 2f
            val tickCount = 9
            for (i in 0..tickCount) {
                val angleDeg = startAngle + (totalSweep * (i.toFloat() / tickCount))
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val outerPoint = Offset(
                    (center.x + (radius + 2.dp.toPx()) * cos(angleRad)).toFloat(),
                    (center.y + (radius + 2.dp.toPx()) * sin(angleRad)).toFloat()
                )
                val innerPoint = Offset(
                    (center.x + (radius - 5.dp.toPx()) * cos(angleRad)).toFloat(),
                    (center.y + (radius - 5.dp.toPx()) * sin(angleRad)).toFloat()
                )
                val isTickActive = (i.toFloat() / tickCount) <= sweepFraction
                drawLine(
                    color = if (isTickActive) NeonGreen else Color(0xFF334155),
                    start = innerPoint,
                    end = outerPoint,
                    strokeWidth = 2.dp.toPx()
                )
            }
        }

        // Center Digital Speed Text
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "${currentSpeed.toInt()}",
                fontSize = if (gaugeSize > 135.dp) 44.sp else 36.sp,
                fontWeight = FontWeight.Black,
                color = HudWhite,
                fontFamily = FontFamily.Monospace,
                lineHeight = 44.sp
            )
            Text(
                text = unitLabel,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = NeonGreen,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
private fun TelemetryMiniRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = HudMuted,
            modifier = Modifier.width(30.dp)
        )
        Text(
            text = value,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = HudWhite,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun StatTriple(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = HudMuted
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = HudWhite,
            fontFamily = FontFamily.Monospace
        )
    }
}
