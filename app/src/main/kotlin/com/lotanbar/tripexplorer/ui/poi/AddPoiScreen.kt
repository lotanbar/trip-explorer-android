package com.lotanbar.tripexplorer.ui.poi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lotanbar.tripexplorer.data.Names
import com.lotanbar.tripexplorer.data.PoiStore
import com.lotanbar.tripexplorer.data.Trips
import java.io.File
import java.util.Locale

/**
 * Add POI: the position is already taken. Opens the in-app camera at once (take any number of
 * photos, then Done), then the form: media pager, name, description, group, photos and audio notes.
 */
@Composable
fun AddPoiScreen(trip: String, lat: Double, lon: Double, atMs: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    // Paths, saved with the instance state, so nothing is lost if the app is killed in the background.
    var photoPaths by rememberSaveable { mutableStateOf(listOf<String>()) }
    var notePaths by rememberSaveable { mutableStateOf(listOf<String>()) }
    var showCamera by rememberSaveable { mutableStateOf(true) }
    val photos = photoPaths.map(::File)
    val notes = notePaths.map(::File)
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var groupKey by rememberSaveable { mutableStateOf<String?>(null) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    if (showCamera) {
        CameraCapture(
            newFile = { Capture.newPhotoFile(context) },
            onDone = { files -> photoPaths = photoPaths + files.map { it.absolutePath }; showCamera = false },
        )
        return
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
        val mediaHeight = (maxHeight * 0.42f).coerceIn(220.dp, 340.dp)
        Column(Modifier.fillMaxSize()) {
            PoiMediaPager(files = photos + notes, onOpen = { }, modifier = Modifier.height(mediaHeight))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    String.format(Locale.US, "%s · %.5f, %.5f", trip, lat, lon),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PoiFields(
                    name = name, onName = { name = it; nameError = null }, nameError = nameError,
                    description = description, onDescription = { description = it },
                    groupKey = groupKey, onGroup = { groupKey = it },
                )
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { showCamera = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add photo")
                    }
                    AudioNoteButton(
                        newFile = { Capture.newNoteFile(context) },
                        onRecorded = { notePaths = notePaths + it.absolutePath },
                        onError = { message = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(
                    onClick = {
                        val taken = Trips.poiDirs(trip).map { it.name }
                        val err = Names.check(name, taken, isPoi = true)
                        if (err != null) { nameError = err; return@Button }
                        runCatching {
                            PoiStore.create(context, trip, name, lat, lon, atMs, description, groupKey, photos, notes)
                        }.onSuccess { onDone() }
                            .onFailure { message = "Could not save the POI: ${it.message}" }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Save POI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
