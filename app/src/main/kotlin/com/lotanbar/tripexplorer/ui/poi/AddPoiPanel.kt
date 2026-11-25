package com.lotanbar.tripexplorer.ui.poi

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lotanbar.tripexplorer.data.Categories
import com.lotanbar.tripexplorer.data.Category
import com.lotanbar.tripexplorer.service.RecordingService
import com.lotanbar.tripexplorer.ui.poi.AddPoiSession.Phase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val FIX_WAIT_MS = 30_000L
private val Red = Color(0xFFC62828)

/**
 * Add POI (see the spec), shown in the main screen in place of the Add POI button while a POI is being
 * added: the note records from the tap; Stop, then listen / record again; Next, then the category grid
 * and Save. Cancel (or the Back gesture) asks before discarding. The screen stays on meanwhile, since
 * Android cuts an app's microphone once its screen locks.
 */
@Composable
fun AddPoiPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by AddPoiSession.state.collectAsStateWithLifecycle()
    val st = current ?: return

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var askDiscard by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var noFixFor by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by rememberSaveable(st.atMs) { mutableStateOf<String?>(null) }

    // While a POI is being added, Back is ours: it never leaves a POI half done (while saving it does nothing).
    BackHandler {
        when {
            locating -> {}
            st.phase == Phase.Category || st.phase == Phase.Camera -> AddPoiSession.back()
            else -> askDiscard = true
        }
    }

    fun save(key: String) {
        scope.launch {
            var at = AddPoiSession.locationNow()
            if (at == null) {
                locating = true
                at = AddPoiSession.awaitLocation(FIX_WAIT_MS)
                locating = false
            }
            if (at == null) { noFixFor = key; return@launch }
            runCatching { AddPoiSession.save(context, key, at) }
                .onSuccess { Toast.makeText(context, "POI saved", Toast.LENGTH_SHORT).show() }
                .onFailure { message = "Could not save the POI: ${it.message}" }
        }
    }

    // --- Dialogs ---
    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text("Discard this POI?") },
            text = { Text("The note will be deleted.") },
            confirmButton = { TextButton(onClick = { askDiscard = false; AddPoiSession.discard() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { askDiscard = false }) { Text("Keep") } },
        )
    }
    if (locating) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Getting your location…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Stay where you are until the GPS has your position. The POI is saved the moment it does.")
                }
            },
            confirmButton = { },
        )
    }
    noFixFor?.let { key ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("No GPS fix") },
            text = { Text("Could not get a GPS fix. Try again outdoors. Nothing is lost.") },
            confirmButton = { TextButton(onClick = { noFixFor = null; save(key) }) { Text("Try again") } },
            dismissButton = { TextButton(onClick = { noFixFor = null }) { Text("Back") } },
        )
    }
    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }

    // Recording and Paused are one step (no fade between them); the others fade in.
    val step = if (st.phase == Phase.Paused) Phase.Recording else st.phase
    Crossfade(targetState = step, label = "add-poi-step", modifier = modifier) { shown ->
        Column(Modifier.fillMaxSize()) {
            // Read through the collected state, so pause / resume / a new take recompose this step.
            val now = current ?: return@Column
            when (shown) {
                Phase.Recording, Phase.Paused -> RecordingPane(now, onCancel = { askDiscard = true })
                Phase.Review -> ReviewPane(
                    now,
                    onAddPhoto = AddPoiSession::openCamera,
                    onRecordAgain = { AddPoiSession.recordAgain(context)?.let { message = it } },
                    onNext = AddPoiSession::next,
                    onCancel = { askDiscard = true },
                )
                // Add photo: the in-app camera in this area; Done / Back return to the note with the photos.
                Phase.Camera -> CameraCapture(
                    newFile = { AddPoiSession.newPhotoFile(context) },
                    onDone = AddPoiSession::closeCamera,
                )
                Phase.Category -> CategoryPane(
                    selected = selected,
                    onSelect = { selected = it },
                    onBack = AddPoiSession::back,
                    onSave = { selected?.let(::save) },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.RecordingPane(st: AddPoiSession.State, onCancel: () -> Unit) {
    val levels by AddPoiSession.levels.collectAsStateWithLifecycle()
    val recording = st.phase == Phase.Recording
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recording) { while (recording) { nowMs = System.currentTimeMillis(); delay(200L) } }

    Spacer(Modifier.weight(1f))
    Soundwave(levels, active = recording, modifier = Modifier.fillMaxWidth().height(160.dp))
    Spacer(Modifier.height(24.dp))
    Text(
        RecordingService.formatElapsed(st.elapsedMs(nowMs)),
        style = MaterialTheme.typography.displayMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Text(
        if (recording) "Recording the note…" else "Paused",
        style = MaterialTheme.typography.titleMedium,
        color = if (recording) Red.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel, modifier = Modifier.width(96.dp)) { Text("Cancel") }
        FilledIconButton(
            onClick = { if (recording) AddPoiSession.pause() else AddPoiSession.resume() },
            modifier = Modifier.size(80.dp),
        ) {
            Icon(if (recording) Icons.Default.Pause else Icons.Default.Mic, contentDescription = if (recording) "Pause" else "Resume", modifier = Modifier.size(40.dp))
        }
        FilledIconButton(
            onClick = AddPoiSession::stop,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Red, contentColor = Color.White),
            modifier = Modifier.size(80.dp),
        ) {
            Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(40.dp))
        }
    }
    Spacer(Modifier.height(16.dp))
}

/** The microphone's real level: one bar per sample, newest on the right. */
@Composable
private fun Soundwave(levels: List<Float>, active: Boolean, modifier: Modifier = Modifier) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Canvas(modifier) {
        val slots = AddPoiSession.LEVELS
        val slot = size.width / slots
        val bar = slot * 0.6f
        val mid = size.height / 2
        val minH = 4.dp.toPx()
        for (i in 0 until slots) {
            // Right-aligned: the newest sample is the last slot; slots without a sample yet are flat.
            val level = levels.getOrNull(levels.size - slots + i) ?: 0f
            val h = (level * size.height * 0.85f).coerceAtLeast(minH)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * slot + (slot - bar) / 2, mid - h / 2),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2, bar / 2),
            )
        }
    }
}

