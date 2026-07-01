package com.example.localsync.ui.photos

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.example.localsync.data.BackupStatus
import com.example.localsync.data.MediaItem
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PhotosScreen(
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    // Group photos by Month Year
    val formatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy") }
    val groupedItems = remember(items) {
        items.groupBy { item ->
            try {
                val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(item.dateTaken), ZoneId.systemDefault())
                ldt.format(formatter)
            } catch (e: Exception) {
                "Unknown Date"
            }
        }
    }

    if (items.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No photos found on device.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            groupedItems.forEach { (monthName, monthItems) ->
                item {
                    Text(
                        text = monthName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                // Chunk month items into rows of 4 for a grid feel
                val chunked = monthItems.chunked(4)
                items(chunked) { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (i in 0 until 4) {
                            if (i < rowItems.size) {
                                PhotoTile(
                                    item = rowItems[i],
                                    onItemClick = onItemClick,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PhotoTile(
    item: MediaItem,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onItemClick(item) }
    ) {
        Image(
            painter = rememberAsyncImagePainter(model = File(item.filePath)),
            contentDescription = item.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Status Overlay
        when (item.backupStatus) {
            BackupStatus.DONE -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Synced",
                    tint = Color.Green,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .size(20.dp)
                )
            }
            BackupStatus.UPLOADING -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(16.dp)
                )
            }
            else -> {
                // PENDING / FAILED show nothing as per v1 specification
            }
        }
    }
}
