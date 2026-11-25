package com.lotanbar.tripexplorer.ui.main

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lotanbar.tripexplorer.ShortcutRequest
import com.lotanbar.tripexplorer.data.Names
import com.lotanbar.tripexplorer.data.PoiEntry
import com.lotanbar.tripexplorer.data.PoiStore
import com.lotanbar.tripexplorer.data.Trips
import com.lotanbar.tripexplorer.service.RecordingService
import com.lotanbar.tripexplorer.service.RecordingState
import com.lotanbar.tripexplorer.sync.Sync
import com.lotanbar.tripexplorer.sync.progressLine
import com.lotanbar.tripexplorer.sync.shortStatus
import com.lotanbar.tripexplorer.ui.poi.AddPoiPanel
import com.lotanbar.tripexplorer.ui.poi.AddPoiSession
import com.lotanbar.tripexplorer.ui.poi.PoiList
import com.lotanbar.tripexplorer.ui.recordings.recordingLabel
import com.lotanbar.tripexplorer.ui.recordings.rememberResumeFlow
import com.lotanbar.tripexplorer.ui.theme.resolvedTextAlign
import com.lotanbar.tripexplorer.ui.theme.resolvedTextDirection
import kotlinx.coroutines.delay
import java.io.File

/** The broken-recording question is asked at every app start (once per start, not each time this screen comes back). */
private var launchQuestionAsked = false

