package com.lotanbar.tripexplorer

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lotanbar.tripexplorer.ui.main.MainScreen
import com.lotanbar.tripexplorer.ui.permission.StoragePermissionScreen
import com.lotanbar.tripexplorer.ui.poi.AddPoiScreen
import com.lotanbar.tripexplorer.ui.poi.MediaPreviewScreen
import com.lotanbar.tripexplorer.ui.poi.PoiListScreen
import com.lotanbar.tripexplorer.ui.poi.PoiScreen
import com.lotanbar.tripexplorer.ui.theme.TripExplorerTheme
import java.io.File

sealed class Screen {
    data object Main : Screen()
    data class AddPoi(val trip: String, val lat: Double, val lon: Double, val atMs: Long) : Screen()
    data object PoiList : Screen()
    data class Poi(val dir: File) : Screen()
    data class Media(val paths: List<String>, val index: Int) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TripExplorerTheme {
                Surface(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    App()
                }
            }
        }
    }
}

@Composable
private fun App() {
    var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    if (!granted) {
        StoragePermissionScreen { granted = true }
        return
    }
    val stack = remember { mutableStateListOf<Screen>(Screen.Main) }
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { pop() }

    when (val screen = stack.last()) {
        Screen.Main -> MainScreen(
            onAddPoi = { trip, lat, lon, atMs -> push(Screen.AddPoi(trip, lat, lon, atMs)) },
            onOpenPoiList = { push(Screen.PoiList) },
        )
        is Screen.AddPoi -> AddPoiScreen(screen.trip, screen.lat, screen.lon, screen.atMs, onDone = ::pop)
        Screen.PoiList -> PoiListScreen(onOpen = { push(Screen.Poi(it)) })
        is Screen.Poi -> PoiScreen(
            dir = screen.dir,
            onBack = ::pop,
            onOpenMedia = { paths, index -> push(Screen.Media(paths, index)) },
        )
        is Screen.Media -> MediaPreviewScreen(screen.paths, screen.index)
    }
}
