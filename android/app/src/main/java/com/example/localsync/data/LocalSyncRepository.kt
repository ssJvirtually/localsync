package com.example.localsync.data

import kotlinx.coroutines.flow.Flow

interface LocalSyncRepository {
    val pairedServerFlow: Flow<PairedServer?>
    val mediaItemsFlow: Flow<List<MediaItem>>
    val totalCountFlow: Flow<Int>
    val backedUpCountFlow: Flow<Int>
    fun isSyncPaused(): Boolean
    fun setSyncPaused(paused: Boolean)
    suspend fun scanLocalMedia()
    suspend fun unpair()
    suspend fun deleteMediaItem(item: MediaItem)
}
