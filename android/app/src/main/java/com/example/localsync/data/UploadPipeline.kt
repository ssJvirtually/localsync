package com.example.localsync.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class ProgressRequestBody(
    private val file: File,
    private val contentType: okhttp3.MediaType?,
    private val onProgress: (Long, Long) -> Unit
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

    private fun isTailscaleIp(ip: String): Boolean {
        if (!ip.startsWith("100.")) return false
        val parts = ip.split(".")
        if (parts.size < 2) return false
        val secondOctet = parts[1].toIntOrNull() ?: return false
        return secondOctet in 64..127
    }

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
        var resolvedIp = ""
        var resolvedPort = 8080 // Default
        var successfullyResolved = false
        
        Log.d(TAG, "Resolving server IP for ${server.pcName} before upload...")
        val resolved = kotlinx.coroutines.withTimeoutOrNull(3000) {
            NsdResolver.resolve(context, server.pcName)
        }
        if (resolved != null) {
            resolvedIp = resolved.first
            resolvedPort = resolved.second
            successfullyResolved = true
            Log.d(TAG, "Server IP resolved successfully via mDNS: $resolvedIp:$resolvedPort")
        } else {
            val fallbacks = server.fallbackIp.split(",")
            Log.w(TAG, "mDNS resolution timed out. Checking fallback IPs: $fallbacks")
            
            for (fallback in fallbacks) {
                if (fallback.isBlank()) continue
                val parts = fallback.split(":")
                val host = parts[0]
                val port = parts.getOrNull(1)?.toIntOrNull() ?: 8080
                
                val testUrl = "http://$host:$port/health"
                val request = Request.Builder()
                    .url(testUrl)
                    .get()
                    .build()
                
                try {
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            resolvedIp = host
                            resolvedPort = port
                            successfullyResolved = true
                            Log.d(TAG, "Successfully reached fallback IP: $resolvedIp:$resolvedPort")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback IP $host:$port unreachable: ${e.message}")
                }
                if (successfullyResolved) break
            }
        }

        if (!successfullyResolved) {
            return Result.failure(IOException("Server is unreachable on all cached IPs and mDNS resolve failed."))
        }

        // Enforce Cellular network restrictions if applicable
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        
        if (isCellular && !isWifi) {
            val sharedPrefs = context.getSharedPreferences("localsync_prefs", Context.MODE_PRIVATE)
            val isCellularTailscaleAllowed = sharedPrefs.getBoolean("sync_on_cellular_tailscale", false)
            
            val hostClean = resolvedIp.replace("[", "").replace("]", "")
            val isTailscale = isTailscaleIp(hostClean)
            
            if (!isCellularTailscaleAllowed || !isTailscale) {
                Log.w(TAG, "Blocked upload on Cellular. Tailscale allowed: $isCellularTailscaleAllowed, Is Tailscale IP: $isTailscale ($resolvedIp)")
                return Result.failure(IOException("Blocked: Sync over mobile network is disabled, or you are not connected to your PC via Tailscale."))
            }
            Log.d(TAG, "Allowing upload on Cellular because setting is enabled and resolved IP is Tailscale: $resolvedIp")
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
            val pct = ((bytesWritten.toFloat() / contentLength.toFloat()) * 100).toInt().coerceIn(0, 100)
            onProgress(pct)
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", item.fileName, progressBody)
            .addFormDataPart("hash", hash)
            .addFormDataPart("deviceId", server.deviceId)
            .addFormDataPart("mediaId", item.mediaId.toString())
            .addFormDataPart("fileName", item.fileName)
            .addFormDataPart("fileSize", file.length().toString())
            .addFormDataPart("dateTaken", item.dateTaken.toString())
            .addFormDataPart("mediaType", item.mediaType.name)
            .build()

        val request = Request.Builder()
            .url(uploadUrl)
            .header("Authorization", "Bearer ${server.token}")
            .post(requestBody)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Uploaded successfully: ${item.fileName}")
                    return Result.success(hash)
                } else {
                    return Result.failure(IOException("Server returned error: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for ${item.fileName}: ${e.message}", e)
            return Result.failure(e)
        }
    }
}
