package com.example.localsync.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.localsync.data.DataRepository
import com.example.localsync.data.LocalSyncRepository
import com.example.localsync.data.MediaItem
import com.example.localsync.data.PairedServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainScreenViewModel(private val repository: LocalSyncRepository) : ViewModel() {
    
    val pairedServer: StateFlow<PairedServer?> = repository.pairedServerFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val mediaItems: StateFlow<List<MediaItem>> = repository.mediaItemsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalCount: StateFlow<Int> = repository.totalCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val backedUpCount: StateFlow<Int> = repository.backedUpCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _isSyncPaused = MutableStateFlow(repository.isSyncPaused())
    val isSyncPaused: StateFlow<Boolean> = _isSyncPaused.asStateFlow()

    fun toggleSyncPause(paused: Boolean) {
        repository.setSyncPaused(paused)
        _isSyncPaused.value = paused
    }

    fun scanLocalMedia() {
        viewModelScope.launch {
            repository.scanLocalMedia()
        }
    }

    fun unpair() {
        viewModelScope.launch {
            repository.unpair()
        }
    }
}
