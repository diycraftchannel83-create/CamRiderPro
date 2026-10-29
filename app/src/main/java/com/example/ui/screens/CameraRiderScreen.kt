package com.example.ui.screens

import android.Manifest
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.RiderCamViewModel
import com.example.ui.components.CompassMiniMapHud
import com.example.ui.components.HandlebarControlBar
import com.example.ui.components.ScreenLockOverlay
import com.example.ui.components.SpeedometerCockpit
import com.example.ui.components.TelemetryOverlayHeader
import com.example.ui.components.TelemetryVideoBannerOverlay
import com.example.ui.dialogs.RideHistoryDialog
import com.example.ui.dialogs.SettingsDialog
import com.example.ui.theme.CockpitBlack
import com.example.ui.theme.CockpitBorder
import com.example.ui.theme.CockpitCard
import com.example.ui.theme.CockpitSurface
import com.example.ui.theme.HudMuted
import com.example.ui.theme.HudWhite
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonYellow
import kotlinx.coroutines.delay

@Composable
fun CameraRiderScreen(
    viewModel: RiderCamViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val telemetry by viewModel.telemetry.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDuration by viewModel.recordingDurationSeconds.collectAsState()
    val isNightBoostActive by viewModel.isNightBoostActive.collectAsState()
    val isTorchActive by viewModel.isTorchActive.collectAsState()
    val isScreenLocked by viewModel.isScreenLocked.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val overlayConfig by viewModel.overlayConfig.collectAsState()
    val rides by viewModel.rides.collectAsState()
    val bannerMessage by viewModel.bannerMessage.collectAsState()
    val isSimulating by viewModel.isSimulating.collectAsState()
    val exportState by viewModel.exportState.collectAsState()

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    // Permissions check & request
    var permissionsGranted by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraOk = results[Manifest.permission.CAMERA] == true
        val locationOk = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        permissionsGranted = cameraOk && locationOk
        if (permissionsGranted) {
            viewModel.startGpsTracking()
            previewViewRef?.let { pv ->
                viewModel.cameraManager.bindCamera(lifecycleOwner, pv, settings.resolution)
            }
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // Auto-dismiss banner messages after 3 seconds
    LaunchedEffect(bannerMessage) {
        if (bannerMessage != null) {
            delay(3200)
            viewModel.clearBanner()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CockpitBlack)
    ) {
        // 1. Live Camera Preview Layer
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewViewRef = this
                    if (permissionsGranted) {
                        viewModel.cameraManager.bindCamera(lifecycleOwner, this, settings.resolution)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Shared visual layer: the supplied CamRider artwork is also composited into exported video.
        Image(
            painter = painterResource(id = com.example.R.drawable.camrider_flyer_overlay),
            contentDescription = "CamRider recording overlay",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            alpha = 0.88f
        )

        // Subtle Night Boost Contrast Filter overlay when active
        if (isNightBoostActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x1800E5FF))
            )
        }

        // 2. Telemetry HUD Layer
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            // Top HUD Status Bar
            TelemetryOverlayHeader(
                isRecording = isRecording,
                recordingDurationSeconds = recordingDuration,
                settings = settings,
                telemetry = telemetry,
                isNightBoostActive = isNightBoostActive,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            if (isLandscape) {
                // LANDSCAPE MOTORCYCLE COCKPIT LAYOUT
                // Left: Handlebar Quick Action Bar
                HandlebarControlBar(
                    isRecording = isRecording,
                    isAudioEnabled = settings.audioEnabled,
                    isNightBoostActive = isNightBoostActive,
                    isTorchActive = isTorchActive,
                    onRecordToggle = { viewModel.toggleRecord() },
                    onCameraSwitch = {
                        previewViewRef?.let { pv ->
                            viewModel.cameraManager.switchCamera(lifecycleOwner, pv, settings.resolution)
                        }
                    },
                    onAudioToggle = { viewModel.toggleAudio() },
                    onNightToggle = { viewModel.toggleNightBoost() },
                    onTorchToggle = { viewModel.toggleTorch() },
                    onLockScreen = { viewModel.lockScreen() },
                    onOpenSettings = { showSettingsDialog = true },
                    onOpenHistory = { showHistoryDialog = true },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp)
                )

                // Top Right: Compass & Mini Route Map Radar
                if (overlayConfig.showGpsMap) {
                    CompassMiniMapHud(
                        telemetry = telemetry,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 54.dp, end = 12.dp)
                    )
                }

                // Bottom Center: Speedometer Cockpit Gauge
                if (overlayConfig.showSpeedometer) {
                    SpeedometerCockpit(
                        telemetry = telemetry,
                        speedUnit = settings.speedUnit,
                        showGearRpm = overlayConfig.showGearRpm,
                        isLandscape = true,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 34.dp)
                    )
                }

                // Bottom Edge: Accurate Date, Coordinates & Watermark banner
                TelemetryVideoBannerOverlay(
                    telemetry = telemetry,
                    speedUnit = settings.speedUnit,
                    overlayConfig = overlayConfig,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            } else {
                // PORTRAIT COCKPIT LAYOUT
                // Right Action Bar with Record button in clear space
                HandlebarControlBar(
                    isRecording = isRecording,
                    isAudioEnabled = settings.audioEnabled,
                    isNightBoostActive = isNightBoostActive,
                    isTorchActive = isTorchActive,
                    onRecordToggle = { viewModel.toggleRecord() },
                    onCameraSwitch = {
                        previewViewRef?.let { pv ->
                            viewModel.cameraManager.switchCamera(lifecycleOwner, pv, settings.resolution)
                        }
                    },
                    onAudioToggle = { viewModel.toggleAudio() },
                    onNightToggle = { viewModel.toggleNightBoost() },
                    onTorchToggle = { viewModel.toggleTorch() },
                    onLockScreen = { viewModel.lockScreen() },
                    onOpenSettings = { showSettingsDialog = true },
                    onOpenHistory = { showHistoryDialog = true },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp)
                )

                // Route radar HUD - moved to TopStart (left side) so it NEVER overlaps the record button on the right!
                if (overlayConfig.showGpsMap) {
                    CompassMiniMapHud(
                        telemetry = telemetry,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 60.dp, start = 10.dp)
                    )
                }

                // Bottom Speedometer & Controls
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (overlayConfig.showSpeedometer) {
                        SpeedometerCockpit(
                            telemetry = telemetry,
                            speedUnit = settings.speedUnit,
                            showGearRpm = overlayConfig.showGearRpm,
                            isLandscape = false,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    // Bottom Row: Settings Button shifted to bottom + Storage Path indicator
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { showSettingsDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CockpitCard.copy(alpha = 0.92f),
                                contentColor = HudWhite
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CockpitBorder),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("btn_bottom_settings")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Pengaturan",
                                tint = NeonYellow,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "PENGATURAN",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = HudWhite
                            )
                        }

                        Text(
                            text = "📁 0://Movies/RiderCam/",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonCyan,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }

                    TelemetryVideoBannerOverlay(
                        telemetry = telemetry,
                        speedUnit = settings.speedUnit,
                        overlayConfig = overlayConfig
                    )
                }
            }

            // Floating Notification Toast/Banner
            AnimatedVisibility(
                visible = bannerMessage != null,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 70.dp)
            ) {
                bannerMessage?.let { msg ->
                    Row(
                        modifier = Modifier
                            .background(CockpitSurface.copy(alpha = 0.95f), RoundedCornerShape(14.dp))
                            .border(1.dp, NeonGreen.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = NeonGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = msg,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = HudWhite
                        )
                    }
                }
            }
        }

        // 3. Permission Request Card if not yet granted
        if (!permissionsGranted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CockpitBlack.copy(alpha = 0.88f))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(if (isLandscape) 0.65f else 0.92f)
                        .testTag("permission_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = CockpitSurface),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, NeonGreen)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.DirectionsBike,
                            contentDescription = null,
                            tint = NeonGreen,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "RIDERCAM DASHBOARD",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = HudWhite
                        )
                        Text(
                            text = "Aplikasi dashcam motor membutuhkan izin Kamera, Mikrofon, dan GPS untuk menghitung kecepatan presisi secara real-time dan merekam video.",
                            fontSize = 12.sp,
                            color = HudMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                        Button(
                            onClick = {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.CAMERA,
                                        Manifest.permission.RECORD_AUDIO,
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonGreen,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("btn_grant_permissions")
                        ) {
                            Icon(imageVector = Icons.Default.Security, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "IZINKAN AKSES & MULAI",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }

        // 4. Handlebar Screen Lock Overlay (Touch Protection against rain & vibration)
        ScreenLockOverlay(
            isLocked = isScreenLocked,
            onUnlock = { viewModel.unlockScreen() }
        )

        // 5. Settings Dialog
        if (showSettingsDialog) {
            SettingsDialog(
                settings = settings,
                overlayConfig = overlayConfig,
                isSimulating = isSimulating,
                onSettingsChange = { viewModel.updateSettings(it) },
                onOverlayConfigChange = { viewModel.updateOverlayConfig(it) },
                onToggleSimulation = { viewModel.toggleSimulation() },
                onDismiss = { showSettingsDialog = false }
            )
        }

        // 6. Ride History & Recorded Videos Dialog
        if (showHistoryDialog) {
            RideHistoryDialog(
                rides = rides,
                exportState = exportState,
                onDeleteRide = { viewModel.deleteRide(it) },
                onExportWithSpeedometer = { viewModel.exportVideoWithSpeedometer(it) },
                onResetExportState = { viewModel.resetExportState() },
                onDismiss = { showHistoryDialog = false }
            )
        }
    }
}
