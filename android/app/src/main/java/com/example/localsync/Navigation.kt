package com.example.localsync

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.localsync.data.DataRepository
import com.example.localsync.ui.main.MainScreen
import com.example.localsync.ui.photos.MediaViewerScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)
  val context = LocalContext.current.applicationContext
  val repository = remember { DataRepository(context) }
  val mediaItemsState = repository.mediaItemsFlow.collectAsState(initial = emptyList())

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          MainScreen(onItemClick = { navKey -> backStack.add(navKey) }, modifier = Modifier.safeDrawingPadding().padding(16.dp))
        }
        entry<MediaViewer> { key ->
          MediaViewerScreen(
            items = mediaItemsState.value,
            initialIndex = key.initialIndex,
            onBack = { backStack.removeLastOrNull() }
          )
        }
      },
  )
}
