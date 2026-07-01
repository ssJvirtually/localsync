package com.example.localsync.ui.main

import com.example.localsync.data.*
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MainScreenViewModelTest {

  @Test
  fun testInitialState() = runTest {
    val fakeRepo = FakeLocalSyncRepository()
    val viewModel = MainScreenViewModel(fakeRepo)
    
    // Test initial state flows
    assertEquals(null, viewModel.pairedServer.value)
    assertEquals(emptyList<MediaItem>(), viewModel.mediaItems.value)
    assertEquals(0, viewModel.totalCount.value)
    assertEquals(0, viewModel.backedUpCount.value)
    assertFalse(viewModel.isSyncPaused.value)
  }
}

private class FakeLocalSyncRepository : LocalSyncRepository {
    override val pairedServerFlow: Flow<PairedServer?> = flowOf(null)
    override val mediaItemsFlow: Flow<List<MediaItem>> = flowOf(emptyList())
    override val totalCountFlow: Flow<Int> = flowOf(0)
    override val backedUpCountFlow: Flow<Int> = flowOf(0)
    override fun isSyncPaused(): Boolean = false
    override fun setSyncPaused(paused: Boolean) {}
    override suspend fun scanLocalMedia() {}
    override suspend fun unpair() {}
}
