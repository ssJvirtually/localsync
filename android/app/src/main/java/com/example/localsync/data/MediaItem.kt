package com.example.localsync.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MediaType {
    PHOTO, VIDEO
}

enum class BackupStatus {
    PENDING, UPLOADING, DONE, FAILED
}

@Entity(
    tableName = "media_items",
    foreignKeys = [
        ForeignKey(
            entity = PairedServer::class,
            parentColumns = ["id"],
            childColumns = ["pairedServerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["pairedServerId"])]
)
data class MediaItem(
    @PrimaryKey val mediaId: Long,
    val filePath: String,
    val fileName: String,
    val dateTaken: Long,
    val fileHash: String?,
    val sizeBytes: Long,
    val mediaType: MediaType,
    val backupStatus: BackupStatus,
    val lastAttemptAt: Long?,
    val pairedServerId: Long
)
