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
    val localIp: String,
    val ips: List<String>? = null
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
            return@withContext executePairing(payload)
        } catch (e: Exception) {
            Log.e("DataRepository", "Pairing failed: ${e.message}", e)
            return@withContext Result.failure(e)
        }
    }

    suspend fun pairManually(ip: String, port: Int, token: String, pcName: String): Result<String> = withContext(Dispatchers.IO) {
        val payload = QrPayload(
            service = "_photobackup._tcp.local.",
            token = token,
            pcName = pcName.ifBlank { "Manual PC" },
            port = port,
            localIp = ip,
            ips = listOf(ip)
        )
        return@withContext executePairing(payload)
    }

    private suspend fun executePairing(payload: QrPayload): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Try mDNS resolve first
            var resolvedIp = payload.localIp
            var resolvedPort = payload.port
            var resolved = false
            
            Log.d("DataRepository", "Attempting mDNS resolution for ${payload.pcName}...")
            val resolvedMdns = kotlinx.coroutines.withTimeoutOrNull(3000) {
                NsdResolver.resolve(context, payload.pcName)
            }
            if (resolvedMdns != null) {
                resolvedIp = resolvedMdns.first
                resolvedPort = resolvedMdns.second
                resolved = true
                Log.d("DataRepository", "mDNS resolve succeeded: $resolvedIp:$resolvedPort")
            } else {
                Log.w("DataRepository", "mDNS resolve failed/timed out, trying fallback IPs...")
            }

            val deviceName = Build.MODEL ?: "Android Device"
            val requestBodyJson = """{"token":"${payload.token}","deviceName":"$deviceName"}"""
            val body = requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())

            val ipList = mutableListOf<String>()
            payload.ips?.let { ipList.addAll(it) }
            if (!ipList.contains(payload.localIp)) {
                ipList.add(payload.localIp)
            }

            var verifiedResponse: Pair<String, String>? = null // Pair(resolvedIp, deviceId)

            if (resolved) {
                val url = "http://$resolvedIp:$resolvedPort/pair/verify"
                try {
                    val request = Request.Builder().url(url).post(body).build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val responseBody = response.body?.string() ?: ""
                            val jsonObject = json.parseToJsonElement(responseBody)
                            val deviceId = jsonObject.jsonObject["deviceId"]?.jsonPrimitive?.content
                            if (deviceId != null) {
                                verifiedResponse = Pair(resolvedIp, deviceId)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("DataRepository", "mDNS resolved connection failed: ${e.message}, falling back to IP list")
                }
            }

            if (verifiedResponse == null) {
                for (ip in ipList) {
                    val url = "http://$ip:${payload.port}/pair/verify"
                    Log.d("DataRepository", "Testing verify connection: $url")
                    try {
                        val request = Request.Builder().url(url).post(body).build()
                        httpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val responseBody = response.body?.string() ?: ""
                                val jsonObject = json.parseToJsonElement(responseBody)
                                val deviceId = jsonObject.jsonObject["deviceId"]?.jsonPrimitive?.content
                                if (deviceId != null) {
                                    resolvedIp = ip
                                    resolvedPort = payload.port
                                    verifiedResponse = Pair(ip, deviceId)
                                    Log.d("DataRepository", "Verified connection on IP: $resolvedIp:$resolvedPort")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("DataRepository", "IP $ip:${payload.port} unreachable during verify: ${e.message}")
                    }
                    if (verifiedResponse != null) break
                }
            }

            val finalResponse = verifiedResponse ?: return@withContext Result.failure(IOException("Server is unreachable on all network interfaces. Check if PC and phone are on the same Wi-Fi or connected to Tailscale."))

            val finalIp = finalResponse.first
            val deviceId = finalResponse.second

            // Re-order ipList so that the working IP is first
            ipList.remove(finalIp)
            ipList.add(0, finalIp)
            val fallbackString = ipList.joinToString(",") { "$it:${payload.port}" }

            // Save to Room
            val server = PairedServer(
                serviceName = payload.service,
                pcName = payload.pcName,
                token = payload.token,
                deviceId = deviceId,
                fallbackIp = fallbackString,
                pairedAt = System.currentTimeMillis()
            )
            
            val serverId = pairedServerDao.insertServer(server)
            scanMediaAndInsert(serverId)
            
            return@withContext Result.success(payload.pcName)
        } catch (e: Exception) {
            Log.e("DataRepository", "executePairing exception: ${e.message}", e)
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

    override fun isSyncOnCellularTailscale(): Boolean {
        return context.getSharedPreferences("localsync_prefs", Context.MODE_PRIVATE)
            .getBoolean("sync_on_cellular_tailscale", false)
    }

    override fun setSyncOnCellularTailscale(enabled: Boolean) {
        context.getSharedPreferences("localsync_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("sync_on_cellular_tailscale", enabled)
            .apply()
    }

    override suspend fun deleteMediaItem(item: MediaItem) = withContext(Dispatchers.IO) {
        mediaItemDao.deleteItem(item)
    }

    override suspend fun deleteMediaItems(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        mediaItemDao.deleteItems(items)
    }
}
