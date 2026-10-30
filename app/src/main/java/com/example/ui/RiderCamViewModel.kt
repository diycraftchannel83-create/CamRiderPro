package com.example.ui

import android.app.Application
import android.media.AudioAttributes
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.camera.CameraRecordingManager
import com.example.data.local.RideDatabase
import com.example.data.local.RideEntity
import com.example.data.model.GpsTelemetry
import com.example.data.model.OverlayConfig
import com.example.data.model.RecordingSettings
import com.example.data.model.RideSession
import com.example.telemetry.GpsSpeedTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RiderCamViewModel(application: Application) : AndroidViewModel(application) {

    val gpsTracker = GpsSpeedTracker(application)
    val cameraManager = CameraRecordingManager(application)
    val videoExporter = com.example.export.TelemetryVideoExporter(application)
    private val rideDao = RideDatabase.getInstance(application).rideDao()

    val telemetry: StateFlow<GpsTelemetry> = gpsTracker.telemetry
    val isRecording: StateFlow<Boolean> = cameraManager.isRecording
    val recordingDurationSeconds: StateFlow<Long> = cameraManager.recordingDurationSeconds
    val isNightBoostActive: StateFlow<Boolean> = cameraManager.isNightBoostActive
    val isTorchActive: StateFlow<Boolean> = cameraManager.isTorchActive

    private val _isScreenLocked = MutableStateFlow(false)
    val isScreenLocked: StateFlow<Boolean> = _isScreenLocked.asStateFlow()

    private val _settings = MutableStateFlow(RecordingSettings())
    val settings: StateFlow<RecordingSettings> = _settings.asStateFlow()

    private val _overlayConfig = MutableStateFlow(OverlayConfig())
    val overlayConfig: StateFlow<OverlayConfig> = _overlayConfig.asStateFlow()

    private val _rides = MutableStateFlow<List<RideSession>>(emptyList())
    val rides: StateFlow<List<RideSession>> = _rides.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private val _isSimulating = MutableStateFlow(false)
    val isSimulating: StateFlow<Boolean> = _isSimulating.asStateFlow()

    val exportState: StateFlow<com.example.export.ExportState> = videoExporter.exportState
    private var recordingTelemetrySnapshot: GpsTelemetry? = null
    private var voiceTts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var wasRecording = false
    private var speedAlertArmed = true
    private var lastSpeedAlertAt = 0L

    private fun speak(message: String) {
        if (!ttsReady || !_settings.value.voiceAssistantEnabled) return
        val utteranceId = "camrider-${System.currentTimeMillis()}"
        val result = voiceTts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            Log.e("CamRiderVoice", "TTS rejected utterance: $utteranceId")
        }
    }

    init {
        voiceTts = TextToSpeech(application) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                // Navigation-guidance attributes are less disruptive to CameraX microphone capture
                // than the default media playback usage on devices with aggressive audio-focus policies.
                voiceTts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                voiceTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d("CamRiderVoice", "TTS started: $utteranceId")
                    }
                    override fun onDone(utteranceId: String?) {
                        Log.d("CamRiderVoice", "TTS completed: $utteranceId")
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e("CamRiderVoice", "TTS playback failed: $utteranceId")
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e("CamRiderVoice", "TTS playback failed ($errorCode): $utteranceId")
                    }
                })
                val result = voiceTts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    voiceTts?.setLanguage(Locale.getDefault())
                }
                voiceTts?.setSpeechRate(0.95f)
            }
        }

        telemetry.onEach { data ->
            val prefs = _settings.value
            val threshold = prefs.speedLimitKmh.toFloat()
            if (!prefs.speedLimitAlertEnabled || !prefs.voiceAssistantEnabled) {
                speedAlertArmed = true
                return@onEach
            }
            if (data.speedKmh < threshold - 3f) speedAlertArmed = true
            val now = System.currentTimeMillis()
            if (speedAlertArmed && data.speedKmh >= threshold &&
                now - lastSpeedAlertAt >= TimeUnit.SECONDS.toMillis(prefs.alertRepeatSeconds.toLong())) {
                speedAlertArmed = false
                lastSpeedAlertAt = now
                // The slider is explicitly calibrated and labelled in km/h.
                // Announce that exact configured threshold so the spoken value always
                // matches the number shown in Settings (not the fluctuating GPS speed).
                val configuredLimitKmh = prefs.speedLimitKmh
                speak("Speed limit is set to $configuredLimitKmh kilometers per hour. Please slow down.")
            }
        }.launchIn(viewModelScope)

        isRecording.onEach { recording ->
            if (recording && !wasRecording && _settings.value.welcomeVoiceEnabled) {
                speak("Welcome to CamRider Pro. Happy travelling and enjoy your journey!")
            }
            wasRecording = recording
        }.launchIn(viewModelScope)

        // Observe saved rides from database
        rideDao.getAllRides().onEach { entities ->
            _rides.value = entities.map { it.toRideSession() }
        }.launchIn(viewModelScope)

        // CameraX OverlayEffect reads this snapshot while encoding each frame, so the
        // recorded MP4 contains the live speed/GPS HUD without a post-recording export.
        cameraManager.telemetryProvider = { telemetry.value }
        cameraManager.speedUnitLabelProvider = { _settings.value.speedUnit.label }
        cameraManager.videoProfileLabelProvider = {
            "${_settings.value.resolution.label.substringBefore(" ")}  ${_settings.value.targetFps}FPS H.265"
        }

        // On recording saved handler
        cameraManager.onRecordingSaved = { videoUri, displayPath, duration ->
            saveRideToDatabase(videoUri, displayPath, duration)
        }

        cameraManager.onRecordingError = { msg ->
            _bannerMessage.value = msg
        }
    }

    fun startGpsTracking() {
        gpsTracker.startTracking()
    }

    fun toggleRecord() {
        if (cameraManager.isRecording.value) {
            // Freeze trip metrics at the user's stop action; encoder finalization is asynchronous.
            recordingTelemetrySnapshot = telemetry.value
            cameraManager.stopRecording()
            _bannerMessage.value = "Perekaman selesai & tersimpan otomatis"
        } else {
            // Every manual recording starts with a clean trip accumulator.
            gpsTracker.resetTrip()
            recordingTelemetrySnapshot = null
            cameraManager.startRecording(_settings.value.audioEnabled)
            _bannerMessage.value = "Perekaman manual dimulai"
        }
    }

    fun toggleAudio() {
        val updated = !_settings.value.audioEnabled
        _settings.value = _settings.value.copy(audioEnabled = updated)
        _bannerMessage.value = if (updated) "Audio mikrofon diaktifkan" else "Audio mikrofon dimatikan"
    }

    fun toggleNightBoost() {
        cameraManager.toggleNightMode()
        _settings.value = _settings.value.copy(nightModeEnabled = cameraManager.isNightBoostActive.value)
        _bannerMessage.value = if (cameraManager.isNightBoostActive.value) {
            "Mode Malam Aktif: Kompensasi eksposur ditingkatkan"
        } else {
            "Mode Normal"
        }
    }

    fun toggleTorch() {
        cameraManager.toggleTorch()
    }

    fun lockScreen() {
        _isScreenLocked.value = true
    }

    fun unlockScreen() {
        _isScreenLocked.value = false
    }

    fun updateSettings(newSettings: RecordingSettings) {
        _settings.value = newSettings
        if (!newSettings.speedLimitAlertEnabled) speedAlertArmed = true
    }

    fun updateOverlayConfig(newConfig: OverlayConfig) {
        _overlayConfig.value = newConfig
    }

    fun toggleSimulation() {
        if (gpsTracker.isSimulationActive()) {
            gpsTracker.stopSimulation()
            _isSimulating.value = false
            _bannerMessage.value = "Simulasi dimatikan: GPS asli aktif"
        } else {
            gpsTracker.resetTrip()
            gpsTracker.startRideSimulation()
            _isSimulating.value = true
            _bannerMessage.value = "Simulasi perjalanan motor aktif!"
        }
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    fun deleteRide(ride: RideSession) {
        viewModelScope.launch {
            rideDao.deleteRide(RideEntity.fromRideSession(ride))
            if (ride.videoPath.startsWith("content://")) {
                try {
                    getApplication<Application>().contentResolver.delete(
                        android.net.Uri.parse(ride.videoPath), null, null
                    )
                } catch (_: Exception) {}
            } else {
                val file = File(ride.videoPath)
                if (file.exists()) {
                    file.delete()
                }
            }
            _bannerMessage.value = "Rekaman perjalanan dihapus"
        }
    }

    private fun saveRideToDatabase(videoUri: String, displayPath: String, durationSeconds: Long) {
        viewModelScope.launch {
            val telem = recordingTelemetrySnapshot ?: telemetry.value
            recordingTelemetrySnapshot = null
            val dateStr = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date())

            val session = RideSession(
                title = "Perjalanan Motor $dateStr",
                videoPath = videoUri,
                timestamp = System.currentTimeMillis(),
                durationSeconds = durationSeconds.coerceAtLeast(1L),
                maxSpeedKmh = telem.maxSpeedKmh,
                avgSpeedKmh = telem.avgSpeedKmh,
                distanceKm = telem.distanceKm,
                startLat = telem.locationHistory.firstOrNull()?.lat ?: telem.latitude,
                startLon = telem.locationHistory.firstOrNull()?.lon ?: telem.longitude,
                endLat = telem.latitude,
                endLon = telem.longitude,
                fileSizeFormatted = displayPath
            )

            val rideId = rideDao.insertRide(RideEntity.fromRideSession(session))
            val savedSession = session.copy(id = rideId)
            // HUD is already burned into each encoded frame by CameraX OverlayEffect.
            _bannerMessage.value = "Rekaman tersimpan dengan HUD speedometer langsung di video"
        }
    }

    fun exportVideoWithSpeedometer(session: RideSession) {
        viewModelScope.launch {
            _bannerMessage.value = "Mengekspor video dengan overlay spedometer..."
            videoExporter.exportVideoWithSpeedometerOverlay(session, _settings.value.speedUnit)
        }
    }

    fun resetExportState() {
        videoExporter.resetState()
    }

    override fun onCleared() {
        voiceTts?.stop()
        voiceTts?.shutdown()
        voiceTts = null
        super.onCleared()
        gpsTracker.stopTracking()
        cameraManager.release()
    }
}
