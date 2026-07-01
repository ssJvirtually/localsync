package com.example.localsync.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PairedServerDao {
    @Query("SELECT * FROM paired_servers LIMIT 1")
    fun getPairedServerFlow(): Flow<PairedServer?>

    @Query("SELECT * FROM paired_servers LIMIT 1")
    suspend fun getPairedServer(): PairedServer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: PairedServer): Long

    @Query("DELETE FROM paired_servers")
    suspend fun clearServers()
}
