package com.lotanbar.tripexplorer.ui.poi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lotanbar.tripexplorer.data.PoiStore
import java.io.File

/** Thumbnails of a POI's photos and audio notes in a row; tap opens the preview. */
@Composable
fun MediaStrip(files: List<File>, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (files.isEmpty()) {
        Box(
            modifier.height(96.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text("No photos or notes", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
        return
    }
    LazyRow(modifier.height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(files, key = { _, f -> f.absolutePath }) { index, file ->
            Box(
                Modifier.size(96.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onOpen(index) },
                contentAlignment = Alignment.Center,
            ) {
                if (PoiStore.isAudio(file)) {
                    Icon(Icons.Default.Mic, contentDescription = file.name, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                    Text(file.nameWithoutExtension, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomCenter))
                } else {
                    AsyncImage(model = file, contentDescription = file.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
