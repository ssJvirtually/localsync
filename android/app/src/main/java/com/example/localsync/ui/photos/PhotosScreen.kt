package com.example.localsync.ui.photos

import android.content.ContentUris
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.example.localsync.data.BackupStatus
import com.example.localsync.data.MediaItem
import com.example.localsync.data.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Custom Scrollbar Modifier
fun Modifier.verticalScrollbar(
    state: LazyListState,
    width: Dp = 6.dp,
    color: Color = Color.Gray.copy(alpha = 0.5f)
): Modifier = this.drawWithContent {
    drawContent()
    
    val layoutInfo = state.layoutInfo
    val totalItemsCount = layoutInfo.totalItemsCount
    
    if (totalItemsCount > 0) {
        val firstVisibleItemIndex = state.firstVisibleItemIndex
        val firstVisibleItemScrollOffset = state.firstVisibleItemScrollOffset
        
        val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
        val visibleItems = layoutInfo.visibleItemsInfo
        
        if (visibleItems.isNotEmpty()) {
            val totalHeight = visibleItems.sumOf { it.size }
            val avgItemHeight = totalHeight.toFloat() / visibleItems.size
            val estimatedTotalHeight = avgItemHeight * totalItemsCount
            
            if (estimatedTotalHeight > viewportHeight) {
                val scrollOffset = firstVisibleItemIndex * avgItemHeight + firstVisibleItemScrollOffset
                val thumbHeight = (viewportHeight / estimatedTotalHeight) * viewportHeight
                val thumbMinHeight = 32.dp.toPx()
                val finalThumbHeight = maxOf(thumbHeight, thumbMinHeight)
                
                val scrollRange = estimatedTotalHeight - viewportHeight
                val thumbRange = viewportHeight - finalThumbHeight
                val thumbTop = (scrollOffset / scrollRange) * thumbRange
                
                drawRect(
                    color = color,
                    topLeft = Offset(
                        x = this.size.width - width.toPx(),
                        y = thumbTop
                    ),
                    size = androidx.compose.ui.geometry.Size(
                        width = width.toPx(),
                        height = finalThumbHeight
                    )
                )
            }
        }
    }
}

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
        val listState = rememberLazyListState()

        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .verticalScrollbar(listState),
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
                                val itemIndex = items.indexOf(rowItems[i])
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
fun VideoThumbnail(item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var thumbnail by remember(item.mediaId) { mutableStateOf<Bitmap?>(null) }
    
    LaunchedEffect(item.mediaId) {
        withContext(Dispatchers.IO) {
            try {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    item.mediaId
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val size = Size(256, 256)
                    thumbnail = context.contentResolver.loadThumbnail(uri, size, null)
                } else {
                    @Suppress("DEPRECATION")
                    thumbnail = MediaStore.Video.Thumbnails.getThumbnail(
                        context.contentResolver,
                        item.mediaId,
                        MediaStore.Video.Thumbnails.MINI_KIND,
                        null
                    )
                }
            } catch (e: Exception) {
                Log.e("VideoThumbnail", "Error loading video thumbnail: ${e.message}")
            }
        }
    }

    val bitmap = thumbnail
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = item.fileName,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        // Video Icon Placeholder
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
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
        if (item.mediaType == MediaType.VIDEO) {
            VideoThumbnail(
                item = item,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Image(
                painter = rememberAsyncImagePainter(model = File(item.filePath)),
                contentDescription = item.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

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
                // PENDING / FAILED show nothing
            }
        }
    }
}
