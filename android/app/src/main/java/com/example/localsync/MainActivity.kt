package com.example.localsync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import kotlinx.coroutines.launch
import com.example.localsync.data.AppDatabase
import com.example.localsync.data.DataRepository
import com.example.localsync.theme.LocalSyncTheme
import com.example.localsync.worker.SyncWorker
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

  private val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    arrayOf(
      Manifest.permission.READ_MEDIA_IMAGES,
      Manifest.permission.READ_MEDIA_VIDEO
    )
  } else {
    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
  }

  private val requestPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { permissions ->
    val allGranted = permissions.values.all { it }
    if (allGranted) {
      Log.d("MainActivity", "All media permissions granted. Scanning media...")
      triggerMediaScan()
    } else {
      Log.w("MainActivity", "Media permissions denied.")
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Reset any stale uploads from last session
    lifecycleScope.launch {
      try {
        AppDatabase.getDatabase(applicationContext).mediaItemDao().resetUploadingStatus()
      } catch (e: Exception) {
        Log.e("MainActivity", "Failed to reset uploading status: ${e.message}")
      }
    }

    // Schedule background WorkManager task
    scheduleBackgroundSync()

    // Request permissions on app open
    checkAndRequestPermissions()

    enableEdgeToEdge()
    setContent {
      LocalSyncTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }
  }

  private fun checkAndRequestPermissions() {
    val missingPermissions = permissionsToRequest.filter {
      ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    if (missingPermissions.isNotEmpty()) {
      Log.d("MainActivity", "Requesting missing permissions: $missingPermissions")
      requestPermissionLauncher.launch(missingPermissions.toTypedArray())
    } else {
      Log.d("MainActivity", "All permissions already granted. Scanning media...")
      triggerMediaScan()
    }
  }

  private fun triggerMediaScan() {
    lifecycleScope.launch {
      try {
        val repository = DataRepository(applicationContext)
        repository.scanLocalMedia()
      } catch (e: Exception) {
        Log.e("MainActivity", "Failed to run media scan: ${e.message}")
      }
    }
  }

  private fun scheduleBackgroundSync() {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED) // Only run on Wi-Fi
        .setRequiresBatteryNotLow(true)
        .build()

    val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
        .setConstraints(constraints)
        .build()

    WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
        "LocalSyncPeriodicBackup",
        ExistingPeriodicWorkPolicy.KEEP,
        syncRequest
    )
  }
}
