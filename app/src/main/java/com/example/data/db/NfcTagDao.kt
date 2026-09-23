package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.NfcTag
import kotlinx.coroutines.flow.Flow

@Dao
interface NfcTagDao {
    @Query("SELECT * FROM nfc_tags ORDER BY createdAt ASC")
    fun getAllTags(): Flow<List<NfcTag>>

    @Query("SELECT * FROM nfc_tags WHERE id = :id LIMIT 1")
    suspend fun getTagById(id: String): NfcTag?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: NfcTag)

    @Update
    suspend fun updateTag(tag: NfcTag)

    @Delete
    suspend fun deleteTag(tag: NfcTag)

    @Query("DELETE FROM nfc_tags WHERE id = :id")
    suspend fun deleteTagById(id: String)
}
