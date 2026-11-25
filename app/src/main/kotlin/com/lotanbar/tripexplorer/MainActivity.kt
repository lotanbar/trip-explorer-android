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
import com.lotanbar.tripexplorer.ui.sync.DriveScreen
import com.lotanbar.tripexplorer.ui.theme.TripExplorerTheme

/** The phone has two screens: the main screen (Add POI happens inside it) and the Drive screen. */
sealed class Screen {
    data object Main : Screen()
    data object Drive : Screen()

    /** Encoded for the saved instance state, so the stack survives the activity being recreated. */
    fun encode(): String = when (this) {
        Main -> "main"
        Drive -> "drive"
    }

    companion object {
        fun decode(s: String): Screen = if (s == "drive") Drive else Main
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
    save = { stack -> stack.map { it.encode() } },
    restore = { saved -> saved.map(Screen::decode).distinct().toMutableStateList() },
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
    stateHolder.SaveableStateProvider(key = "${stack.lastIndex}:${screen.encode()}") {
        when (screen) {
            Screen.Main -> MainScreen(onOpenDrive = { push(Screen.Drive) })
            Screen.Drive -> DriveScreen(onDone = ::pop)
        }
    }
}
