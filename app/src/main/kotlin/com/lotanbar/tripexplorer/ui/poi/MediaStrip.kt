package com.lotanbar.tripexplorer.ui.poi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lotanbar.tripexplorer.data.PoiStore
import java.io.File

/**
 * Full-width swipeable media pager for the POI screens. The caller sets
 * the height. Tap opens the full preview.
 */
@Composable
fun PoiMediaPager(files: List<File>, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        if (files.isEmpty()) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.ImageNotSupported,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(52.dp),
                )
                Text("No photos or notes", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
            }
            return@Box
        }
        val pagerState = rememberPagerState(pageCount = { files.size })
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val file = files[page]
            Box(Modifier.fillMaxSize().clickable { onOpen(page) }, contentAlignment = Alignment.Center) {
                if (PoiStore.isAudio(file)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(64.dp))
                        val n = files.take(page + 1).count { PoiStore.isAudio(it) }
                        Text("Audio note $n", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
        }
        if (files.size > 1) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(files.size) { i ->
                    val selected = pagerState.currentPage == i
                    Box(Modifier.size(if (selected) 8.dp else 6.dp).clip(CircleShape).background(if (selected) Color.White else Color.White.copy(alpha = 0.5f)))
                }
            }
        }
    }
}
