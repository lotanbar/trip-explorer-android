package com.lotanbar.tripexplorer.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lotanbar.tripexplorer.data.GpxWriter
import com.lotanbar.tripexplorer.data.Names
import com.lotanbar.tripexplorer.data.Trips
import com.lotanbar.tripexplorer.service.RecordingService
import com.lotanbar.tripexplorer.service.RecordingState
import com.lotanbar.tripexplorer.ui.theme.resolvedTextAlign
import com.lotanbar.tripexplorer.ui.theme.resolvedTextDirection
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onAddPoi: (trip: String, lat: Double, lon: Double, atMs: Long) -> Unit,
    onOpenPoiList: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var refresh by remember { mutableIntStateOf(0) }
    val trips = remember(refresh) { Trips.list() }
    var currentTrip by remember(refresh) { mutableStateOf(Trips.currentTrip(context)) }
    val recordingState by RecordingService.state.collectAsStateWithLifecycle()
    val recordings = remember(refresh, currentTrip, recordingState) { currentTrip?.let { Trips.recordings(it) } ?: emptyList() }

    // Re-read the folders whenever the app comes back (files may have changed over USB).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showNewTrip by remember { mutableStateOf(trips.isEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var fixJob by remember { mutableStateOf<Job?>(null) }
    var showBatteryDialog by remember { mutableStateOf(false) }

    // --- Incomplete recordings: ask once per file on launch ---
    var incompleteQueue by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(Unit) {
        val active = (RecordingService.state.value as? RecordingState.Active)?.file
        val dismissed = Trips.dismissedIncomplete(context)
        incompleteQueue = Trips.list().flatMap { Trips.recordings(it) }
            .filter { GpxWriter.isIncomplete(it) && it != active && it.absolutePath !in dismissed }
    }

    fun finishIncomplete(file: File) {
        val endMs = GpxWriter.lastPointTimeMs(file) ?: GpxWriter.startMsFromName(file.name) ?: System.currentTimeMillis()
        val target = File(file.parentFile, GpxWriter.finishedFileName(file.name, endMs))
        if (file.renameTo(target)) Trips.scan(context, target, file) else message = "Could not rename ${file.name}"
        refresh++
    }

    // --- Permissions and start ---
    fun startRecording() {
        val trip = currentTrip ?: return
        RecordingService.start(context, trip)
    }
    fun checkBatteryAndStart() {
        val pm = context.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) showBatteryDialog = true else startRecording()
    }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { startRecording() }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checkBatteryAndStart() }
    fun checkNotifAndStart() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else checkBatteryAndStart()
    }
    val recordPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) checkNotifAndStart()
        else message = "Precise location permission is required to record."
    }
    fun onStartPressed() {
        when {
            currentTrip == null -> showNewTrip = true
            !hasLocationPermission(context) -> recordPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            !isGpsEnabled(context) -> message = "Location is off. Turn it on in the phone's settings."
            else -> checkNotifAndStart()
        }
    }

    fun fetchFixAndAddPoi() {
        val trip = currentTrip ?: return
        fixJob = scope.launch {
            val loc = currentGpsLocation(context)
            fixJob = null
            if (loc != null) onAddPoi(trip, loc.latitude, loc.longitude, System.currentTimeMillis())
            else message = "Could not get a GPS fix. Try again outdoors."
        }
    }
    val poiPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) fetchFixAndAddPoi()
        else message = "Precise location permission is required to add a POI."
    }
    fun onAddPoiPressed() {
        when {
            currentTrip == null -> showNewTrip = true
            !hasLocationPermission(context) -> poiPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            !isGpsEnabled(context) -> message = "Location is off. Turn it on in the phone's settings."
            else -> fetchFixAndAddPoi()
        }
    }

    // --- Dialogs ---
    if (showNewTrip) {
        NewTripDialog(
            taken = trips,
            canDismiss = trips.isNotEmpty(),
            onCreate = { name ->
                Trips.create(context, name)
                Trips.setCurrentTrip(context, name)
                showNewTrip = false
                refresh++
            },
            onDismiss = { showNewTrip = false },
        )
    }
    incompleteQueue.firstOrNull()?.let { file ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Incomplete recording") },
            text = { Text("${file.parentFile?.parentFile?.name} / ${file.name}\n\nStop was never pressed for this recording. Resume it, or finish it with its last point's time.") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        incompleteQueue = incompleteQueue.drop(1)
                        if (recordingState is RecordingState.Idle) RecordingService.resumeIncomplete(context, file)
                        else message = "Stop the current recording first."
                    }) { Text("Resume") }
                    TextButton(onClick = { incompleteQueue = incompleteQueue.drop(1); finishIncomplete(file) }) { Text("Finish") }
                }
            },
            dismissButton = {
                TextButton(onClick = { Trips.dismissIncomplete(context, file); incompleteQueue = incompleteQueue.drop(1) }) { Text("Later") }
            },
        )
    }
    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            title = { Text("Battery optimization") },
            text = { Text("For reliable background recording, disable battery optimization for this app. Without this, the phone may stop the recording when the screen is off.") },
            confirmButton = {
                TextButton(onClick = {
                    showBatteryDialog = false
                    batteryLauncher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:${context.packageName}") })
                }) { Text("Open Settings") }
            },
            dismissButton = { TextButton(onClick = { showBatteryDialog = false; startRecording() }) { Text("Continue anyway") } },
        )
    }
    fixJob?.let { job ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Getting GPS fix…") },
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(16.dp)); Text("Waiting for the GPS") } },
            confirmButton = { TextButton(onClick = { job.cancel(); fixJob = null }) { Text("Cancel") } },
        )
    }
    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }

    // --- Layout ---
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.fillMaxWidth()) {
            val shown = currentTrip ?: "No trip"
            OutlinedTextField(
                value = shown,
                onValueChange = {},
                readOnly = true,
                label = { Text("Trip") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = shown.resolvedTextDirection(), textAlign = shown.resolvedTextAlign()),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                trips.forEach { trip ->
                    DropdownMenuItem(
                        text = { Text(trip, style = MaterialTheme.typography.bodyLarge.copy(textDirection = trip.resolvedTextDirection(), textAlign = trip.resolvedTextAlign()), modifier = Modifier.fillMaxWidth()) },
                        onClick = { Trips.setCurrentTrip(context, trip); currentTrip = trip; expanded = false },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("+ New trip") }, onClick = { expanded = false; showNewTrip = true })
            }
        }

        Spacer(Modifier.height(16.dp))
        RecordingCard(
            state = recordingState,
            onStart = ::onStartPressed,
            onPause = { RecordingService.pause(context) },
            onResume = { RecordingService.resume(context) },
            onStop = { RecordingService.stop(context) },
        )

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = ::onAddPoiPressed, modifier = Modifier.weight(1f).height(56.dp)) {
                Icon(Icons.Default.AddLocation, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add POI", style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(onClick = onOpenPoiList, modifier = Modifier.weight(1f).height(56.dp)) {
                Icon(Icons.Default.List, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("POIs", style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Recordings", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        if (recordings.isEmpty()) {
            Text("No recordings in this trip yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
        val activeFile = (recordingState as? RecordingState.Active)?.file
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(recordings, key = { it.absolutePath }) { file ->
                val incomplete = GpxWriter.isIncomplete(file)
                val isActive = file == activeFile
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(file.name.removeSuffix(".gpx"), style = MaterialTheme.typography.bodyLarge)
                    if (incomplete) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (isActive) "recording now" else "incomplete",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isActive) MaterialTheme.colorScheme.primary else Color(0xFFE0A050),
                            )
                            if (!isActive) {
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    enabled = recordingState is RecordingState.Idle,
                                    onClick = { RecordingService.resumeIncomplete(context, file) },
                                ) { Text("Resume") }
                                TextButton(onClick = { finishIncomplete(file) }) { Text("Finish") }
                            }
                        }
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            }
        }
    }
}

