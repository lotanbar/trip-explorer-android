package com.lotanbar.tripexplorer.ui.poi

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.lotanbar.tripexplorer.data.PoiStore
import java.io.File

/** Full-screen pager with zoomable photos and an audio player. */
@Composable
fun MediaPreviewScreen(paths: List<String>, startIndex: Int) {
    if (paths.isEmpty()) return
    val safeStart = startIndex.coerceIn(0, paths.size - 1)
    val pagerState = rememberPagerState(initialPage = safeStart) { paths.size }
    var zoomedPage by remember { mutableIntStateOf(-1) }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().background(Color.Black),
        userScrollEnabled = zoomedPage != pagerState.currentPage,
    ) { page ->
        val path = paths[page]
        if (PoiStore.isAudio(File(path))) {
            AudioPlayer(path = path)
        } else {
            ZoomableImage(
                path = path,
                onZoomChanged = { zoomed -> if (zoomed) zoomedPage = page else if (zoomedPage == page) zoomedPage = -1 },
            )
        }
    }
}

@Composable
fun ZoomableImage(path: String, onZoomChanged: (Boolean) -> Unit = {}) {
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var viewport by remember(path) { mutableStateOf(IntSize.Zero) }

    fun updateTransform(targetScale: Float, pan: Offset = Offset.Zero) {
        val newScale = targetScale.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val maxX = viewport.width * (newScale - 1f) / 2f
        val maxY = viewport.height * (newScale - 1f) / 2f
        scale = newScale
        offset = if (newScale == MIN_ZOOM) Offset.Zero else Offset(
            x = (offset.x + pan.x).coerceIn(-maxX, maxX),
            y = (offset.y + pan.y).coerceIn(-maxY, maxY),
        )
        onZoomChanged(newScale > MIN_ZOOM)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .pointerInput(path) {
                detectTapGestures(onDoubleTap = { updateTransform(if (scale > MIN_ZOOM) MIN_ZOOM else DOUBLE_TAP_ZOOM) })
            },
    ) {
        AsyncImage(
            model = File(path),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(path) { detectTransformGestures { _, pan, zoom, _ -> updateTransform(scale * zoom, pan) } }
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            contentScale = ContentScale.Fit,
        )
    }
}

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
fun AudioPlayer(path: String) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(path) {
        player.setMediaItem(MediaItem.fromUri(File(path).toURI().toString()))
        player.prepare()
        onDispose { player.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                controllerShowTimeoutMs = 0
                controllerHideOnTouch = false
                useArtwork = true
                defaultArtwork = ContextCompat.getDrawable(ctx, android.R.drawable.ic_media_play)
            }
        },
        update = { view -> view.showController() },
        modifier = Modifier.fillMaxSize(),
    )
}