/**
 * The main screen: the trip and Drive sync on top, the route recording under them, and a large Add POI
 * button, which turns into the Add POI steps in place while a POI is being added. Swiping that area
 * sideways shows the current trip's POI list (see the spec's Main screen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onOpenDrive: () -> Unit, onOpenPoi: (File) -> Unit) {
    val context = LocalContext.current
    // Over the lock screen (the side button's Add POI), the Drive screen and Sync are not reachable.
    // Checked at the tap, since the phone may have been unlocked meanwhile.
    fun isLocked() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    fun unlockFirst() = Toast.makeText(context, "Unlock the phone first", Toast.LENGTH_SHORT).show()
    val openDrive: () -> Unit = { if (isLocked()) unlockFirst() else onOpenDrive() }
    val lifecycleOwner = LocalLifecycleOwner.current

    var refresh by remember { mutableIntStateOf(0) }
    val trips = remember(refresh) { Trips.list() }
    var currentTrip by remember(refresh) { mutableStateOf(Trips.currentTrip(context)) }
    val recordingState by RecordingService.state.collectAsStateWithLifecycle()
    val addingPoi by AddPoiSession.state.collectAsStateWithLifecycle()

    // Re-read the folders whenever the app comes back (files may have changed over USB).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { refresh++ }
    // Drive sync (the Sync button): re-read the folders when a pass changed files here.
    LaunchedEffect(Unit) { Sync.engine(context) }
    val syncStatus by Sync.status.collectAsStateWithLifecycle()
    val syncChanges by Sync.changes.collectAsStateWithLifecycle()
    LaunchedEffect(syncChanges) { if (syncChanges > 0) refresh++ }

    // Only when asked for (Trip menu → + New trip); what needs a trip says so.
    var showNewTrip by remember { mutableStateOf(false) }
    fun needTrip() = Toast.makeText(context, "Create a trip first: tap the trip name → + New trip", Toast.LENGTH_SHORT).show()
    var message by remember { mutableStateOf<String?>(null) }
    var showBatteryDialog by remember { mutableStateOf(false) }

    // While a recording runs, the screen shows its trip (a continued broken recording may belong to another one).
    val activeRecording = recordingState as? RecordingState.Active
    LaunchedEffect(activeRecording?.file) {
        val trip = activeRecording?.trip ?: return@LaunchedEffect
        if (trip != currentTrip && trip in Trips.list()) { Trips.setCurrentTrip(context, trip); currentTrip = trip }
    }
    // The POI list (the page beside Add POI); read again with the folders, and after each Add POI ends.
    val pois: List<PoiEntry> = remember(refresh, currentTrip) { currentTrip?.let(PoiStore::list) ?: emptyList() }
    LaunchedEffect(addingPoi == null) { if (addingPoi == null) refresh++ }
    // Opened over the lock screen (the side button's Add POI): nothing but Add POI is reachable.
    val locked = remember(refresh) { isLocked() }
    // Battery optimization still on: a banner until it is turned off (checked again whenever the app comes back).
    val batteryOptimized = remember(refresh) {
        !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val batteryBannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    // Continue (and its checks: another trip, far from where it stopped).
    val resumeFlow = rememberResumeFlow(onStarted = { refresh++ })

    // --- Starting a new recording: permissions, then the battery question ---
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
    // Physical activity (steps) lets a recording switch GPS off while not moving; refusing it just keeps GPS on.
    val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checkNotifAndStart() }
    fun checkActivityAndStart() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else checkNotifAndStart()
    }
    val recordPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) checkActivityAndStart()
        else message = "Precise location permission is required to record."
    }
    fun checkPermissionsAndStart() {
        when {
            currentTrip == null -> needTrip()
            !hasLocationPermission(context) -> recordPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            !isGpsEnabled(context) -> message = "Location is off. Turn it on in the phone's settings."
            else -> checkActivityAndStart()
        }
    }

    // --- Broken recordings: one question per file, at app start and every time Start recording is pressed ---
    var brokenQueue by remember { mutableStateOf<List<File>>(emptyList()) }
    var startAfterQuestions by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (launchQuestionAsked) return@LaunchedEffect
        launchQuestionAsked = true
        brokenQueue = Trips.brokenRecordings((RecordingService.state.value as? RecordingState.Active)?.file)
    }
    fun answered(file: File) {
        brokenQueue = brokenQueue - file
        if (brokenQueue.isEmpty() && startAfterQuestions) {
            startAfterQuestions = false
            checkPermissionsAndStart()
        }
    }
    fun onStartPressed() {
        if (currentTrip == null) { needTrip(); return }
        val broken = Trips.brokenRecordings(activeRecording?.file)
        if (broken.isEmpty()) checkPermissionsAndStart() else { startAfterQuestions = true; brokenQueue = broken }
    }

    // --- Add POI: needs a trip, precise location and the microphone; then the note starts recording at once ---
    fun beginPoi() {
        val trip = currentTrip ?: return
        if (!isGpsEnabled(context)) { message = "Location is off. Turn it on in the phone's settings."; return }
        AddPoiSession.begin(context, trip)?.let { message = it }
    }
    val poiPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        when {
            !hasLocationPermission(context) -> message = "Precise location permission is required to add a POI."
            !hasMicPermission(context) -> message = "Microphone permission is required to record the note."
            else -> beginPoi()
        }
    }
    fun onAddPoiPressed() {
        when {
            AddPoiSession.state.value != null -> {}
            currentTrip == null -> needTrip()
            !hasLocationPermission(context) || !hasMicPermission(context) -> poiPermLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.RECORD_AUDIO),
            )
            else -> beginPoi()
        }
    }

    // Opened through the "Add POI" entry (the side button's double press): start Add POI at once.
    val shortcut by ShortcutRequest.requested.collectAsStateWithLifecycle()
    LaunchedEffect(shortcut) {
        if (!shortcut) return@LaunchedEffect
        ShortcutRequest.requested.value = false
        onAddPoiPressed()
    }

    // --- Dialogs ---
    if (showNewTrip) {
        NewTripDialog(
            taken = trips,
            onCreate = { name ->
                Trips.create(context, name)
                Trips.setCurrentTrip(context, name)
                showNewTrip = false
                refresh++
            },
            onDismiss = { showNewTrip = false },
        )
    }
    // The file being recorded is never asked about (a continue may still be starting when the list is made).
    brokenQueue.firstOrNull { it != activeRecording?.file }?.let { file ->
        val trip = file.parentFile?.parentFile?.name ?: ""
        AlertDialog(
            onDismissRequest = { answered(file) }, // the Back gesture is Dismiss
            title = { Text("Broken recording") },
            text = { Text("$trip · ${recordingLabel(file.name)}\n\nStop was never pressed for this recording.") },
            // Three answers, one under the other.
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        // Continuing it: that is the recording that runs. The rest are asked about next time.
                        brokenQueue = emptyList()
                        startAfterQuestions = false
                        resumeFlow.start(file)
                    }) { Text("Continue") }
                    TextButton(onClick = {
                        if (Trips.finishIncomplete(context, file) == null) message = "Could not rename ${file.name}"
                        refresh++
                        answered(file)
                    }) { Text("Don't show again") }
                    TextButton(onClick = { answered(file) }) { Text("Dismiss") }
                }
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
    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }

    // --- Layout: trip and sync, the route recording, then Add POI ---
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        // One row: the trip (tap to switch or add one) · Drive sync (tap for its screen) and the Sync button.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(1f)) {
                val shown = currentTrip ?: "No trip"
                Row(
                    Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        shown,
                        style = MaterialTheme.typography.headlineSmall.copy(textDirection = shown.resolvedTextDirection()),
                        fontWeight = FontWeight.SemiBold,
                        color = if (currentTrip == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded)
                }
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
            Spacer(Modifier.width(8.dp))
            SyncStatus(
                ready = syncStatus.signedIn && syncStatus.folder != null,
                line = if (!Sync.configured) "No client" else shortStatus(syncStatus),
                error = syncStatus.error != null && !syncStatus.busy,
                onOpen = openDrive,
            )
            // The Sync button: one pass, both ways.
            if (syncStatus.busy) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp) }
            } else {
                IconButton(
                    modifier = Modifier.size(56.dp),
                    onClick = {
                        when {
                            isLocked() -> unlockFirst()
                            !Sync.configured -> message = "This build has no Google client, so Drive sync is off."
                            !syncStatus.signedIn || syncStatus.folder == null -> openDrive()
                            else -> Sync.syncNow(context)
                        }
                    },
                ) { Icon(Icons.Default.Sync, contentDescription = "Sync with Google Drive", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) }
            }
        }
        // While syncing: a bar, and the item on its way · MB done · time left.
        if (syncStatus.busy) {
            val progress = when {
                syncStatus.bytesTotal > 0 -> syncStatus.bytesDone.toFloat() / syncStatus.bytesTotal
                syncStatus.total > 0 -> syncStatus.done.toFloat() / syncStatus.total
                else -> null
            }
            if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
            Text(
                progressLine(syncStatus),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }

        if (batteryOptimized && !locked) {
            Spacer(Modifier.height(8.dp))
            Surface(
                onClick = {
                    batteryBannerLauncher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:${context.packageName}") })
                },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF4E3B12),
                contentColor = Color(0xFFFFD54F),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BatteryAlert, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Battery optimization is on: a recording may stop while the screen is off. Tap to turn it off.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Under the top bar, across the whole width: the route recording.
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(84.dp)) {
            RecordingCard(state = recordingState, onStart = ::onStartPressed, onStop = { RecordingService.stop(context) })
        }
        // A line between the route recording and Add POI (which records a note), so the two aren't confused.
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))
        // The rest: a round Add POI button, which becomes the Add POI steps while a POI is being added.
        // Swiping sideways shows the POI list (not while adding a POI, nor over the lock screen).
        val pager = rememberPagerState { 2 }
        val canSwipe = addingPoi == null && !locked
        LaunchedEffect(canSwipe) { if (!canSwipe && pager.currentPage != 0) pager.scrollToPage(0) }
        HorizontalPager(pager, userScrollEnabled = canSwipe, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
        if (page == 1) {
            PoiList(currentTrip, pois, onOpen = { onOpenPoi(it.dir) }, modifier = Modifier.fillMaxSize())
        } else
        Crossfade(targetState = addingPoi != null, label = "add-poi", modifier = Modifier.fillMaxSize()) { adding ->
            if (adding) {
                AddPoiPanel(Modifier.fillMaxSize())
            } else {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        onClick = ::onAddPoiPressed,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shadowElevation = 6.dp,
                        modifier = Modifier.size(176.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.AddLocation, contentDescription = "Add POI", modifier = Modifier.size(72.dp))
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("Add POI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Records a spoken note here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Keeps the button centred in the space, as if the bar above were not there.
                    Spacer(Modifier.height(88.dp))
                }
            }
        }
        }
        // Two dots: which page is shown (Add POI · POI list).
        if (canSwipe) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
                repeat(2) { i ->
                    Box(
                        Modifier.padding(horizontal = 4.dp).size(8.dp).clip(CircleShape).background(
                            if (pager.currentPage == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    )
                }
            }
        }
    }
}

fun hasMicPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Drive sync at a glance: a cloud and a few words (tap for the Drive screen, where errors are spelled out). */
@Composable
private fun SyncStatus(ready: Boolean, line: String, error: Boolean, onOpen: () -> Unit) {
    Row(Modifier.clickable(onClick = onOpen).padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ready) Icons.Default.Cloud else Icons.Default.CloudOff,
            contentDescription = "Drive sync",
            tint = when {
                error -> Color(0xFFEF5350)
                ready -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(30.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            line,
            style = MaterialTheme.typography.titleMedium,
            color = if (error) Color(0xFFEF5350) else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
    }
}

/** Start recording; while recording: the time, the distance ("not moving" when auto-paused) and Stop. */
@Composable
private fun RecordingCard(state: RecordingState, onStart: () -> Unit, onStop: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    when (state) {
        RecordingState.Idle -> Surface(
            onClick = onStart,
            shape = shape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxSize().clip(shape),
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FiberManualRecord, contentDescription = null, tint = Color(0xFFE53935), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                // The same type as the trip's name above.
                Text("Start recording", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
        }
        is RecordingState.Active -> Card(
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxSize(),
        ) {
            var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { delay(1000L); nowMs = System.currentTimeMillis() } }
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        RecordingService.formatElapsed(state.elapsedMs(nowMs)),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        if (state.autoPaused) "not moving · ${RecordingService.formatDistance(state.distanceM)}"
                        else RecordingService.formatDistance(state.distanceM),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onStop, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.Red, modifier = Modifier.size(44.dp))
                }
            }
        }
    }
}

@Composable
private fun NewTripDialog(
    taken: List<String>,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
