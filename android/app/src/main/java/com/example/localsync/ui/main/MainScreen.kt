package com.example.localsync.ui.main

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.localsync.MediaViewer
import com.example.localsync.data.BackupStatus
import com.example.localsync.data.DataRepository
import com.example.localsync.data.MediaItem
import com.example.localsync.data.MediaType
import com.example.localsync.service.BackupForegroundService
import com.example.localsync.ui.pairing.PairingScreen
import com.example.localsync.ui.photos.PhotosScreen
import com.example.localsync.ui.search.SearchScreen
import com.example.localsync.ui.settings.SettingsScreen
import kotlinx.coroutines.launch
import java.io.File

enum class MainTab {
    PHOTOS, SEARCH, SETTINGS
}

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val repository = remember { DataRepository(appContext) }
    val viewModel: MainScreenViewModel = viewModel {
        MainScreenViewModel(repository)
    }

    val pairedServer by viewModel.pairedServer.collectAsStateWithLifecycle()
    val mediaItems by viewModel.mediaItems.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val backedUpCount by viewModel.backedUpCount.collectAsStateWithLifecycle()
    val isPaused by viewModel.isSyncPaused.collectAsStateWithLifecycle()

    // Contextual selection states
    val selectedItems = remember { mutableStateListOf<MediaItem>() }
    var isSelectionMode by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { }
    )

    // Automatically trigger notification permission check on Android 13+
    LaunchedEffect(pairedServer) {
        if (pairedServer != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    // Monitor pairing status and start/stop the foreground backup service
    LaunchedEffect(pairedServer) {
        if (pairedServer != null) {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = "ACTION_START_SYNC"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            val intent = Intent(context, BackupForegroundService::class.java)
            context.stopService(intent)
        }
    }

    // Trigger service re-sync when sync is unpaused in settings
    LaunchedEffect(isPaused) {
        if (!isPaused && pairedServer != null) {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = "ACTION_START_SYNC"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    // Reset selection mode if pairedServer is unpaired
    LaunchedEffect(pairedServer) {
        if (pairedServer == null) {
            selectedItems.clear()
            isSelectionMode = false
        }
    }

    // Delete confirmation alert dialog
    if (showDeleteDialog) {
        val allBackedUp = selectedItems.all { it.backupStatus == BackupStatus.DONE }
        val dialogMessage = if (allBackedUp) {
            "These items can be deleted safely as they are already backed up to your PC."
        } else {
            "Warning: Some items are not backed up yet. Are you sure you want to delete them from your device?"
        }

        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(text = "Delete from device?") },
            text = { Text(text = dialogMessage) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        val itemsToDelete = selectedItems.toList()
                        selectedItems.clear()
                        isSelectionMode = false
                        
                        coroutineScope.launch {
                            for (item in itemsToDelete) {
                                // 1. Delete local file
                                try {
                                    val file = File(item.filePath)
                                    if (file.exists()) {
                                        file.delete()
                                    }
                                } catch (e: Exception) {
                                    Log.e("MainScreen", "Failed to delete file: ${item.filePath}", e)
                                }
                                
                                // 2. Delete MediaStore record
                                try {
                                    val uri = ContentUris.withAppendedId(
                                        if (item.mediaType == MediaType.VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                        item.mediaId
                                    )
                                    context.contentResolver.delete(uri, null, null)
                                } catch (e: Exception) {
                                    Log.e("MainScreen", "Failed to delete from MediaStore: ${item.mediaId}", e)
                                }

                                // 3. Delete from Local Database
                                viewModel.deleteMediaItem(item)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (pairedServer == null) {
        PairingScreen(
            repository = repository,
            modifier = modifier
        )
    } else {
        var currentTab by remember { mutableStateOf(MainTab.PHOTOS) }

        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                if (isSelectionMode) {
                    // Google Photos contextual top action bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .height(56.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            selectedItems.clear()
                            isSelectionMode = false
                        }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel selection",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "${selectedItems.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            shareSelectedMedia(context, selectedItems)
                        }) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share selected",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        IconButton(onClick = {
                            showDeleteDialog = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete selected",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentTab == MainTab.PHOTOS,
                        onClick = { currentTab = MainTab.PHOTOS },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Photos") },
                        label = { Text("Photos") }
                    )
                    NavigationBarItem(
                        selected = currentTab == MainTab.SEARCH,
                        onClick = { currentTab = MainTab.SEARCH },
                        icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                        label = { Text("Search") }
                    )
                    NavigationBarItem(
                        selected = currentTab == MainTab.SETTINGS,
                        onClick = { currentTab = MainTab.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") }
                    )
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (currentTab) {
                    MainTab.PHOTOS -> {
                        PhotosScreen(
                            items = mediaItems,
                            selectedItems = selectedItems,
                            isSelectionMode = isSelectionMode,
                            onItemClick = { item ->
                                if (isSelectionMode) {
                                    if (selectedItems.contains(item)) {
                                        selectedItems.remove(item)
                                        if (selectedItems.isEmpty()) {
                                            isSelectionMode = false
                                        }
                                    } else {
                                        selectedItems.add(item)
                                    }
                                } else {
                                    val index = mediaItems.indexOf(item)
                                    if (index != -1) {
                                        onItemClick(MediaViewer(index))
                                    }
                                }
                            },
                            onItemLongClick = { item ->
                                if (!isSelectionMode) {
                                    isSelectionMode = true
                                    selectedItems.add(item)
                                }
                            }
                        )
                    }
                    MainTab.SEARCH -> {
                        SearchScreen(
                            items = mediaItems,
                            selectedItems = selectedItems,
                            isSelectionMode = isSelectionMode,
                            onItemClick = { item ->
                                if (isSelectionMode) {
                                    if (selectedItems.contains(item)) {
                                        selectedItems.remove(item)
                                        if (selectedItems.isEmpty()) {
                                            isSelectionMode = false
                                        }
                                    } else {
                                        selectedItems.add(item)
                                    }
                                } else {
                                    val index = mediaItems.indexOf(item)
                                    if (index != -1) {
                                        onItemClick(MediaViewer(index))
                                    }
                                }
                            },
                            onItemLongClick = { item ->
                                if (!isSelectionMode) {
                                    isSelectionMode = true
                                    selectedItems.add(item)
                                }
                            }
                        )
                    }
                    MainTab.SETTINGS -> {
                        SettingsScreen(
                            pairedServer = pairedServer!!,
                            totalCount = totalCount,
                            backedUpCount = backedUpCount,
                            isPaused = isPaused,
                            onPauseToggle = { viewModel.toggleSyncPause(it) },
                            onBackupNowClick = {
                                viewModel.scanLocalMedia()
                                // Force wake up service
                                val intent = Intent(context, BackupForegroundService::class.java).apply {
                                    action = "ACTION_START_SYNC"
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                            },
                            onUnpairClick = { viewModel.unpair() }
                        )
                    }
                }
            }
        }
    }
}

private fun shareSelectedMedia(context: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val uris = ArrayList<android.net.Uri>()
    for (item in items) {
        val uri = ContentUris.withAppendedId(
            if (item.mediaType == MediaType.VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            item.mediaId
        )
        uris.add(uri)
    }

    val intent = Intent().apply {
        if (uris.size == 1) {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            action = Intent.ACTION_SEND_MULTIPLE
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        type = if (items.all { it.mediaType == MediaType.PHOTO }) "image/*"
               else if (items.all { it.mediaType == MediaType.VIDEO }) "video/*"
               else "*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    
    context.startActivity(Intent.createChooser(intent, "Share media via"))
}
