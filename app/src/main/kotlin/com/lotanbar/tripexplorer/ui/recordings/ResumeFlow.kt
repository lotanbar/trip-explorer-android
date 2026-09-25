package com.lotanbar.tripexplorer.ui.recordings

import android.location.Location
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lotanbar.tripexplorer.data.GpxWriter
import com.lotanbar.tripexplorer.data.Trips
import com.lotanbar.tripexplorer.service.RecordingService
import com.lotanbar.tripexplorer.service.RecordingState
import com.lotanbar.tripexplorer.ui.main.currentGpsLocation
import com.lotanbar.tripexplorer.ui.main.hasLocationPermission
import com.lotanbar.tripexplorer.ui.main.isGpsEnabled
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/** Farther than this from where a recording stopped, resuming it asks first. */
private const val FAR_KM = 50

/**
 * Resuming an incomplete recording, or continuing a finished one, with its safeguards:
 * a finished one asks "Continue this recording?"; a recording of another trip than the one shown
 * asks before switching to that trip; then the GPS position is taken, and if it is more than
 * [FAR_KM] km from the recording's last point, that is asked too. Continuing renames the file back
 * to "… - recording.gpx", so it is incomplete again until Stop.
 */
class ResumeFlow internal constructor() {
    internal var step by mutableStateOf<Step?>(null)

    internal sealed class Step {
        abstract val file: File
        data class ConfirmContinue(override val file: File) : Step()
        data class OtherTrip(override val file: File, val shown: String) : Step()
        data class Locating(override val file: File, val job: Job) : Step()
        data class Far(override val file: File, val km: Int) : Step()
        data class NoFix(override val file: File) : Step()
        data class Message(override val file: File, val text: String) : Step()
    }

    /** Starts the flow for [file] (incomplete: Resume; finished: Continue). */
    fun start(file: File) {
        if (GpxWriter.isIncomplete(file)) begin(file) else step = Step.ConfirmContinue(file)
    }

    /** The checks from the trip question on; set by [rememberResumeFlow]. */
    internal var begin: (File) -> Unit = {}
}

private fun verb(file: File) = if (GpxWriter.isIncomplete(file)) "Resume" else "Continue"

@Composable
fun rememberResumeFlow(onStarted: () -> Unit): ResumeFlow {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember { ResumeFlow() }

    fun go(file: File) {
        var target = file
        if (!GpxWriter.isIncomplete(file)) {
            target = Trips.reopenFinished(context, file) ?: run {
                flow.step = ResumeFlow.Step.Message(file, "Could not reopen ${file.name}"); return
            }
        }
        flow.step = null
        target.parentFile?.parentFile?.name?.let { Trips.setCurrentTrip(context, it) }
        RecordingService.resumeIncomplete(context, target)
        onStarted()
    }

    fun locate(file: File) {
        if (!hasLocationPermission(context)) { flow.step = ResumeFlow.Step.Message(file, "Precise location permission is required to record."); return }
        if (!isGpsEnabled(context)) { flow.step = ResumeFlow.Step.Message(file, "Location is off. Turn it on in the phone's settings."); return }
        val last = GpxWriter.lastPoint(file)
        if (last == null) { go(file); return }
        val job = scope.launch {
            val here = currentGpsLocation(context)
            if (here == null) { flow.step = ResumeFlow.Step.NoFix(file); return@launch }
            val d = FloatArray(1).also { Location.distanceBetween(last.lat, last.lon, here.latitude, here.longitude, it) }[0]
            if (d > FAR_KM * 1000) flow.step = ResumeFlow.Step.Far(file, (d / 1000).toInt()) else go(file)
        }
        flow.step = ResumeFlow.Step.Locating(file, job)
    }

    fun checkTrip(file: File) {
        if (RecordingService.state.value !is RecordingState.Idle) {
            flow.step = ResumeFlow.Step.Message(file, "Stop the current recording first."); return
        }
        val trip = file.parentFile?.parentFile?.name
        val shown = Trips.currentTrip(context)
        if (trip != null && shown != null && trip != shown) flow.step = ResumeFlow.Step.OtherTrip(file, shown) else locate(file)
    }

    flow.begin = ::checkTrip

    when (val s = flow.step) {
        null -> {}
        is ResumeFlow.Step.ConfirmContinue -> AlertDialog(
            onDismissRequest = { flow.step = null },
            title = { Text("Continue this recording?") },
            text = { Text("New points go into the same file, as a new segment. It counts as incomplete until you press Stop.") },
            confirmButton = { TextButton(onClick = { checkTrip(s.file) }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { flow.step = null }) { Text("Cancel") } },
        )
        is ResumeFlow.Step.OtherTrip -> {
            val trip = s.file.parentFile?.parentFile?.name ?: ""
            AlertDialog(
                onDismissRequest = { flow.step = null },
                title = { Text("Another trip") },
                text = { Text("This recording belongs to $trip, not ${s.shown}. ${verb(s.file)} it and switch to $trip?") },
                confirmButton = { TextButton(onClick = { locate(s.file) }) { Text(verb(s.file)) } },
                dismissButton = { TextButton(onClick = { flow.step = null }) { Text("Cancel") } },
            )
        }
        is ResumeFlow.Step.Locating -> AlertDialog(
            onDismissRequest = { },
            title = { Text("Getting your location…") },
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(16.dp)); Text("Checking you're near where this recording stopped.") } },
            confirmButton = { TextButton(onClick = { s.job.cancel(); flow.step = null }) { Text("Cancel") } },
        )
        is ResumeFlow.Step.Far -> AlertDialog(
            onDismissRequest = { flow.step = null },
            title = { Text("Far from this recording") },
            text = { Text("You're ${s.km} km from where this recording stopped. ${verb(s.file)} it anyway?") },
            confirmButton = { TextButton(onClick = { go(s.file) }) { Text(verb(s.file)) } },
            dismissButton = { TextButton(onClick = { flow.step = null }) { Text("Cancel") } },
        )
        // No position, no check: it can't be resumed until the GPS finds one.
        is ResumeFlow.Step.NoFix -> AlertDialog(
            onDismissRequest = { flow.step = null },
            title = { Text("No GPS fix") },
            text = { Text("Could not get your position, so it can't be checked against where this recording stopped. Try again outdoors.") },
            confirmButton = { TextButton(onClick = { flow.step = null }) { Text("OK") } },
        )
        is ResumeFlow.Step.Message -> AlertDialog(
            onDismissRequest = { flow.step = null },
            text = { Text(s.text) },
            confirmButton = { TextButton(onClick = { flow.step = null }) { Text("OK") } },
        )
    }
    return flow
}
