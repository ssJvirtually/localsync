package com.example.localsync.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "paired_servers")
data class PairedServer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serviceName: String,
    val pcName: String,
    val token: String,
    val deviceId: String,
    val fallbackIp: String,
    val pairedAt: Long
)
