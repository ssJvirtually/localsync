package com.example.localsync.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.localsync.data.DataRepository
import com.example.localsync.service.BackupForegroundService

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "Periodic SyncWorker triggered.")
        val repository = DataRepository(applicationContext)
        val server = repository.getPairedServer()
        
        if (server != null && !repository.isSyncPaused()) {
            val intent = Intent(applicationContext, BackupForegroundService::class.java).apply {
                action = "ACTION_START_SYNC"
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("SyncWorker", "Failed to start BackupForegroundService from Worker: ${e.message}")
            }
        }
        
        return Result.success()
    }
}
