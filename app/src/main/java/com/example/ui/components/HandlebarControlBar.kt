package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CockpitBlack
import com.example.ui.theme.CockpitBorder
import com.example.ui.theme.CockpitCard
import com.example.ui.theme.HudMuted
import com.example.ui.theme.HudWhite
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.NeonYellow

@Composable
fun HandlebarControlBar(
    isRecording: Boolean,
    isAudioEnabled: Boolean,
    isNightBoostActive: Boolean,
    isTorchActive: Boolean,
    onRecordToggle: () -> Unit,
    onCameraSwitch: () -> Unit,
    onAudioToggle: () -> Unit,
    onNightToggle: () -> Unit,
    onTorchToggle: () -> Unit,
    onLockScreen: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(
                color = CockpitBlack.copy(alpha = 0.16f),
                shape = RoundedCornerShape(20.dp)
            )
            .border(1.dp, NeonCyan.copy(alpha = 0.30f), RoundedCornerShape(28.dp))
            .padding(vertical = 7.dp, horizontal = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // Record Action Big Button
        RecordActionButton(
            isRecording = isRecording,
            onClick = onRecordToggle
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Switch Camera
        HandlebarIconButton(
            icon = Icons.Default.Cameraswitch,
            contentDescription = "Switch Camera",
            testTag = "btn_switch_camera",
            onClick = onCameraSwitch
        )

        // Audio / Mic toggle
        HandlebarIconButton(
            icon = if (isAudioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
            contentDescription = "Toggle Audio",
            testTag = "btn_toggle_audio",
            tint = if (isAudioEnabled) NeonGreen else HudMuted,
            onClick = onAudioToggle
        )

        // Night Mode Boost
        HandlebarIconButton(
            icon = Icons.Default.Bedtime,
            contentDescription = "Night Mode Boost",
            testTag = "btn_toggle_night_mode",
            tint = if (isNightBoostActive) NeonCyan else HudMuted,
            onClick = onNightToggle
        )

        // Screen Lock for motorcycle mount protection
        HandlebarIconButton(
            icon = Icons.Default.Lock,
            contentDescription = "Lock Screen Guard",
            testTag = "btn_lock_screen",
            tint = NeonYellow,
            onClick = onLockScreen
        )

        // Flash / Torch toggle
        HandlebarIconButton(
            icon = Icons.Default.FlashOn,
            contentDescription = "Torch Light",
            testTag = "btn_toggle_torch",
            tint = if (isTorchActive) NeonYellow else HudMuted,
            onClick = onTorchToggle
        )

        // Ride History / Gallery
        HandlebarIconButton(
            icon = Icons.Default.History,
            contentDescription = "Ride History",
            testTag = "btn_open_history",
            onClick = onOpenHistory
        )

        // Settings
        HandlebarIconButton(
            icon = Icons.Default.Settings,
            contentDescription = "Settings",
            testTag = "btn_open_settings",
            onClick = onOpenSettings
        )
    }
}

@Composable
private fun RecordActionButton(
    isRecording: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isRecording) NeonRed else NeonGreen,
        label = "rec_bg"
    )

    Box(
        modifier = Modifier
            .size(50.dp)
            .background(CockpitBlack.copy(alpha = 0.32f), CircleShape)
            .border(2.dp, if (isRecording) NeonRed else NeonGreen, CircleShape)
            .clickable { onClick() }
            .testTag("btn_record_toggle"),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
            tint = if (isRecording) NeonRed else NeonGreen,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun HandlebarIconButton(
    icon: ImageVector,
    contentDescription: String,
    testTag: String,
    tint: Color = HudWhite,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .background(CockpitBlack.copy(alpha = 0.28f), CircleShape)
            .border(1.dp, NeonCyan.copy(alpha = 0.48f), CircleShape)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}
