package com.example.localsync.ui.photos

import android.content.ContentUris
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.example.localsync.data.BackupStatus
import com.example.localsync.data.MediaItem
import com.example.localsync.data.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed class GalleryItem {
    data class Header(val date: String) : GalleryItem()
    data class PhotoItem(val photo: MediaItem) : GalleryItem()
}

private val gridHeaderFormatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy", java.util.Locale.getDefault())
private val gridMonthYearFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.getDefault())

val ScrollHandleIcon = ImageVector.Builder(
    name = "ScrollHandle",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).path(
    fill = SolidColor(Color.DarkGray)
) {
    // Up arrow
    moveTo(12f, 5f)
    lineTo(7f, 10f)
    lineTo(17f, 10f)
    close()
    // Down arrow
    moveTo(12f, 19f)
    lineTo(7f, 14f)
    lineTo(17f, 14f)
    close()
}.build()

@Composable
fun PhotosScreen(
    items: List<MediaItem>,
    selectedItems: SnapshotStateList<MediaItem>,
    isSelectionMode: Boolean,
    onSelectionModeChange: (Boolean) -> Unit,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    // Group photos by clean date header
    val groupedPhotosList = remember(items) {
        val list = mutableListOf<GalleryItem>()
        var lastDateHeader = ""

        for (photo in items) {
            val dateHeader = try {
                gridHeaderFormatter.format(Instant.ofEpochMilli(photo.dateTaken).atZone(ZoneId.systemDefault()))
            } catch (e: Exception) {
                "Unknown Date"
            }

            if (dateHeader != lastDateHeader) {
                list.add(GalleryItem.Header(dateHeader))
                lastDateHeader = dateHeader
            }
            list.add(GalleryItem.PhotoItem(photo))
        }
        list
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
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val containerHeightPx = constraints.maxHeight.toFloat()
            val gridState = rememberLazyGridState()
            val coroutineScope = rememberCoroutineScope()
            val haptic = LocalHapticFeedback.current

            // Drag selection state variables
            var isDraggingToSelect by remember { mutableStateOf(false) }
            var dragStartPhotoIndex by remember { mutableStateOf<Int?>(null) }
            var dragCurrentPhotoIndex by remember { mutableStateOf<Int?>(null) }
            var dragCurrentPosition by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
            var initialSelection by remember { mutableStateOf(emptyList<MediaItem>()) }
            var isSelecting by remember { mutableStateOf(true) }

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(groupedPhotosList) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { startOffset ->
                                val startIndex = gridState.getItemIndexAt(startOffset)
                                val startItem = startIndex?.let { groupedPhotosList.getOrNull(it) }
                                if (startItem is GalleryItem.PhotoItem) {
                                    dragStartPhotoIndex = startIndex
                                    dragCurrentPhotoIndex = startIndex
                                    dragCurrentPosition = startOffset
                                    isDraggingToSelect = true
                                    initialSelection = selectedItems.toList()
                                    isSelecting = !initialSelection.any { it.mediaId == startItem.photo.mediaId }
                                    
                                    if (isSelecting) {
                                        if (!selectedItems.any { it.mediaId == startItem.photo.mediaId }) {
                                            selectedItems.add(startItem.photo)
                                        }
                                    } else {
                                        selectedItems.removeAll { it.mediaId == startItem.photo.mediaId }
                                    }
                                    onSelectionModeChange(true)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    
                                    // Start Auto-Scroll loop during drag
                                    coroutineScope.launch {
                                        while (isDraggingToSelect) {
                                            val currentY = dragCurrentPosition.y
                                            val containerHeight = containerHeightPx
                                            var scrollDelta = 0f
                                            
                                            if (currentY < 150f) {
                                                scrollDelta = -30f
                                            } else if (currentY > containerHeight - 150f) {
                                                scrollDelta = 30f
                                            }
                                            
                                            if (scrollDelta != 0f) {
                                                try {
                                                    gridState.scrollBy(scrollDelta)
                                                    val currentIndex = gridState.getItemIndexAt(dragCurrentPosition)
                                                    if (currentIndex != null && currentIndex != dragCurrentPhotoIndex) {
                                                        dragCurrentPhotoIndex = currentIndex
                                                        val start = minOf(dragStartPhotoIndex!!, dragCurrentPhotoIndex!!)
                                                        val end = maxOf(dragStartPhotoIndex!!, dragCurrentPhotoIndex!!)
                                                        
                                                        val photosInRange = (start..end).mapNotNull { idx ->
                                                            (groupedPhotosList.getOrNull(idx) as? GalleryItem.PhotoItem)?.photo
                                                        }
                                                        
                                                        val targetIds = photosInRange.map { it.mediaId }.toSet()
                                                        
                                                        // 1. Remove items not in range and not in initial selection
                                                        selectedItems.removeAll { item ->
                                                            !initialSelection.any { it.mediaId == item.mediaId } && !targetIds.contains(item.mediaId)
                                                        }
                                                        
                                                        // 2. Add or remove items based on current isSelecting mode
                                                        for (photo in photosInRange) {
                                                            if (isSelecting) {
                                                                if (!selectedItems.any { it.mediaId == photo.mediaId }) {
                                                                    selectedItems.add(photo)
                                                                }
                                                            } else {
                                                                selectedItems.removeAll { it.mediaId == photo.mediaId }
                                                            }
                                                        }
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                } catch (e: Exception) {
                                                    // Ignore scroll bounds exception
                                                }
                                            }
                                            kotlinx.coroutines.delay(30)
                                        }
                                    }
                                }
                            },
                            onDragEnd = {
                                isDraggingToSelect = false
                                dragStartPhotoIndex = null
                                dragCurrentPhotoIndex = null
                            },
                            onDragCancel = {
                                isDraggingToSelect = false
                                dragStartPhotoIndex = null
                                dragCurrentPhotoIndex = null
                            },
                            onDrag = { change, dragAmount ->
                                if (isDraggingToSelect && dragStartPhotoIndex != null) {
                                    change.consume()
                                    dragCurrentPosition += dragAmount
                                    val currentIndex = gridState.getItemIndexAt(dragCurrentPosition)
                                    if (currentIndex != null && currentIndex != dragCurrentPhotoIndex) {
                                        dragCurrentPhotoIndex = currentIndex
                                        val start = minOf(dragStartPhotoIndex!!, dragCurrentPhotoIndex!!)
                                        val end = maxOf(dragStartPhotoIndex!!, dragCurrentPhotoIndex!!)
                                        
                                        val photosInRange = (start..end).mapNotNull { idx ->
                                            (groupedPhotosList.getOrNull(idx) as? GalleryItem.PhotoItem)?.photo
                                        }
                                        
                                        val targetIds = photosInRange.map { it.mediaId }.toSet()
                                        
                                        // 1. Remove items not in range and not in initial selection
                                        selectedItems.removeAll { item ->
                                            !initialSelection.any { it.mediaId == item.mediaId } && !targetIds.contains(item.mediaId)
                                        }
                                        
                                        // 2. Add or remove items based on isSelecting mode
                                        for (photo in photosInRange) {
                                            if (isSelecting) {
                                                if (!selectedItems.any { it.mediaId == photo.mediaId }) {
                                                    selectedItems.add(photo)
                                                }
                                            } else {
                                                selectedItems.removeAll { it.mediaId == photo.mediaId }
                                            }
                                        }
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            }
                        )
                    },
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(
                    count = groupedPhotosList.size,
                    span = { index ->
                        val item = groupedPhotosList[index]
                        val spanCount = if (item is GalleryItem.Header) 4 else 1
                        GridItemSpan(spanCount)
                    }
                ) { index ->
                    val item = groupedPhotosList[index]
                    when (item) {
                        is GalleryItem.Header -> {
                            Text(
                                text = item.date,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp)
                            )
                        }
                        is GalleryItem.PhotoItem -> {
                            PhotoTile(
                                item = item.photo,
                                isSelected = selectedItems.any { it.mediaId == item.photo.mediaId },
                                isSelectionMode = isSelectionMode,
                                onItemClick = onItemClick,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // --- Custom Premium Google Photos-style Scrollbar ---
            val totalItems = groupedPhotosList.size
            if (totalItems > 5) {
                val monthSections = remember(groupedPhotosList) {
                    val sections = mutableListOf<Int>()
                    val seenMonths = mutableSetOf<String>()
                    
                    for (index in groupedPhotosList.indices) {
                        val item = groupedPhotosList[index]
                        val dateMs = when (item) {
                            is GalleryItem.Header -> {
                                try {
                                    java.time.LocalDate.parse(item.date, gridHeaderFormatter)
                                        .atStartOfDay(ZoneId.systemDefault())
                                        .toInstant()
                                        .toEpochMilli()
                                } catch (e: Exception) {
                                    0L
                                }
                            }
                            is GalleryItem.PhotoItem -> item.photo.dateTaken
                        }
                        
                        if (dateMs > 0L) {
                            val monthKey = try {
                                gridMonthYearFormatter.format(Instant.ofEpochMilli(dateMs).atZone(ZoneId.systemDefault()))
                            } catch (e: Exception) {
                                ""
                            }
                            if (monthKey.isNotEmpty() && seenMonths.add(monthKey)) {
                                sections.add(index)
                            }
                        }
                    }
                    if (sections.isEmpty()) sections.add(0)
                    sections
                }

                val firstVisibleIndex = gridState.firstVisibleItemIndex
                val firstVisibleOffset = gridState.firstVisibleItemScrollOffset

                val scrollFraction = remember(firstVisibleIndex, firstVisibleOffset, totalItems) {
                    if (totalItems <= 1) 0f
                    else {
                        val itemFraction = firstVisibleIndex.toFloat() / totalItems.toFloat()
                        val detailOffset = if (gridState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                            val itemHeight = gridState.layoutInfo.visibleItemsInfo.first().size.height
                            if (itemHeight > 0) {
                                (firstVisibleOffset.toFloat() / itemHeight.toFloat()) / totalItems.toFloat()
                            } else 0f
                        } else 0f
                        (itemFraction + detailOffset).coerceIn(0f, 1f)
                    }
                }

                var isDragging by remember { mutableStateOf(false) }
                var dragOffsetFraction by remember { mutableStateOf(0f) }

                var scrollbarAlpha by remember { mutableStateOf(0f) }
                LaunchedEffect(gridState.isScrollInProgress, isDragging) {
                    if (gridState.isScrollInProgress || isDragging) {
                        scrollbarAlpha = 1f
                    } else {
                        kotlinx.coroutines.delay(1500)
                        scrollbarAlpha = 0f
                    }
                }

                val currentMonthKey = remember(firstVisibleIndex, monthSections) {
                    var activeSectionIdx = 0
                    for (i in monthSections.indices) {
                        if (monthSections[i] <= firstVisibleIndex) {
                            activeSectionIdx = i
                        } else {
                            break
                        }
                    }
                    activeSectionIdx
                }
                LaunchedEffect(currentMonthKey) {
                    if (gridState.isScrollInProgress || isDragging) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }

                val animatedAlpha by animateFloatAsState(
                    targetValue = scrollbarAlpha,
                    animationSpec = tween(durationMillis = 300),
                    label = "scrollbar_alpha"
                )

                if (animatedAlpha > 0f) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val paddingPx = with(density) { 32.dp.toPx() }
                    val trackHeightPx = containerHeightPx - (paddingPx * 2)

                    val thumbHeightDp = 36.dp
                    val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
                    val scrollableRangePx = trackHeightPx - thumbHeightPx

                    val activeFraction = if (isDragging) dragOffsetFraction else scrollFraction
                    val thumbYPx = paddingPx + (activeFraction * scrollableRangePx)
                    val thumbY = with(density) { thumbYPx.toDp() }

                    // Drag area
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(36.dp)
                            .graphicsLayer { alpha = animatedAlpha }
                            .pointerInput(containerHeightPx, scrollableRangePx) {
                                detectVerticalDragGestures(
                                    onDragStart = { startPosition ->
                                        isDragging = true
                                        val relativeY = (startPosition.y - paddingPx - (thumbHeightPx / 2))
                                        dragOffsetFraction = (relativeY / scrollableRangePx).coerceIn(0f, 1f)
                                        coroutineScope.launch {
                                            val targetSectionIndex = (dragOffsetFraction * monthSections.size).toInt().coerceIn(0, monthSections.size - 1)
                                            val targetGridIndex = monthSections[targetSectionIndex]
                                            gridState.scrollToItem(targetGridIndex)
                                        }
                                    },
                                    onDragEnd = { isDragging = false },
                                    onDragCancel = { isDragging = false },
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        val currentY = paddingPx + (dragOffsetFraction * scrollableRangePx)
                                        val newY = currentY + dragAmount
                                        dragOffsetFraction = ((newY - paddingPx) / scrollableRangePx).coerceIn(0f, 1f)
                                        coroutineScope.launch {
                                            val targetSectionIndex = (dragOffsetFraction * monthSections.size).toInt().coerceIn(0, monthSections.size - 1)
                                            val targetGridIndex = monthSections[targetSectionIndex]
                                            gridState.scrollToItem(targetGridIndex)
                                        }
                                    }
                                )
                            }
                    ) {
                        // Track line
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxHeight()
                                .padding(vertical = 32.dp)
                                .width(2.dp)
                                .background(
                                    color = if (isDragging) Color.Gray.copy(alpha = 0.3f)
                                    else Color.Gray.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(1.dp)
                                )
                        )
                        
                        // Handle Thumb
                        Box(
                            modifier = Modifier
                                .offset(y = thumbY)
                                .align(Alignment.TopEnd)
                                .padding(end = 4.dp)
                                .size(36.dp)
                                .shadow(
                                    elevation = if (isDragging) 8.dp else 4.dp,
                                    shape = CircleShape
                                )
                                .background(
                                    color = Color.White,
                                    shape = CircleShape
                                )
                                .border(
                                    width = 0.5.dp,
                                    color = Color.LightGray.copy(alpha = 0.5f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = ScrollHandleIcon,
                                contentDescription = "Scroll handle",
                                tint = Color.DarkGray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Floating Date Bubble
                    val activeSectionIndex = (activeFraction * (monthSections.size - 1)).toInt().coerceIn(0, monthSections.size - 1)
                    val activeItemIndex = monthSections.getOrNull(activeSectionIndex) ?: 0
                    val activeItem = groupedPhotosList.getOrNull(activeItemIndex)

                    val bubbleText = remember(activeItem) {
                        if (activeItem == null) ""
                        else {
                            when (activeItem) {
                                is GalleryItem.Header -> {
                                    try {
                                        val localDate = java.time.LocalDate.parse(activeItem.date, gridHeaderFormatter)
                                        gridMonthYearFormatter.format(localDate.atStartOfDay(ZoneId.systemDefault()))
                                    } catch (e: Exception) {
                                        ""
                                    }
                                }
                                is GalleryItem.PhotoItem -> {
                                    try {
                                        gridMonthYearFormatter.format(Instant.ofEpochMilli(activeItem.photo.dateTaken).atZone(ZoneId.systemDefault()))
                                    } catch (e: Exception) {
                                        ""
                                    }
                                }
                            }
                        }
                    }

                    if (isDragging && bubbleText.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .offset(y = thumbY - 8.dp)
                                .align(Alignment.TopEnd)
                                .padding(end = 48.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = bubbleText,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
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
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onItemClick(item) }
    ) {
        if (item.mediaType == MediaType.VIDEO) {
            VideoThumbnail(
                item = item,
                modifier = Modifier.fillMaxSize()
            )
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play Video",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(4.dp)
                    .size(14.dp)
            )
        } else {
            Image(
                painter = rememberAsyncImagePainter(model = File(item.filePath)),
                contentDescription = item.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Status Overlay (Synced checkmark / uploading circle)
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
                        .size(16.dp)
                )
            }
            BackupStatus.UPLOADING -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(14.dp)
                )
            }
            else -> {
                // PENDING / FAILED show nothing
            }
        }

        // Selection overlay (Google Photos-style blue selection checkbox & tint)
        if (isSelectionMode) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                )
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color.White, CircleShape)
                        .size(22.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(22.dp)
                        .background(Color.Black.copy(alpha = 0.2f), CircleShape)
                        .border(1.5.dp, Color.White, CircleShape)
                )
            }
        }
    }
}

// Extension helper to determine item index from screen touch offsets
private fun LazyGridState.getItemIndexAt(offset: androidx.compose.ui.geometry.Offset): Int? {
    val itemsInfo = layoutInfo.visibleItemsInfo
    val matched = itemsInfo.find { item ->
        val x = offset.x.toInt()
        val y = offset.y.toInt()
        x >= item.offset.x && x <= item.offset.x + item.size.width &&
        y >= item.offset.y && y <= item.offset.y + item.size.height
    }
    return matched?.index
}
