package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RideDao {
    @Query("SELECT * FROM rides ORDER BY timestamp DESC")
    fun getAllRides(): Flow<List<RideEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRide(ride: RideEntity): Long

    @Delete
    suspend fun deleteRide(ride: RideEntity)

    @Query("UPDATE rides SET videoPath = :videoPath WHERE id = :id")
    suspend fun updateRideVideoPath(id: Long, videoPath: String)

    @Query("SELECT * FROM rides WHERE id = :id LIMIT 1")
    suspend fun getRideById(id: Long): RideEntity?
}
