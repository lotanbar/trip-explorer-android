package com.lotanbar.tripexplorer.ui.poi

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

/** Edit an existing POI: media pager on top, name / description / group, Show in map; photos and notes are added at the bottom. */
@Composable
fun PoiScreen(dir: File, onRenamed: (File) -> Unit, onOpenMedia: (paths: List<String>, index: Int) -> Unit) {
    val context = LocalContext.current
    var refresh by rememberSaveable { mutableIntStateOf(0) }
    val poi = remember(dir, refresh) { PoiStore.read(dir) }
    if (poi == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("POI not found") }
        return
    }
    var name by rememberSaveable(dir) { mutableStateOf(poi.name) }
    var description by rememberSaveable(dir) { mutableStateOf(poi.description) }
    var groupKey by rememberSaveable(dir) { mutableStateOf(poi.groupKey) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var showCamera by rememberSaveable { mutableStateOf(false) }
    val dirty = name != poi.name || description != poi.description || groupKey != poi.groupKey

    if (showCamera) {
        // Photos go straight into media/ under the next free number.
        CameraCapture(
            newFile = { PoiStore.nextMediaFile(dir, PoiStore.MediaKind.PHOTO) },
            onDone = { showCamera = false; refresh++ },
        )
        return
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
            .onSuccess { if (it != dir) onRenamed(it) else refresh++ }
            .onFailure { message = "Could not save: ${it.message}" }
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
            PoiMediaPager(
                files = poi.media,
                onOpen = { index -> onOpenMedia(poi.media.map { it.absolutePath }, index) },
                modifier = Modifier.height(mediaHeight),
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    String.format(Locale.US, "%s · %s · %.5f, %.5f", poi.trip, poi.datetime, poi.lat, poi.lon),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { showCamera = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add photo")
                    }
                    AudioNoteButton(
                        newFile = { PoiStore.nextMediaFile(dir, PoiStore.MediaKind.NOTE) },
                        onRecorded = { Trips.scan(context, it); refresh++ },
                        onError = { message = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (dirty) {
                    Button(onClick = ::save, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("Save", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
