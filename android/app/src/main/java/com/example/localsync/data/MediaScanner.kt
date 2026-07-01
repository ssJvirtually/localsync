package com.example.localsync.data

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import java.io.File

object MediaScanner {
    private const val TAG = "MediaScanner"

    fun scan(context: Context, pairedServerId: Long): List<MediaItem> {
        val mediaList = mutableListOf<MediaItem>()

        // Scan Images
        val imageProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.SIZE
        )

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imageProjection,
                null,
                null,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val path = cursor.getString(dataColumn) ?: ""
                    val name = cursor.getString(nameColumn) ?: "IMG_$id.jpg"
                    var date = cursor.getLong(dateColumn)
                    val size = cursor.getLong(sizeColumn)

                    // If EXIF / MediaStore DATE_TAKEN is not present (or is 0 / Jan 01 1970), fall back to file's last modified date
                    if (date <= 0L && path.isNotEmpty()) {
                        try {
                            val file = File(path)
                            if (file.exists()) {
                                date = file.lastModified()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to read file attributes for: $path")
                        }
                    }

                    // Final fallback to current system time if still 0
                    if (date <= 0L) {
                        date = System.currentTimeMillis()
                    }

                    if (size > 0 && path.isNotEmpty()) {
                        mediaList.add(
                            MediaItem(
                                mediaId = id,
                                filePath = path,
                                fileName = name,
                                dateTaken = date,
                                fileHash = null,
                                sizeBytes = size,
                                mediaType = MediaType.PHOTO,
                                backupStatus = BackupStatus.PENDING,
                                lastAttemptAt = null,
                                pairedServerId = pairedServerId
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning images: ${e.message}", e)
        }

        // Scan Videos
        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.SIZE
        )

        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection,
                null,
                null,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val path = cursor.getString(dataColumn) ?: ""
                    val name = cursor.getString(nameColumn) ?: "VID_$id.mp4"
                    var date = cursor.getLong(dateColumn)
                    val size = cursor.getLong(sizeColumn)

                    // If EXIF / MediaStore DATE_TAKEN is not present (or is 0 / Jan 01 1970), fall back to file's last modified date
                    if (date <= 0L && path.isNotEmpty()) {
                        try {
                            val file = File(path)
                            if (file.exists()) {
                                date = file.lastModified()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to read file attributes for: $path")
                        }
                    }

                    // Final fallback to current system time if still 0
                    if (date <= 0L) {
                        date = System.currentTimeMillis()
                    }

                    if (size > 0 && path.isNotEmpty()) {
                        mediaList.add(
                            MediaItem(
                                mediaId = id,
                                filePath = path,
                                fileName = name,
                                dateTaken = date,
                                fileHash = null,
                                sizeBytes = size,
                                mediaType = MediaType.VIDEO,
                                backupStatus = BackupStatus.PENDING,
                                lastAttemptAt = null,
                                pairedServerId = pairedServerId
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning videos: ${e.message}", e)
        }

        Log.d(TAG, "Scanned ${mediaList.size} media items from MediaStore")
        return mediaList
    }
}
