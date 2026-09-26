package com.lotanbar.tripexplorer.ui.poi

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.lotanbar.tripexplorer.data.Trips
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * In-app camera: take as many photos as you like, one after the other, then press Done.
 * Tap the preview to focus there (a ring shows where), pinch to zoom.
 * Each photo is written by [newFile] as soon as it is taken; [onDone] gets all of them.
 * Back finishes too (keeping the photos taken so far).
 */
@Composable
fun CameraCapture(newFile: () -> File, onDone: (List<File>) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }

    var taken by remember { mutableStateOf(listOf<File>()) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    var focusTap by remember { mutableStateOf(0) }
    LaunchedEffect(focusTap) { if (focusAt != null) { delay(1200); focusAt = null } }

    // Tap to focus and meter on that point (back to continuous focus after 5 s); pinch to zoom.
    DisposableEffect(previewView) {
        val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val cam = camera ?: return false
                val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(5, TimeUnit.SECONDS)
                    .build()
                cam.cameraControl.startFocusAndMetering(action)
                focusAt = Offset(e.x, e.y)
                focusTap++
                return true
            }
        })
        val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val zoom = cam.cameraInfo.zoomState.value ?: return false
                val ratio = (zoom.zoomRatio * detector.scaleFactor).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
                cam.cameraControl.setZoomRatio(ratio)
                return true
            }
        })
        @SuppressLint("ClickableViewAccessibility")
        val listener = android.view.View.OnTouchListener { _, event ->
            pinch.onTouchEvent(event)
            if (!pinch.isInProgress) taps.onTouchEvent(event)
            true
        }
        previewView.setOnTouchListener(listener)
        onDispose { previewView.setOnTouchListener(null) }
    }

    BackHandler { onDone(taken) }
    DisposableEffect(Unit) {
        onDispose { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } }
    }

    // Bind the preview and capture use cases whenever the lens changes.
    LaunchedEffect(granted, lensFacing) {
        if (!granted) return@LaunchedEffect
        val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        runCatching {
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
        }.onFailure { error = "Camera unavailable: ${it.message}" }
    }

    fun shoot() {
        if (busy) return
        busy = true
        val file = newFile()
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    busy = false
                    taken = taken + file
                    Trips.scan(context, file)
                }
                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    file.delete()
                    error = "Could not take the photo: ${exception.message}"
                }
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            focusAt?.let { at ->
                val ring = 72.dp
                val half = with(LocalDensity.current) { (ring / 2).roundToPx() }
                Box(
                    Modifier.offset { IntOffset(at.x.toInt() - half, at.y.toInt() - half) }
                        .size(ring).border(2.dp, Color.White, CircleShape),
                )
            }
        } else {
            Text(
                if (denied) "Camera permission is required to take photos." else "Waiting for camera permission…",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).systemBarsPadding()) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            if (taken.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(taken, key = { it.absolutePath }) { file ->
                        AsyncImage(
                            model = file,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                    },
                    modifier = Modifier.size(56.dp).background(Color.White.copy(alpha = 0.15f), CircleShape),
                ) { Icon(Icons.Default.Cameraswitch, contentDescription = "Switch camera", tint = Color.White) }
                Box(
                    Modifier.size(80.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(8.dp)
                        .clip(CircleShape).background(if (busy) Color.Gray else Color.White)
                        .clickable(enabled = granted && !busy) { shoot() },
                )
                Button(onClick = { onDone(taken) }, modifier = Modifier.height(48.dp)) {
                    Text(if (taken.isEmpty()) "Skip" else "Done (${taken.size})", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