@Composable
private fun RecordingCard(
    state: RecordingState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        when (state) {
            RecordingState.Idle -> Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
            ) {
                Icon(Icons.Default.FiberManualRecord, contentDescription = null, tint = Color(0xFFE53935))
                Spacer(Modifier.width(8.dp))
                Text("Start recording", style = MaterialTheme.typography.titleMedium)
            }
            is RecordingState.Active -> {
                var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) { while (true) { delay(1000L); nowMs = System.currentTimeMillis() } }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            RecordingService.formatElapsed(nowMs - state.startedAtMs),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Text(
                            if (state.paused) "paused · ${state.pointCount} points" else "${state.pointCount} points",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.paused) {
                        IconButton(onClick = onResume, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    } else {
                        IconButton(onClick = onPause, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }
                    IconButton(onClick = onStop, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.Red, modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NewTripDialog(
    taken: List<String>,
    canDismiss: Boolean,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        title = { Text(if (taken.isEmpty()) "Name your first trip" else "New trip") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text("Trip name") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = name.resolvedTextDirection(), textAlign = name.resolvedTextAlign()),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val err = Names.check(name, taken)
                if (err != null) error = err else onCreate(name)
            }) { Text("Create") }
        },
        dismissButton = if (canDismiss) ({ TextButton(onClick = onDismiss) { Text("Cancel") } }) else null,
    )
}
