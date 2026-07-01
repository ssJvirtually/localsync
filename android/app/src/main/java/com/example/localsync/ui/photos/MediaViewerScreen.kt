package com.example.localsync.ui.photos

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.rememberAsyncImagePainter
import java.io.File

@Composable
fun MediaViewerScreen(
    filePath: String,
    mediaType: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val file = File(filePath)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Custom Top Bar to avoid ExperimentalMaterial3Api warnings
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .background(Color.Black),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (!file.exists()) {
                Text(
                    text = "File not found: ${file.name}",
                    color = Color.Red,
                    style = MaterialTheme.typography.bodyLarge
                )
            } else if (mediaType == "PHOTO") {
                Image(
                    painter = rememberAsyncImagePainter(model = file),
                    contentDescription = "Photo Preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AndroidView(
                    factory = { context ->
                        VideoView(context).apply {
                            val mediaController = MediaController(context)
                            mediaController.setAnchorView(this)
                            setMediaController(mediaController)
                            setVideoPath(file.absolutePath)
                            setOnPreparedListener { mp ->
                                mp.isLooping = false
                                start()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
