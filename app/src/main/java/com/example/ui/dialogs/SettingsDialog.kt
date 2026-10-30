package com.example.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.OverlayConfig
import com.example.data.model.RecordingSettings
import com.example.data.model.SpeedUnit
import com.example.data.model.VideoResolution
import com.example.ui.theme.CockpitBlack
import com.example.ui.theme.CockpitBorder
import com.example.ui.theme.CockpitCard
import com.example.ui.theme.CockpitSurface
import com.example.ui.theme.HudMuted
import com.example.ui.theme.HudWhite
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonYellow

@Composable
fun SettingsDialog(
    settings: RecordingSettings,
    overlayConfig: OverlayConfig,
    isSimulating: Boolean,
    onSettingsChange: (RecordingSettings) -> Unit,
    onOverlayConfigChange: (OverlayConfig) -> Unit,
    onToggleSimulation: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .testTag("settings_dialog"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CockpitSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CockpitBorder)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = NeonGreen,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "PENGATURAN RIDERCAM",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            color = HudWhite
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("btn_close_settings")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = HudMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Section 1: Simulasi / Demo Mode
                SettingsSectionHeader("SIMULASI PENGUJIAN DESK/EMULATOR", Icons.Default.DirectionsBike)
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Simulasi Perjalanan Motor",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = HudWhite
                            )
                            Text(
                                text = "Simulasikan kecepatan motor, akselerasi, gigi & GPS tanpa harus mengendarai motor nyata",
                                fontSize = 11.sp,
                                color = HudMuted
                            )
                        }
                        Switch(
                            checked = isSimulating,
                            onCheckedChange = { onToggleSimulation() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = NeonGreen
                            ),
                            modifier = Modifier.testTag("switch_simulation")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Section 2: Video & Manual Record
                SettingsSectionHeader("PENGATURAN REKAMAN", Icons.Default.Videocam)
                SettingsCard {
                    Text(
                        text = "Resolusi Video",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = HudWhite
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    VideoResolution.values().forEach { res ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSettingsChange(settings.copy(resolution = res)) }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = settings.resolution == res,
                                onClick = { onSettingsChange(settings.copy(resolution = res)) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = NeonGreen,
                                    unselectedColor = HudMuted
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = res.label,
                                fontSize = 12.sp,
                                color = if (settings.resolution == res) HudWhite else HudMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Frame rate
                    Text(
                        text = "Frame Rate Target",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = HudWhite
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        listOf(60, 30).forEach { fps ->
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (settings.targetFps == fps) NeonGreen else CockpitBlack,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(1.dp, CockpitBorder, RoundedCornerShape(8.dp))
                                    .clickable { onSettingsChange(settings.copy(targetFps = fps)) }
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "$fps FPS",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (settings.targetFps == fps) Color.Black else HudWhite
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Lokasi Penyimpanan Video:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = HudMuted
                    )
                    Text(
                        text = "0://Movies/RiderCam/ (Memori Internal)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Section 3: Mode Malam
                SettingsSectionHeader("MODE MALAM (LOW LIGHT BOOST)", Icons.Default.Nightlight)
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Tingkatkan Kualitas Malam",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = HudWhite
                            )
                            Text(
                                text = "Kompensasi eksposur tinggi untuk rekaman malam lebih terang dan jernih",
                                fontSize = 10.sp,
                                color = HudMuted
                            )
                        }
                        Switch(
                            checked = settings.nightModeEnabled,
                            onCheckedChange = { onSettingsChange(settings.copy(nightModeEnabled = it)) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = NeonCyan
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Section 4: Overlay & Tampilan
                SettingsSectionHeader("OVERLAY & TAMPILAN", Icons.Default.Visibility)
                SettingsCard {
                    OverlayToggleItem("Kecepatan Digital (Speedometer)", overlayConfig.showSpeedometer) {
                        onOverlayConfigChange(overlayConfig.copy(showSpeedometer = it))
                    }
                    OverlayToggleItem("Peta GPS & Rute Radar", overlayConfig.showGpsMap) {
                        onOverlayConfigChange(overlayConfig.copy(showGpsMap = it))
                    }
                    OverlayToggleItem("Koordinat GPS & Ketinggian", overlayConfig.showCoordinates) {
                        onOverlayConfigChange(overlayConfig.copy(showCoordinates = it))
                    }
                    OverlayToggleItem("Data Gear / RPM (OBD-II)", overlayConfig.showGearRpm) {
                        onOverlayConfigChange(overlayConfig.copy(showGearRpm = it))
                    }
                    OverlayToggleItem("Tanggal & Waktu Real-Time", overlayConfig.showDateTime) {
                        onOverlayConfigChange(overlayConfig.copy(showDateTime = it))
                    }
                    OverlayToggleItem("Watermark Logo RiderCam", overlayConfig.showWatermark) {
                        onOverlayConfigChange(overlayConfig.copy(showWatermark = it))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = NeonYellow,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = NeonYellow,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(CockpitCard, RoundedCornerShape(12.dp))
            .border(1.dp, CockpitBorder, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Column {
            content()
        }
    }
}

@Composable
private fun OverlayToggleItem(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = HudWhite
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = NeonGreen
            )
        )
    }
}
