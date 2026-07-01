package com.example.localsync.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromMediaType(value: MediaType): String {
        return value.name
    }

    @TypeConverter
    fun toMediaType(value: String): MediaType {
        return MediaType.valueOf(value)
    }

    @TypeConverter
    fun fromBackupStatus(value: BackupStatus): String {
        return value.name
    }

    @TypeConverter
    fun toBackupStatus(value: String): BackupStatus {
        return BackupStatus.valueOf(value)
    }
}
