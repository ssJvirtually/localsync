package com.example.localsync

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey
@Serializable data class MediaViewer(val initialIndex: Int) : NavKey