@Composable
private fun ColumnScope.ReviewPane(
    st: AddPoiSession.State,
    onAddPhoto: () -> Unit,
    onRecordAgain: () -> Unit,
    onNext: () -> Unit,
    onCancel: () -> Unit,
) {
    Spacer(Modifier.weight(1f))
    val take = st.take
    if (take != null) {
        NotePlayer(take, modifier = Modifier.fillMaxWidth())
    } else {
        Text(
            "Nothing was recorded.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
    Spacer(Modifier.weight(1f))
    // Optional photos, above the bottom row.
    OutlinedButton(onClick = onAddPhoto, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Icon(Icons.Default.PhotoCamera, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (st.photos.isEmpty()) "Add photo" else "Add photo (${st.photos.size})", style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(12.dp))
    // Cancel, Record again and Next in one row.
    Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel, contentPadding = PaddingValues(4.dp), modifier = Modifier.weight(0.8f).fillMaxSize()) {
            Text("Cancel", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
        }
        OutlinedButton(onClick = onRecordAgain, contentPadding = PaddingValues(4.dp), modifier = Modifier.weight(1.3f).fillMaxSize()) {
            Text("Record again", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
        }
        Button(onClick = onNext, enabled = take != null, contentPadding = PaddingValues(4.dp), modifier = Modifier.weight(1f).fillMaxSize()) {
            Text("Next", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
    Spacer(Modifier.height(16.dp))
}

/** Play / pause, with a progress bar and the time. */
@Composable
internal fun NotePlayer(file: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
    }
    var playing by remember(file) { mutableStateOf(false) }
    var positionMs by remember(file) { mutableLongStateOf(0L) }
    var durationMs by remember(file) { mutableLongStateOf(0L) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { player.pause(); player.seekTo(0) }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            if (player.duration > 0) durationMs = player.duration
            delay(100L)
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = { if (playing) player.pause() else player.play() }, modifier = Modifier.size(96.dp)) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (playing) "Pause" else "Play", modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(
            progress = { if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${RecordingService.formatElapsed(positionMs)} / ${RecordingService.formatElapsed(durationMs)}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Six large tiles, 2 across and 3 down; a tap selects one, Save writes the POI. */
@Composable
private fun ColumnScope.CategoryPane(
    selected: String?,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Categories.all.chunked(2).forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { cat ->
                    CategoryTile(cat, selected = cat.key == selected, onClick = { onSelect(cat.key) }, modifier = Modifier.weight(1f).fillMaxSize())
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).fillMaxSize()) {
            Text("Back", style = MaterialTheme.typography.titleMedium)
        }
        Button(onClick = onSave, enabled = selected != null, modifier = Modifier.weight(1f).fillMaxSize()) {
            Text("Save", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CategoryTile(cat: Category, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) cat.color.copy(alpha = 0.30f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = if (selected) BorderStroke(3.dp, cat.color) else null,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                Surface(shape = CircleShape, color = cat.color, modifier = Modifier.fillMaxSize()) {}
                Icon(painterResource(cat.iconRes), contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(cat.name, style = MaterialTheme.typography.titleLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}
