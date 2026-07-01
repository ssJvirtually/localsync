package com.example.localsync.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.localsync.MainActivity
import com.example.localsync.R
import com.example.localsync.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

class BackupForegroundService : Service() {
    private companion object {
        const val TAG = "BackupService"
        const val NOTIFICATION_CHANNEL_ID = "localsync_backup_channel"
        const val NOTIFICATION_ID = 1001
        
        // In-memory progress state for UI observers
        val _uploadProgress = MutableStateFlow<Map<Long, Int>>(emptyMap())
        val uploadProgress = _uploadProgress.asStateFlow()
        
        var isServiceRunning = false
    }

    private lateinit var repository: DataRepository
    private lateinit var mediaItemDao: MediaItemDao
    private lateinit var connectivityManager: ConnectivityManager
    
    @Volatile private var photoSemaphore = Semaphore(4)
    @Volatile private var videoSemaphore = Semaphore(2)

    private val powerReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateConcurrencyLimits()
        }
    }
    
    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var syncJob: Job? = null
    
    private var isWifiConnected = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network became available")
            checkWifiAndTriggerSync()
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost")
            isWifiConnected = false
            stopActiveSync("Waiting for Wi-Fi...")
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        repository = DataRepository(applicationContext)
        mediaItemDao = AppDatabase.getDatabase(applicationContext).mediaItemDao()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        createNotificationChannel()
        registerNetworkCallback()
        
        // Register power connection broadcast receiver
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(powerReceiver, filter)
        updateConcurrencyLimits()
        
        // Start Foreground immediately
        startForeground(
            NOTIFICATION_ID, 
            buildNotification("Initializing...", "Scanning local photo queue"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else 0
        )
        
        checkWifiAndTriggerSync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "ACTION_START_SYNC") {
            checkWifiAndTriggerSync()
        } else if (action == "ACTION_STOP_SERVICE") {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        unregisterNetworkCallback()
        
        try {
            unregisterReceiver(powerReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister power receiver: ${e.message}")
        }
        
        serviceScope.launch {
            // Cancel all work
            serviceJob.cancel()
            // Reset any item in database marked as UPLOADING back to PENDING
            mediaItemDao.resetUploadingStatus()
            Log.d(TAG, "Reset uploading statuses in database.")
        }
        
        Log.d(TAG, "Service destroyed")
    }

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    private fun unregisterNetworkCallback() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister network callback: ${e.message}")
        }
    }

    private fun checkWifiAndTriggerSync() {
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        isWifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        
        if (isWifiConnected) {
            startActiveSync()
        } else {
            stopActiveSync("Waiting for Wi-Fi...")
        }
    }

    @Synchronized
    private fun startActiveSync() {
        if (repository.isSyncPaused()) {
            stopActiveSync("Sync is paused")
            return
        }

        if (syncJob?.isActive == true) {
            Log.d(TAG, "Sync loop already running.")
            return
        }

        syncJob = serviceScope.launch {
            Log.d(TAG, "Starting sync loop...")
            try {
                // Ensure media items are scanned and up to date before processing queue
                repository.scanLocalMedia()
                
                while (isActive && isWifiConnected && !repository.isSyncPaused()) {
                    val server = repository.getPairedServer()
                    if (server == null) {
                        stopActiveSync("Not paired to any PC")
                        break
                    }

                    // Pull pending items in small batches to reduce memory footprint
                    val pendingItems = mediaItemDao.getItemsByStatus(BackupStatus.PENDING).take(30)
                    if (pendingItems.isEmpty()) {
                        Log.d(TAG, "No more pending items in queue. Sync complete.")
                        updateNotificationProgress(0, 0, "All items backed up")
                        break
                    }

                    Log.d(TAG, "Processing batch of ${pendingItems.size} items...")
                    val batchJobs = pendingItems.map { item ->
                        launch {
                            val semaphore = if (item.mediaType == MediaType.PHOTO) photoSemaphore else videoSemaphore
                            semaphore.withPermit {
                                processUpload(item, server)
                            }
                        }
                    }
                    
                    // Wait for the entire batch to complete/fail
                    batchJobs.joinAll()
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Sync loop cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "Error in sync loop: ${e.message}", e)
                updateNotificationProgress(0, 0, "Sync error occurred")
            }
        }
    }

    private suspend fun processUpload(item: MediaItem, server: PairedServer) {
        // 1. Mark as UPLOADING in DB
        val uploadingItem = item.copy(backupStatus = BackupStatus.UPLOADING)
        mediaItemDao.updateItem(uploadingItem)
        
        _uploadProgress.value = _uploadProgress.value + (item.mediaId to 0)
        
        // 2. Perform upload
        val result = UploadPipeline.uploadItem(applicationContext, item, server) { progress ->
            _uploadProgress.value = _uploadProgress.value + (item.mediaId to progress)
            // Update notification status occasionally for large videos
            if (item.mediaType == MediaType.VIDEO) {
                updateNotificationProgress(progress, 100, "Uploading: ${item.fileName}")
            }
        }

        // 3. Handle result
        if (result.isSuccess) {
            val hash = result.getOrThrow()
            val doneItem = item.copy(backupStatus = BackupStatus.DONE, fileHash = hash)
            mediaItemDao.updateItem(doneItem)
            Log.d(TAG, "Successfully backed up: ${item.fileName}")
        } else {
            val failedItem = item.copy(
                backupStatus = BackupStatus.PENDING, // Back to PENDING to retry next time
                lastAttemptAt = System.currentTimeMillis()
            )
            mediaItemDao.updateItem(failedItem)
            Log.e(TAG, "Failed backing up: ${item.fileName}: ${result.exceptionOrNull()?.message}")
        }
        
        // Remove from active progress map
        _uploadProgress.value = _uploadProgress.value - item.mediaId
    }

    @Synchronized
    private fun stopActiveSync(statusMessage: String = "Stopped") {
        if (syncJob?.isActive == true) {
            Log.d(TAG, "Stopping active sync: $statusMessage")
            syncJob?.cancel()
            syncJob = null
        }
        
        // Clear UI progress overlays
        _uploadProgress.value = emptyMap()
        
        updateNotificationProgress(0, 0, statusMessage)
    }

    private fun updateNotificationProgress(progress: Int, max: Int, message: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildNotification(message, if (max > 0) "$progress% completed" else null)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, content: String?): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_upload) // standard system icon for upload
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "LocalSync Backup Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun updateConcurrencyLimits() {
        val isCharging = checkIsCharging()
        val newPhotoLimit = if (isCharging) 6 else 4
        val newVideoLimit = if (isCharging) 3 else 2
        
        Log.d(TAG, "Updating concurrency limits. Charging: $isCharging. Limits -> Photos: $newPhotoLimit, Videos: $newVideoLimit")
        
        photoSemaphore = Semaphore(newPhotoLimit)
        videoSemaphore = Semaphore(newVideoLimit)
    }

    private fun checkIsCharging(): Boolean {
        return try {
            val intent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            Log.e(TAG, "Error checking charging status: ${e.message}")
            false
        }
    }
}
