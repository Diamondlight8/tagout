package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.BlockingProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockingProfileDao {
    @Query("SELECT * FROM blocking_profiles ORDER BY id ASC")
    fun getAllProfiles(): Flow<List<BlockingProfile>>

    @Query("SELECT * FROM blocking_profiles WHERE isActive = 1")
    fun getActiveProfiles(): Flow<List<BlockingProfile>>

    @Query("SELECT * FROM blocking_profiles WHERE isActive = 1")
    suspend fun getActiveProfilesSync(): List<BlockingProfile>

    @Query("SELECT * FROM blocking_profiles WHERE id = :id LIMIT 1")
    fun getProfileById(id: Long): Flow<BlockingProfile?>

    @Query("SELECT * FROM blocking_profiles WHERE id = :id LIMIT 1")
    suspend fun getProfileByIdSync(id: Long): BlockingProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: BlockingProfile): Long

    @Update
    suspend fun updateProfile(profile: BlockingProfile)

    @Query("UPDATE blocking_profiles SET isActive = :isActive, activatedAt = :activatedAt WHERE id = :id")
    suspend fun updateActiveStatus(id: Long, isActive: Boolean, activatedAt: Long?)

    @Delete
    suspend fun deleteProfile(profile: BlockingProfile)

    @Query("DELETE FROM blocking_profiles WHERE id = :id")
    suspend fun deleteProfileById(id: Long)
}
