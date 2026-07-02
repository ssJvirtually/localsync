package com.example.localsync.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaItemDao {
    @Query("SELECT * FROM media_items ORDER BY dateTaken DESC")
    fun getAllMediaItemsFlow(): Flow<List<MediaItem>>

    @Query("SELECT * FROM media_items WHERE backupStatus = :status ORDER BY dateTaken ASC")
    suspend fun getItemsByStatus(status: BackupStatus): List<MediaItem>

    @Query("SELECT * FROM media_items WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getItemById(mediaId: Long): MediaItem?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<MediaItem>)

    @Update
    suspend fun updateItem(item: MediaItem)

    @Query("UPDATE media_items SET backupStatus = 'PENDING' WHERE backupStatus = 'UPLOADING'")
    suspend fun resetUploadingStatus()

    @Query("SELECT COUNT(*) FROM media_items WHERE backupStatus = 'DONE'")
    fun getBackedUpCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media_items")
    fun getTotalCountFlow(): Flow<Int>

    @Query("DELETE FROM media_items")
    suspend fun clearAllMedia()

    @Delete
    suspend fun deleteItem(item: MediaItem)
}
