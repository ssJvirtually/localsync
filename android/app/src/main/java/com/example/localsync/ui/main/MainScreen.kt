package com.example.localsync.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.localsync.data.DataRepository
import com.example.localsync.ui.pairing.PairingScreen
import com.example.localsync.ui.photos.PhotosScreen
import com.example.localsync.ui.search.SearchScreen
import com.example.localsync.ui.settings.SettingsScreen

enum class MainTab {
    PHOTOS, SEARCH, SETTINGS
}

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val repository = remember { DataRepository(context) }
    val viewModel: MainScreenViewModel = viewModel {
        MainScreenViewModel(repository)
    }

    val pairedServer by viewModel.pairedServer.collectAsStateWithLifecycle()
    val mediaItems by viewModel.mediaItems.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val backedUpCount by viewModel.backedUpCount.collectAsStateWithLifecycle()
    val isPaused by viewModel.isSyncPaused.collectAsStateWithLifecycle()

    if (pairedServer == null) {
        PairingScreen(
            repository = repository,
            modifier = modifier
        )
    } else {
        var currentTab by remember { mutableStateOf(MainTab.PHOTOS) }

        Scaffold(
            modifier = modifier.fillMaxSize(),
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
                        PhotosScreen(items = mediaItems)
                    }
                    MainTab.SEARCH -> {
                        SearchScreen(items = mediaItems)
                    }
                    MainTab.SETTINGS -> {
                        SettingsScreen(
                            pairedServer = pairedServer!!,
                            totalCount = totalCount,
                            backedUpCount = backedUpCount,
                            isPaused = isPaused,
                            onPauseToggle = { viewModel.toggleSyncPause(it) },
                            onBackupNowClick = { viewModel.scanLocalMedia() },
                            onUnpairClick = { viewModel.unpair() }
                        )
                    }
                }
            }
        }
    }
}
