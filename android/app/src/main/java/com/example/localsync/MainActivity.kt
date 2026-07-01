package com.example.localsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.localsync.data.AppDatabase
import com.example.localsync.theme.LocalSyncTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Reset any stale uploads from last session
    lifecycleScope.launch {
      try {
        AppDatabase.getDatabase(applicationContext).mediaItemDao().resetUploadingStatus()
      } catch (e: Exception) {
        android.util.Log.e("MainActivity", "Failed to reset uploading status: ${e.message}")
      }
    }

    enableEdgeToEdge()
    setContent {
      LocalSyncTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }
  }
}
