package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.RideSession

@Entity(tableName = "rides")
data class RideEntity(
    @PrimaryKey(autoGenerate = true)
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
    val fileSizeFormatted: String
) {
    fun toRideSession(): RideSession = RideSession(
        id = id,
        title = title,
        videoPath = videoPath,
        timestamp = timestamp,
        durationSeconds = durationSeconds,
        maxSpeedKmh = maxSpeedKmh,
        avgSpeedKmh = avgSpeedKmh,
        distanceKm = distanceKm,
        startLat = startLat,
        startLon = startLon,
        endLat = endLat,
        endLon = endLon,
        fileSizeFormatted = fileSizeFormatted
    )

    companion object {
        fun fromRideSession(session: RideSession): RideEntity = RideEntity(
            id = session.id,
            title = session.title,
            videoPath = session.videoPath,
            timestamp = session.timestamp,
            durationSeconds = session.durationSeconds,
            maxSpeedKmh = session.maxSpeedKmh,
            avgSpeedKmh = session.avgSpeedKmh,
            distanceKm = session.distanceKm,
            startLat = session.startLat,
            startLon = session.startLon,
            endLat = session.endLat,
            endLon = session.endLon,
            fileSizeFormatted = session.fileSizeFormatted
        )
    }
}
