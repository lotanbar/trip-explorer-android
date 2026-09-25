package com.lotanbar.tripexplorer.ui.recordings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lotanbar.tripexplorer.data.GpxWriter
import com.lotanbar.tripexplorer.data.Trips
import com.lotanbar.tripexplorer.service.RecordingService
import com.lotanbar.tripexplorer.service.RecordingState
import com.lotanbar.tripexplorer.ui.theme.resolvedTextDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Every recording of every trip, newest first under each trip, opened by a long press on the record
 * button. Incomplete ones (Stop never pressed) can be resumed or finished here, also after "Later".
 * The Back gesture returns to the main screen.
 */
@Composable
fun RecordingsScreen(onResumed: () -> Unit) {
    val context = LocalContext.current
    val recordingState by RecordingService.state.collectAsStateWithLifecycle()
    val active = (recordingState as? RecordingState.Active)?.file
    var refresh by remember { mutableIntStateOf(0) }
    val byTrip = remember(refresh) { Trips.list().map { it to Trips.recordings(it) }.filter { it.second.isNotEmpty() } }
    val distances = remember { mutableStateMapOf<String, Double>() }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(byTrip) {
        for ((_, files) in byTrip) for (f in files) {
            if (f.absolutePath !in distances) distances[f.absolutePath] = withContext(Dispatchers.IO) { GpxWriter.distanceMeters(f) }
        }
    }

    message?.let { m ->
        AlertDialog(onDismissRequest = { message = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } })
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Recordings", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        if (byTrip.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No recordings yet.", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            for ((trip, files) in byTrip) {
                item(key = "trip:$trip") {
                    Text(
                        trip,
                        style = MaterialTheme.typography.titleSmall.copy(textDirection = trip.resolvedTextDirection()),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(files, key = { it.absolutePath }) { file ->
                    val incomplete = GpxWriter.isIncomplete(file)
                    val recordingNow = file == active
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(dateRange(file.name), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            listOfNotNull(
                                distances[file.absolutePath]?.let { RecordingService.formatDistance(it) },
                                when {
                                    recordingNow -> "recording now"
                                    incomplete -> "incomplete"
                                    else -> null
                                },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (incomplete && !recordingNow) Color(0xFFFFB74D) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (incomplete && !recordingNow) {
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        if (recordingState !is RecordingState.Idle) {
                                            message = "Stop the current recording first."
                                        } else {
                                            RecordingService.resumeIncomplete(context, file)
                                            onResumed()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Resume") }
                                OutlinedButton(
                                    onClick = {
                                        if (Trips.finishIncomplete(context, file) == null) message = "Could not rename ${file.name}"
                                        refresh++
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Finish") }
                            }
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                }
            }
        }
    }
}

/**
 * A recording's name as a date range, like the PC app's tree: "2026-09-14 08:10 – 17:45",
 * "2026-09-14 22:10 – 2026-09-15 01:30", or "2026-09-15 09:02 – …" while unfinished.
 */
private fun dateRange(name: String): String {
    val m = Regex("""^(\d{4}-\d{2}-\d{2}) (\d{2})-(\d{2})-\d{2} - (?:(\d{4}-\d{2}-\d{2}) )?(?:(\d{2})-(\d{2})-\d{2}|recording)\.gpx$""", RegexOption.IGNORE_CASE)
        .find(name) ?: return name
    val (date, h, min, endDate, eh, emin) = m.destructured
    val end = when {
        eh.isEmpty() -> "…"
        endDate.isNotEmpty() && endDate != date -> "$endDate $eh:$emin"
        else -> "$eh:$emin"
    }
    return "$date $h:$min – $end"
}
