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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
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

    /** Encoded for the saved instance state, so the stack survives process death (e.g. under the camera). */
    fun encode(): List<String> = when (this) {
        Main -> listOf("main")
        is AddPoi -> listOf("add", trip, lat.toString(), lon.toString(), atMs.toString())
        PoiList -> listOf("list")
        is Poi -> listOf("poi", dir.absolutePath)
        is Media -> listOf("media", index.toString()) + paths
    }

    companion object {
        fun decode(parts: List<String>): Screen = when (parts[0]) {
            "add" -> AddPoi(parts[1], parts[2].toDouble(), parts[3].toDouble(), parts[4].toLong())
            "list" -> PoiList
            "poi" -> Poi(File(parts[1]))
            "media" -> Media(parts.drop(2), parts[1].toInt())
            else -> Main
        }
    }
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

private val stackSaver = listSaver<MutableList<Screen>, String>(
    save = { stack -> stack.map { it.encode().joinToString("\u0000") } },
    restore = { saved -> saved.map { Screen.decode(it.split("\u0000")) }.toMutableStateList() },
)

@Composable
private fun App() {
    var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    if (!granted) {
        StoragePermissionScreen { granted = true }
        return
    }
    val stack = rememberSaveable(saver = stackSaver) { mutableListOf<Screen>(Screen.Main).toMutableStateList() }
    val stateHolder = rememberSaveableStateHolder()
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { pop() }

    val screen = stack.last()
    // Each screen keeps its own saved state while another one is on top of it.
    stateHolder.SaveableStateProvider(key = "${stack.lastIndex}:${screen.encode().joinToString("/").hashCode()}") {
        when (screen) {
            Screen.Main -> MainScreen(
                onAddPoi = { trip, lat, lon, atMs -> push(Screen.AddPoi(trip, lat, lon, atMs)) },
                onOpenPoiList = { push(Screen.PoiList) },
            )
            is Screen.AddPoi -> AddPoiScreen(screen.trip, screen.lat, screen.lon, screen.atMs, onDone = ::pop)
            Screen.PoiList -> PoiListScreen(onOpen = { push(Screen.Poi(it)) })
            is Screen.Poi -> PoiScreen(
                dir = screen.dir,
                onBack = ::pop,
                onRenamed = { newDir -> stack[stack.lastIndex] = Screen.Poi(newDir) },
                onOpenMedia = { paths, index -> push(Screen.Media(paths, index)) },
            )
            is Screen.Media -> MediaPreviewScreen(screen.paths, screen.index)
        }
    }
}
