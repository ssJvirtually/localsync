package com.example.localsync

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey
@Serializable data class MediaViewer(val filePath: String, val mediaType: String) : NavKey
