package com.lotanbar.tripexplorer.ui.recordings

import android.media.MediaMetadataRetriever
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lotanbar.tripexplorer.data.Trips
import com.lotanbar.tripexplorer.service.RecordingService
import com.lotanbar.tripexplorer.service.VoiceRecordingService
import com.lotanbar.tripexplorer.service.VoiceState
import java.io.File

/**
 * General recordings of a trip (trips/<trip>/general_recordings/), opened by a long press on the mic
 * button. Tap one to play it (swipe for the others); the one being recorded can't be opened yet.
 */
@Composable
fun VoiceRecordingsScreen(trip: String, onOpenMedia: (paths: List<String>, index: Int) -> Unit) {
    val voiceState by VoiceRecordingService.state.collectAsStateWithLifecycle()
    val recording = (voiceState as? VoiceState.Active)?.file
    val files = remember(trip, recording) { Trips.generalRecordings(trip) }
    val playable = files.filter { it != recording }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
        Text("Audio recordings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
        Text(trip, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No audio recordings yet. Tap the mic to record.", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(files, key = { it.absolutePath }) { file ->
                val isRecording = file == recording
                val duration = remember(file, isRecording) { if (isRecording) null else audioDurationMs(file) }
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = !isRecording) { onOpenMedia(playable.map { it.absolutePath }, playable.indexOf(file)) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        tint = if (isRecording) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(file.nameWithoutExtension, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            when {
                                isRecording -> "recording…"
                                duration != null -> RecordingService.formatElapsed(duration)
                                else -> "${file.length() / 1024} KB"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            }
        }
    }
}

private fun audioDurationMs(file: File): Long? = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(file.absolutePath)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } finally {
        r.release()
    }
}.getOrNull()
