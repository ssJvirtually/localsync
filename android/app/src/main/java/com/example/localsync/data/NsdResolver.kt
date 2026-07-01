package com.example.localsync.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet6Address
import kotlin.coroutines.resume

object NsdResolver {
    private const val TAG = "NsdResolver"

    suspend fun resolve(context: Context, serviceName: String): Pair<String, Int>? = suspendCancellableCoroutine { continuation ->
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for $serviceName with error code: $errorCode")
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host
                val port = serviceInfo.port
                val ipAddress = host?.hostAddress ?: ""
                Log.d(TAG, "Service resolved: $serviceName -> $ipAddress:$port")

                if (continuation.isActive) {
                    if (host is Inet6Address) {
                        // Keep parsing or use fallback, but let's return it and let HTTP client try it
                        continuation.resume(Pair("[$ipAddress]", port))
                    } else {
                        continuation.resume(Pair(ipAddress, port))
                    }
                }
            }
        }

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = "_photobackup._tcp."
        }

        try {
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting resolve: ${e.message}", e)
            if (continuation.isActive) {
                continuation.resume(null)
            }
        }
    }
}
