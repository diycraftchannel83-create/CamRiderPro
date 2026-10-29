package com.example.telemetry

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.example.data.model.GpsTelemetry
import com.example.data.model.LatLngPoint
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class GpsSpeedTracker(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _telemetry = MutableStateFlow(GpsTelemetry())
    val telemetry: StateFlow<GpsTelemetry> = _telemetry.asStateFlow()

    private var lastLocation: Location? = null
    private var totalDistanceMeters: Double = 0.0
    private var maxRecordedSpeedKmh: Float = 0f
    private val speedReadings = mutableListOf<Float>()
    private val routeTrail = mutableListOf<LatLngPoint>()

    private var isTracking = false
    private var isSimulating = false
    private var simulationJob: Job? = null
    private val trackerScope = CoroutineScope(Dispatchers.Default)

    // Auto record listener
    var onAutoRecordTrigger: ((Boolean) -> Unit)? = null
    var autoRecordThresholdKmh: Float = 5.0f
    var isAutoRecordEnabled: Boolean = true
    private var consecutiveMovingSeconds = 0

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (isSimulating) return
            result.lastLocation?.let { loc ->
                processLocation(loc)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking) return
        isTracking = true

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(0.5f)
            .build()

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            // Handled via Compose permission flow; fallback to initial state
        }
    }

    fun stopTracking() {
        isTracking = false
        stopSimulation()
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {
        }
    }

    fun resetTrip() {
        totalDistanceMeters = 0.0
        maxRecordedSpeedKmh = 0f
        speedReadings.clear()
        routeTrail.clear()
        consecutiveMovingSeconds = 0
        _telemetry.value = _telemetry.value.copy(
            speedKmh = 0f,
            maxSpeedKmh = 0f,
            avgSpeedKmh = 0f,
            distanceKm = 0f,
            locationHistory = emptyList(),
            gear = "N",
            rpm = 1100
        )
    }

    private fun processLocation(loc: Location) {
        // GPS speed in m/s converted to km/h
        var rawSpeedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f

        // Stationary noise filter: GPS drift often produces 0.5 - 1.5 km/h jitter when stopped
        if (rawSpeedKmh < 1.8f) {
            rawSpeedKmh = 0f
        }

        // Distance accumulator
        lastLocation?.let { prev ->
            val dist = prev.distanceTo(loc)
            if (dist > 1.5f && rawSpeedKmh > 1.0f) {
                totalDistanceMeters += dist
            }
        }
        lastLocation = loc

        if (rawSpeedKmh > maxRecordedSpeedKmh) {
            maxRecordedSpeedKmh = rawSpeedKmh
        }

        if (rawSpeedKmh > 0f) {
            speedReadings.add(rawSpeedKmh)
            if (speedReadings.size > 300) speedReadings.removeAt(0)
        }

        val avgSpeed = if (speedReadings.isNotEmpty()) {
            speedReadings.average().toFloat()
        } else {
            0f
        }

        val bearing = if (loc.hasBearing()) {
            loc.bearing
        } else {
            _telemetry.value.bearingDegrees
        }

        // Motorcycle Gear & RPM synthesis
        val (gear, rpm) = calculateGearAndRpm(rawSpeedKmh)

        // Route breadcrumb update
        val point = LatLngPoint(loc.latitude, loc.longitude, rawSpeedKmh)
        if (routeTrail.isEmpty() || rawSpeedKmh > 1f) {
            routeTrail.add(point)
            if (routeTrail.size > 200) routeTrail.removeAt(0)
        }

        // Satellites heuristic
        val sats = if (loc.extras?.containsKey("satellites") == true) {
            loc.extras?.getInt("satellites") ?: 14
        } else {
            if (loc.accuracy < 10f) 18 else if (loc.accuracy < 25f) 12 else 7
        }

        _telemetry.value = GpsTelemetry(
            speedKmh = rawSpeedKmh,
            maxSpeedKmh = maxRecordedSpeedKmh,
            avgSpeedKmh = avgSpeed,
            distanceKm = (totalDistanceMeters / 1000.0).toFloat(),
            latitude = loc.latitude,
            longitude = loc.longitude,
            altitudeMeters = loc.altitude,
            satellitesCount = sats,
            bearingDegrees = bearing,
            gear = gear,
            rpm = rpm,
            isGpsFixed = true,
            accuracyMeters = loc.accuracy,
            locationHistory = routeTrail.toList()
        )

        // Auto-record trigger check
        checkAutoRecord(rawSpeedKmh)
    }

    private fun checkAutoRecord(speed: Float) {
        if (!isAutoRecordEnabled) return
        if (speed >= autoRecordThresholdKmh) {
            consecutiveMovingSeconds++
            if (consecutiveMovingSeconds >= 2) {
                onAutoRecordTrigger?.invoke(true)
            }
        } else {
            consecutiveMovingSeconds = 0
        }
    }

    private fun calculateGearAndRpm(speed: Float): Pair<String, Int> {
        return when {
            speed < 1.0f -> "N" to 1150
            speed < 25.0f -> {
                val ratio = (speed / 25f).coerceIn(0f, 1f)
                "1" to (2200 + (ratio * 4500).toInt())
            }
            speed < 45.0f -> {
                val ratio = ((speed - 25f) / 20f).coerceIn(0f, 1f)
                "2" to (3000 + (ratio * 4500).toInt())
            }
            speed < 68.0f -> {
                val ratio = ((speed - 45f) / 23f).coerceIn(0f, 1f)
                "3" to (3400 + (ratio * 4600).toInt())
            }
            speed < 90.0f -> {
                val ratio = ((speed - 68f) / 22f).coerceIn(0f, 1f)
                "4" to (3800 + (ratio * 4800).toInt())
            }
            speed < 118.0f -> {
                val ratio = ((speed - 90f) / 28f).coerceIn(0f, 1f)
                "5" to (4200 + (ratio * 5000).toInt())
            }
            else -> {
                val ratio = ((speed - 118f) / 60f).coerceIn(0f, 1f)
                "6" to (4800 + (ratio * 6500).toInt().coerceAtMost(13000))
            }
        }
    }

    /**
     * Test & Demonstration Ride Simulation mode for indoor testing, desk testing,
     * or emulator environments where physical motorcycle movement isn't active.
     */
    fun startRideSimulation() {
        if (isSimulating) return
        isSimulating = true

        simulationJob = trackerScope.launch {
            var currentSimSpeed = 0f
            var targetSpeed = 75f
            var curLat = -7.3386
            var curLon = 108.2174
            var curAlt = 682.0
            var heading = 25f

            while (isActive && isSimulating) {
                // Smooth acceleration/deceleration curve
                if (currentSimSpeed < targetSpeed) {
                    currentSimSpeed += (2.2f + (Math.random() * 2f).toFloat())
                } else {
                    currentSimSpeed -= (1.5f + (Math.random() * 1.5f).toFloat())
                }

                if (abs(currentSimSpeed - targetSpeed) < 4f) {
                    // Pick new target speed periodically
                    targetSpeed = (40f + (Math.random() * 95f).toFloat())
                }

                currentSimSpeed = currentSimSpeed.coerceIn(0f, 175f)

                // Advance coordinates slightly along heading
                val rad = Math.toRadians(heading.toDouble())
                val speedMps = (currentSimSpeed / 3.6)
                val distMeters = speedMps * 0.5
                totalDistanceMeters += distMeters

                curLat += (distMeters / 111320.0) * cos(rad)
                curLon += (distMeters / (111320.0 * cos(Math.toRadians(curLat)))) * sin(rad)
                curAlt += (Math.random() - 0.48) * 0.8
                heading = (heading + (Math.random() * 4 - 2).toFloat() + 360f) % 360f

                if (currentSimSpeed > maxRecordedSpeedKmh) {
                    maxRecordedSpeedKmh = currentSimSpeed
                }
                speedReadings.add(currentSimSpeed)
                if (speedReadings.size > 300) speedReadings.removeAt(0)

                val (gear, rpm) = calculateGearAndRpm(currentSimSpeed)
                val pt = LatLngPoint(curLat, curLon, currentSimSpeed)
                routeTrail.add(pt)
                if (routeTrail.size > 200) routeTrail.removeAt(0)

                _telemetry.value = GpsTelemetry(
                    speedKmh = currentSimSpeed,
                    maxSpeedKmh = maxRecordedSpeedKmh,
                    avgSpeedKmh = speedReadings.average().toFloat(),
                    distanceKm = (totalDistanceMeters / 1000.0).toFloat(),
                    latitude = curLat,
                    longitude = curLon,
                    altitudeMeters = curAlt,
                    satellitesCount = 18,
                    bearingDegrees = heading,
                    gear = gear,
                    rpm = rpm,
                    isGpsFixed = true,
                    accuracyMeters = 3.5f,
                    locationHistory = routeTrail.toList()
                )

                checkAutoRecord(currentSimSpeed)
                delay(500)
            }
        }
    }

    fun stopSimulation() {
        isSimulating = false
        simulationJob?.cancel()
        simulationJob = null
    }

    fun isSimulationActive(): Boolean = isSimulating
}
