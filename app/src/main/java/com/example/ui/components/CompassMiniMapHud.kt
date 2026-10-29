package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GpsTelemetry
import com.example.data.model.LatLngPoint
import com.example.ui.theme.CockpitBlack
import com.example.ui.theme.CockpitBorder
import com.example.ui.theme.CockpitCard
import com.example.ui.theme.HudMuted
import com.example.ui.theme.HudWhite
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed

@Composable
fun CompassMiniMapHud(
    telemetry: GpsTelemetry,
    modifier: Modifier = Modifier
) {
    val animatedBearing by animateFloatAsState(
        targetValue = telemetry.bearingDegrees,
        label = "bearing"
    )

    Column(
        modifier = modifier
            .background(CockpitBlack.copy(alpha = 0.82f), RoundedCornerShape(20.dp))
            .border(1.dp, CockpitBorder, RoundedCornerShape(20.dp))
            .padding(10.dp)
            .testTag("compass_minimap_hud"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Compass & Radar Box
        Box(
            modifier = Modifier
                .size(110.dp)
                .background(CockpitCard.copy(alpha = 0.9f), CircleShape)
                .border(1.5.dp, CockpitBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // Radar concentric rings & route trail
            RadarRouteCanvas(
                points = telemetry.locationHistory,
                bearing = animatedBearing,
                modifier = Modifier.fillMaxSize()
            )

            // North Pointer Needle & Compass Indicator
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // North indicator
                Text(
                    text = "N",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = NeonRed
                )
                // Center Motorcycle Heading Pointer
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = "Bearing",
                    tint = NeonGreen,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(animatedBearing)
                )
                Text(
                    text = "S",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = HudMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Distance Badge
        Row(
            modifier = Modifier
                .background(NeonGreen.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                .border(1.dp, NeonGreen.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(NeonGreen, CircleShape)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = String.format("%.1f km", telemetry.distanceKm),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = NeonGreen,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Satellite & Altitude stats
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.width(110.dp)
        ) {
            Text(
                text = "ALT ${telemetry.altitudeMeters.toInt()}m",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = HudMuted,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "SAT ${telemetry.satellitesCount}",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = if (telemetry.isGpsFixed) NeonCyan else HudMuted,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun RadarRouteCanvas(
    points: List<LatLngPoint>,
    bearing: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.padding(10.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.minDimension / 2f

        // Draw concentric radar circles
        drawCircle(
            color = Color(0xFF1E293B).copy(alpha = 0.6f),
            radius = maxRadius * 0.4f,
            center = center,
            style = Stroke(1.dp.toPx())
        )
        drawCircle(
            color = Color(0xFF1E293B).copy(alpha = 0.6f),
            radius = maxRadius * 0.75f,
            center = center,
            style = Stroke(1.dp.toPx())
        )

        // Draw crosshairs
        drawLine(
            color = Color(0xFF1E293B).copy(alpha = 0.4f),
            start = Offset(center.x, 0f),
            end = Offset(center.x, size.height),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = Color(0xFF1E293B).copy(alpha = 0.4f),
            start = Offset(0f, center.y),
            end = Offset(size.width, center.y),
            strokeWidth = 1.dp.toPx()
        )

        // Draw dynamic live route breadcrumb trail
        if (points.size >= 2) {
            val lastPt = points.last()
            val path = Path()

            val routeOffsets = points.takeLast(25).map { pt ->
                // Delta in degrees scaled to mini-map radar
                val dLon = (pt.lon - lastPt.lon) * 12000f
                val dLat = (pt.lat - lastPt.lat) * 12000f
                Offset(
                    (center.x + dLon.toFloat()).coerceIn(4f, size.width - 4f),
                    (center.y - dLat.toFloat()).coerceIn(4f, size.height - 4f)
                )
            }

            path.moveTo(routeOffsets.first().x, routeOffsets.first().y)
            for (i in 1 until routeOffsets.size) {
                path.lineTo(routeOffsets[i].x, routeOffsets[i].y)
            }

            drawPath(
                path = path,
                color = NeonGreen,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )

            // Glow line effect
            drawPath(
                path = path,
                color = NeonGreen.copy(alpha = 0.35f),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}
