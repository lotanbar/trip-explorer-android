package com.lotanbar.tripexplorer.ui.poi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.lotanbar.tripexplorer.service.RecordingService
import kotlinx.coroutines.delay
import java.io.File

/** Records an AAC audio note (.m4a) with the phone's microphone, inside the app. */
class AudioNoteRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    var output: File? = null
        private set

    fun start(target: File) {
        target.parentFile?.mkdirs()
        val r = MediaRecorder(context)
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(96_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(target.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        output = target
    }

    /** Stops and returns the file, or null if nothing usable was recorded. */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val f = output
        output = null
        return if (ok && f != null && f.length() > 0) f else { f?.delete(); null }
    }

    fun cancel() { stop() }
}

/**
 * A Record / Stop button. [newFile] gives the file to record into when the user presses Record;
 * [onRecorded] receives the finished file.
 */
@Composable
fun AudioNoteButton(
    newFile: () -> File,
    onRecorded: (File) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val recorder = remember { AudioNoteRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var startedMs by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(0L) }

    fun begin() {
        runCatching { recorder.start(newFile()) }
            .onSuccess { recording = true; startedMs = System.currentTimeMillis(); nowMs = startedMs }
            .onFailure { onError("Could not start recording: ${it.message}") }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else onError("Microphone permission is required for audio notes.")
    }

    LaunchedEffect(recording) { while (recording) { delay(500L); nowMs = System.currentTimeMillis() } }
    DisposableEffect(Unit) { onDispose { if (recording) recorder.cancel() } }

    if (recording) {
        Button(
            onClick = { recording = false; recorder.stop()?.let(onRecorded) ?: onError("Nothing was recorded.") },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
            modifier = modifier,
        ) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Stop ${RecordingService.formatElapsed(nowMs - startedMs)}")
        }
    } else {
        OutlinedButton(
            onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
                else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            modifier = modifier,
        ) {
            Icon(Icons.Default.Mic, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Record note")
        }
    }
}

