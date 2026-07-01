package com.example.localsync.data

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class QrPayload(
    val service: String,
    val token: String,
    val pcName: String,
    val port: Int,
    val localIp: String
)

class DataRepository(private val context: Context) : LocalSyncRepository {
    private val db = AppDatabase.getDatabase(context)
    private val mediaItemDao = db.mediaItemDao()
    private val pairedServerDao = db.pairedServerDao()
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override val pairedServerFlow: Flow<PairedServer?> = pairedServerDao.getPairedServerFlow()
    override val mediaItemsFlow: Flow<List<MediaItem>> = mediaItemDao.getAllMediaItemsFlow()
    override val totalCountFlow: Flow<Int> = mediaItemDao.getTotalCountFlow()
    override val backedUpCountFlow: Flow<Int> = mediaItemDao.getBackedUpCountFlow()

    suspend fun getPairedServer(): PairedServer? = pairedServerDao.getPairedServer()

    suspend fun pairWithServer(qrJson: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = json.decodeFromString<QrPayload>(qrJson)
            
            // Try mDNS resolve first
            var resolvedIp = payload.localIp
            var resolvedPort = payload.port
            
            Log.d("DataRepository", "Attempting mDNS resolution for ${payload.pcName}...")
            val resolved = kotlinx.coroutines.withTimeoutOrNull(3000) {
                NsdResolver.resolve(context, payload.pcName)
            }
            if (resolved != null) {
                resolvedIp = resolved.first
                resolvedPort = resolved.second
                Log.d("DataRepository", "mDNS resolve succeeded: $resolvedIp:$resolvedPort")
            } else {
                Log.w("DataRepository", "mDNS resolve failed/timed out, falling back to QR IP: $resolvedIp:$resolvedPort")
            }

            val url = "http://$resolvedIp:$resolvedPort/pair/verify"
            
            val deviceName = Build.MODEL ?: "Android Device"
            val requestBodyJson = """{"token":"${payload.token}","deviceName":"$deviceName"}"""
            val body = requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Server error: ${response.code}"))
                }
                
                val responseBody = response.body?.string() ?: return@withContext Result.failure(IOException("Empty response"))
                val jsonObject = json.parseToJsonElement(responseBody)
                val deviceId = jsonObject.jsonObject["deviceId"]?.jsonPrimitive?.content 
                    ?: return@withContext Result.failure(IOException("No deviceId in response"))
                
                // Save to Room
                val server = PairedServer(
                    serviceName = payload.service,
                    pcName = payload.pcName,
                    token = payload.token,
                    deviceId = deviceId,
                    fallbackIp = "${payload.localIp}:${payload.port}",
                    pairedAt = System.currentTimeMillis()
                )
                
                val serverId = pairedServerDao.insertServer(server)
                
                // Trigger initial media scan in background
                scanMediaAndInsert(serverId)
                
                return@withContext Result.success(payload.pcName)
            }
        } catch (e: Exception) {
            Log.e("DataRepository", "Pairing failed: ${e.message}", e)
            return@withContext Result.failure(e)
        }
    }

    suspend fun scanMediaAndInsert(serverId: Long) = withContext(Dispatchers.IO) {
        try {
            val items = MediaScanner.scan(context, serverId)
            mediaItemDao.insertItems(items)
            Log.d("DataRepository", "Successfully scanned and inserted ${items.size} media items")
        } catch (e: Exception) {
            Log.e("DataRepository", "Error in scanMediaAndInsert: ${e.message}", e)
        }
    }

    override suspend fun scanLocalMedia() = withContext(Dispatchers.IO) {
        val server = pairedServerDao.getPairedServer()
        if (server != null) {
            scanMediaAndInsert(server.id)
        }
    }

    override suspend fun unpair() = withContext(Dispatchers.IO) {
        mediaItemDao.clearAllMedia()
        pairedServerDao.clearServers()
    }

    override fun isSyncPaused(): Boolean {
        return context.getSharedPreferences("localsync_prefs", Context.MODE_PRIVATE)
            .getBoolean("sync_paused", false)
    }

    override fun setSyncPaused(paused: Boolean) {
        context.getSharedPreferences("localsync_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("sync_paused", paused)
            .apply()
    }
}
