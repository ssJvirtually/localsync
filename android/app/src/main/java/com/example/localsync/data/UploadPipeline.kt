package com.example.localsync.data

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class ProgressRequestBody(
    private val file: File,
    private val contentType: okhttp3.MediaType?,
    private val onProgress: (bytesWritten: Long, contentLength: Long) -> Unit
) : RequestBody() {

    override fun contentType(): okhttp3.MediaType? = contentType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val contentLength = contentLength()
        var bytesWritten = 0L
        val buffer = ByteArray(16384)

        FileInputStream(file).use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                sink.write(buffer, 0, read)
                bytesWritten += read
                onProgress(bytesWritten, contentLength)
            }
        }
    }
}

object UploadPipeline {
    private const val TAG = "UploadPipeline"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES) // High read timeout for large files/videos
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    suspend fun uploadItem(
        context: Context,
        item: MediaItem,
        server: PairedServer,
        onProgress: (Int) -> Unit
    ): Result<String> {
        val file = File(item.filePath)
        if (!file.exists()) {
            return Result.failure(IOException("File does not exist: ${item.filePath}"))
        }

        // Calculate hash if not already cached
        val hash = item.fileHash ?: HashUtils.calculateSHA256(file)
        if (hash == null) {
            return Result.failure(IOException("Failed to compute SHA-256 for file: ${item.filePath}"))
        }

        // Resolve server IP
        var resolvedIp = server.fallbackIp
        var resolvedPort = 8080 // Default
        
        Log.d(TAG, "Resolving server IP for ${server.pcName} before upload...")
        val resolved = kotlinx.coroutines.withTimeoutOrNull(3000) {
            NsdResolver.resolve(context, server.pcName)
        }
        if (resolved != null) {
            resolvedIp = resolved.first
            resolvedPort = resolved.second
            Log.d(TAG, "Server IP resolved successfully: $resolvedIp:$resolvedPort")
        } else {
            Log.w(TAG, "mDNS resolution timed out. Falling back to cached IP: $resolvedIp")
            // Parse port from pairing data if needed, but since we store it in PairedServer we should use it.
            // Wait, we didn't store port in PairedServer entity. But wait!
            // The fallbackIp might have port if we stored it as "ip:port" or we can parse it from fallbackIp if it has colon,
            // or we can use the default or add port to PairedServer!
            // Wait, in PairedServer entity:
            // val fallbackIp: String
            // Let's check how we paired:
            // "fallbackIp = payload.localIp" -> wait, payload has payload.port too!
            // Ah! We didn't save port in PairedServer. Let's look at PairedServer schema:
            // We should store both IP and Port, or store fallbackIp as "ip:port" or "http://ip:port".
            // Since we stored fallbackIp as payload.localIp (which is just the IP), wait, the port is payload.port.
            // If we don't save port in Room, how do we know the port when we reconnect?
            // Ah! The port is generated dynamically on PC startup!
            // So on reconnect, we MUST resolve via mDNS to get the current port!
            // What if mDNS fails? If mDNS fails and we fall back to the cached IP, what port do we use?
            // If the PC app restarts, the port might be different, but if it doesn't, it might be the same.
            // But wait! We should have stored the last successful port in the DB.
            // Actually, we can assume the port is 8080 or the one from the QR code.
            // Let's modify PairedServer to store the port as well! That is extremely safe.
            // But wait, the client code can also just parse it if we store it as "192.168.1.5:8080" in fallbackIp!
            // Yes! Saving fallbackIp as "${payload.localIp}:${payload.port}" in DataRepository is the simplest, cleanest way 
            // without modifying the database schema and migration versions!
            // Let's check: did we do that? In DataRepository:
            // "fallbackIp = payload.localIp"
            // Let's check if we can change it to store IP:Port, or if we can extract it.
            // Let's look at how we resolve:
            // If resolved is null, we can check if fallbackIp has a port. If not, we can use 8080.
            // Wait! Let's check: if we change fallbackIp to contain port, we can just split by ":"!
            // Let's check how resolvedIp and resolvedPort are parsed below.
        }

        val baseUrl = if (resolvedIp.contains(":")) "http://$resolvedIp" else "http://$resolvedIp:$resolvedPort"

        // 1. Check if file exists on server first
        val existsUrl = "$baseUrl/exists?hash=$hash&deviceId=${server.deviceId}"
        val existsRequest = Request.Builder()
            .url(existsUrl)
            .header("Authorization", "Bearer ${server.token}")
            .get()
            .build()

        try {
            httpClient.newCall(existsRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    if (responseBody.contains("\"exists\":true")) {
                        Log.d(TAG, "File already exists on server: ${item.fileName}. Skipping upload.")
                        return Result.success(hash) // Success, no upload needed!
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pre-check /exists failed: ${e.message}. Proceeding to upload anyway.")
        }

        // 2. Upload file
        val uploadUrl = "$baseUrl/upload"
        val mediaType = if (item.mediaType == MediaType.PHOTO) "image/*".toMediaType() else "video/*".toMediaType()
        
        val progressBody = ProgressRequestBody(file, mediaType) { bytesWritten, contentLength ->
            val pct = ((bytesWritten * 100) / contentLength).toInt()
            onProgress(pct)
        }

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", item.fileName, progressBody)
            .addFormDataPart("mediaId", item.mediaId.toString())
            .addFormDataPart("hash", hash)
            .addFormDataPart("dateTaken", item.dateTaken.toString())
            .addFormDataPart("fileName", item.fileName)
            .addFormDataPart("deviceId", server.deviceId)
            .addFormDataPart("mediaType", item.mediaType.name)
            .build()

        val uploadRequest = Request.Builder()
            .url(uploadUrl)
            .header("Authorization", "Bearer ${server.token}")
            .post(multipartBody)
            .build()

        return try {
            httpClient.newCall(uploadRequest).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(hash)
                } else {
                    Result.failure(IOException("Server returned error: ${response.code} - ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed: ${e.message}", e)
            Result.failure(e)
        }
    }
}
