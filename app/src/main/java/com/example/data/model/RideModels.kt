package com.example.data.model

enum class SpeedUnit(val label: String, val multiplierFromKmh: Float) {
    KMH("km/h", 1.0f),
    MPH("mph", 0.621371f)
}

enum class VideoResolution(val label: String, val width: Int, val height: Int) {
    RES_4K("4K (3840x2160)", 3840, 2160),
    RES_1080P("1080p (1920x1080)", 1920, 1080),
    RES_720P("720p (1280x720)", 1280, 720)
}

data class GpsTelemetry(
    val speedKmh: Float = 0f,
    val maxSpeedKmh: Float = 0f,
    val avgSpeedKmh: Float = 0f,
    val distanceKm: Float = 0f,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitudeMeters: Double = 0.0,
    val satellitesCount: Int = 0,
    val bearingDegrees: Float = 0f,
    val gear: String = "--",
    val rpm: Int = 0,
    val isGpsFixed: Boolean = false,
    val accuracyMeters: Float = 0f,
    val locationHistory: List<LatLngPoint> = emptyList()
)

data class LatLngPoint(
    val lat: Double,
    val lon: Double,
    val speedKmh: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

data class OverlayConfig(
    val showSpeedometer: Boolean = true,
    val showGpsMap: Boolean = true,
    val showCoordinates: Boolean = true,
    val showGearRpm: Boolean = true,
    val showDateTime: Boolean = true,
    val showWatermark: Boolean = true
)

data class RecordingSettings(
    val resolution: VideoResolution = VideoResolution.RES_1080P,
    val targetFps: Int = 60,
    val audioEnabled: Boolean = true,
    val nightModeEnabled: Boolean = false,
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val voiceAssistantEnabled: Boolean = true,
    val speedLimitAlertEnabled: Boolean = true,
    val welcomeVoiceEnabled: Boolean = true,
    val speedLimitKmh: Int = 50,
    val alertRepeatSeconds: Int = 30
)

data class RideSession(
    val id: Long = 0,
    val title: String,
    val videoPath: String,
    val timestamp: Long,
    val durationSeconds: Long,
    val maxSpeedKmh: Float,
    val avgSpeedKmh: Float,
    val distanceKm: Float,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val fileSizeFormatted: String = ""
)
