package com.example.localsync.data

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object HashUtils {
    fun calculateSHA256(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(16384) // 16 KB buffer
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val bytes = digest.digest()
            val result = StringBuilder()
            for (b in bytes) {
                result.append(String.format("%02x", b))
            }
            result.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
