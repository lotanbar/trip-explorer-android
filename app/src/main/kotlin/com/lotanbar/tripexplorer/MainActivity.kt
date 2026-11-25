package com.lotanbar.tripexplorer

import android.app.KeyguardManager
import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.lotanbar.tripexplorer.ui.poi.AddPoiSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.lotanbar.tripexplorer.ui.main.MainScreen
import com.lotanbar.tripexplorer.ui.permission.StoragePermissionScreen
import com.lotanbar.tripexplorer.ui.poi.PoiScreen
import com.lotanbar.tripexplorer.ui.sync.DriveScreen
import java.io.File
import com.lotanbar.tripexplorer.ui.theme.TripExplorerTheme

/** The screens: the main screen (Add POI and the POI list happen inside it), the Drive screen and a POI's screen. */
sealed class Screen {
    data object Main : Screen()
    data object Drive : Screen()
    data class Poi(val path: String) : Screen()

    /** Encoded for the saved instance state, so the stack survives the activity being recreated. */
    fun encode(): String = when (this) {
        Main -> "main"
        Drive -> "drive"
        is Poi -> "poi:$path"
    }

    companion object {
        fun decode(s: String): Screen = when {
            s == "drive" -> Drive
            s.startsWith("poi:") -> Poi(s.removePrefix("poi:"))
            else -> Main
        }
    }
}

class MainActivity : ComponentActivity() {
    /**
     * Samsung's side key double press opens the app's main entry, whichever one the setting names. On a locked
     * phone that would ask for the PIN; instead, the main screen steps over the lock screen just long enough to
     * hand over to the Add POI entry, so the double press goes straight into Add POI. Only for a launcher start
     * (the side key, the app icon) on a locked phone; nothing of the main screen is shown there.
     */
    private fun handOverToAddPoiIfLocked(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_MAIN || intent.hasCategory(Intent.CATEGORY_LAUNCHER).not()) return false
        if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        startActivity(Intent(this, AddPoiShortcut::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        setShowWhenLocked(false)
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (handOverToAddPoiIfLocked(intent)) moveTaskToBack(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && handOverToAddPoiIfLocked(intent)) { finish(); return }
        SideKey.configureOnce(this)
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

/** Set when "Add POI" is opened (the side button's double press); the main screen starts Add POI and clears it. */
object ShortcutRequest {
    val requested = MutableStateFlow(false)
}

/**
 * The "Add POI" entry: the main screen, shown over the lock screen, starting Add POI at once. It closes as soon
 * as that POI is saved or cancelled, so a locked phone goes back to its lock screen.
 */
class AddPoiShortcut : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) ShortcutRequest.requested.value = true
        lifecycleScope.launch {
            var started = false
            AddPoiSession.state.collect { st ->
                if (st != null) started = true
                else if (started) finish()
            }
        }
        enableEdgeToEdge()
        setContent {
            TripExplorerTheme {
                Surface(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    App()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ShortcutRequest.requested.value = true
    }
}

private val stackSaver = listSaver<MutableList<Screen>, String>(
    save = { stack -> stack.map { it.encode() } },
    restore = { saved -> saved.map(Screen::decode).distinct().toMutableStateList() },
)

@Composable
internal fun App() {
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

    // The "Add POI" entry: back to the main screen, where Add POI starts.
    val shortcut by ShortcutRequest.requested.collectAsState()
    LaunchedEffect(shortcut) { if (shortcut) while (stack.size > 1) stack.removeAt(stack.lastIndex) }

    val screen = stack.last()
    // Each screen keeps its own saved state while another one is on top of it.
    stateHolder.SaveableStateProvider(key = "${stack.lastIndex}:${screen.encode()}") {
        when (screen) {
            Screen.Main -> MainScreen(onOpenDrive = { push(Screen.Drive) }, onOpenPoi = { push(Screen.Poi(it.path)) })
            Screen.Drive -> DriveScreen(onDone = ::pop)
            is Screen.Poi -> PoiScreen(File(screen.path), onBack = ::pop)
        }
    }
}
