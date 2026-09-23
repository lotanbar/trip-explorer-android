package com.lotanbar.tripexplorer.ui.poi

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lotanbar.tripexplorer.data.Names
import com.lotanbar.tripexplorer.data.PoiStore
import com.lotanbar.tripexplorer.data.Trips
import java.io.File
import java.net.URLEncoder
import java.util.Locale

/** Edit an existing POI: name, description, group; add photos or audio notes; Show in map. */
@Composable
fun PoiScreen(dir: File, onBack: () -> Unit, onRenamed: (File) -> Unit, onOpenMedia: (paths: List<String>, index: Int) -> Unit) {
    val context = LocalContext.current
    val currentDir = dir
    var refresh by rememberSaveable { mutableIntStateOf(0) }
    val poi = remember(currentDir, refresh) { PoiStore.read(currentDir) }
    if (poi == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("POI not found") }
        return
    }
    var name by rememberSaveable(currentDir) { mutableStateOf(poi.name) }
    var description by rememberSaveable(currentDir) { mutableStateOf(poi.description) }
    var groupKey by rememberSaveable(currentDir) { mutableStateOf(poi.groupKey) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingPhoto = pendingPhotoPath?.let(::File)
    val dirty = name != poi.name || description != poi.description || groupKey != poi.groupKey

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingPhoto
        pendingPhotoPath = null
        if (ok && f != null && f.length() > 0) {
            val saved = PoiStore.addMedia(currentDir, f, PoiStore.MediaKind.PHOTO)
            Trips.scan(context, saved)
            refresh++
        } else f?.delete()
    }

    fun showInMap() {
        val label = URLEncoder.encode(poi.name, "UTF-8").replace("+", "%20")
        val uri = String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", poi.lat, poi.lon, poi.lat, poi.lon, label)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
        else message = "No map app is installed. Install Organic Maps or OsmAnd to show POIs on a map."
    }

    fun save() {
        val taken = Trips.poiDirs(poi.trip).map { it.name }.filter { it != poi.name }
        val err = Names.check(name, taken, isPoi = true)
        if (err != null) { nameError = err; return }
        runCatching { PoiStore.update(context, poi, name, description, groupKey) }
            .onSuccess { if (it != currentDir) onRenamed(it) else refresh++ }
            .onFailure { message = "Could not save: ${it.message}" }
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        Text(poi.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            String.format(Locale.US, "%s · %s · %.5f, %.5f", poi.trip, poi.datetime, poi.lat, poi.lon),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MediaStrip(files = poi.media, onOpen = { index -> onOpenMedia(poi.media.map { it.absolutePath }, index) }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        val f = Capture.newPhotoFile(context)
                        pendingPhotoPath = f.absolutePath
                        runCatching { camera.launch(Capture.uriFor(context, f)) }
                            .onFailure { pendingPhotoPath = null; message = "No camera app is available." }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add photo")
                }
                AudioNoteButton(
                    newFile = { PoiStore.nextMediaFile(currentDir, PoiStore.MediaKind.NOTE) },
                    onRecorded = { Trips.scan(context, it); refresh++ },
                    onError = { message = it },
                    modifier = Modifier.weight(1f),
                )
            }
            PoiFields(
                name = name, onName = { name = it; nameError = null }, nameError = nameError,
                description = description, onDescription = { description = it },
                groupKey = groupKey, onGroup = { groupKey = it },
            )
            OutlinedButton(onClick = ::showInMap, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Map, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Show in map")
            }
        }
        Button(
            onClick = { if (dirty) save() else onBack() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (dirty) "Save" else "Back", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
    }
}
