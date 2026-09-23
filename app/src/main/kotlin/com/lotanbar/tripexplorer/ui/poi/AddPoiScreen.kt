package com.lotanbar.tripexplorer.ui.poi

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
 * Add POI: the position is already taken. Opens the phone's camera at once; after each photo
 * offers "Another photo" or "Done"; then the form (name, description, group, audio notes).
 */
@Composable
fun AddPoiScreen(trip: String, lat: Double, lon: Double, atMs: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val photos = remember { mutableStateListOf<File>() }
    val notes = remember { mutableStateListOf<File>() }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    var askAnother by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var groupKey by rememberSaveable { mutableStateOf<String?>(null) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var cameraOpened by rememberSaveable { mutableStateOf(false) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingPhoto
        pendingPhoto = null
        if (ok && f != null && f.length() > 0) {
            photos.add(f)
            askAnother = true
        } else {
            f?.delete()
        }
    }
    fun openCamera() {
        val f = Capture.newPhotoFile(context)
        pendingPhoto = f
        runCatching { camera.launch(Capture.uriFor(context, f)) }
            .onFailure { pendingPhoto = null; message = "No camera app is available." }
    }
    LaunchedEffect(Unit) {
        if (!cameraOpened) { cameraOpened = true; openCamera() }
    }

    if (askAnother) {
        AlertDialog(
            onDismissRequest = { askAnother = false },
            title = { Text("Photo ${photos.size} saved") },
            confirmButton = { TextButton(onClick = { askAnother = false; openCamera() }) { Text("Another photo") } },
            dismissButton = { TextButton(onClick = { askAnother = false }) { Text("Done") } },
        )
    }
    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        Text("New POI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            String.format(Locale.US, "%s · %.5f, %.5f", trip, lat, lon),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MediaStrip(files = photos + notes, onOpen = { }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = ::openCamera, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (photos.isEmpty()) "Take photo" else "Another photo")
                }
                AudioNoteButton(
                    newFile = { Capture.newNoteFile(context) },
                    onRecorded = { notes.add(it) },
                    onError = { message = it },
                    modifier = Modifier.weight(1f),
                )
            }
            PoiFields(
                name = name, onName = { name = it; nameError = null }, nameError = nameError,
                description = description, onDescription = { description = it },
                groupKey = groupKey, onGroup = { groupKey = it },
            )
        }
        Button(
            onClick = {
                val taken = Trips.poiDirs(trip).map { it.name }
                val err = Names.check(name, taken, isPoi = true)
                if (err != null) { nameError = err; return@Button }
                runCatching {
                    PoiStore.create(context, trip, name, lat, lon, atMs, description, groupKey, photos.toList(), notes.toList())
                }.onSuccess { onDone() }
                    .onFailure { message = "Could not save the POI: ${it.message}" }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Save POI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
    }
}
