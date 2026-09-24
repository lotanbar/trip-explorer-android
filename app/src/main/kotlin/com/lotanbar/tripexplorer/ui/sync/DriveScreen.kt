package com.lotanbar.tripexplorer.ui.sync

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lotanbar.tripexplorer.BuildConfig
import com.lotanbar.tripexplorer.sync.DriveApi
import com.lotanbar.tripexplorer.sync.RemoteFile
import com.lotanbar.tripexplorer.sync.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Google Drive setup: sign in (in the browser), then pick the Drive folder the trips folder syncs with.
 * The list starts at My Drive; a folder opens with a tap. My Drive itself cannot be picked.
 */
@Composable
fun DriveScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { Sync.engine(context) }
    val status by Sync.status.collectAsStateWithLifecycle()
    var signInJob by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun signIn() {
        signInJob = scope.launch {
            try {
                val (token, email) = runInterruptible(Dispatchers.IO) {
                    val back = "tripexplorer://signed-in"
                    val token = DriveApi.signIn(BuildConfig.GOOGLE_CLIENT_ID, BuildConfig.GOOGLE_CLIENT_SECRET, back) { url ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    token to DriveApi(BuildConfig.GOOGLE_CLIENT_ID, BuildConfig.GOOGLE_CLIENT_SECRET, token).aboutEmail()
                }
                engine.saveAuth(token, email)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Sign-in failed: ${e.message}"
            } finally {
                signInJob = null
            }
        }
    }

    message?.let { m ->
        AlertDialog(onDismissRequest = { message = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } })
    }
    signInJob?.let { job ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Signing in…") },
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(16.dp)); Text("Finish in the browser, then come back here.") } },
            confirmButton = { TextButton(onClick = { job.cancel(); signInJob = null }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Google Drive sync", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        if (!Sync.configured) {
            Text("This build has no Google client, so Drive sync is off.")
            return@Column
        }
        if (!status.signedIn) {
            Text("Sign in with Google in the browser. Trip Explorer asks for access to your Drive to keep the trips folder in sync with a Drive folder.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = ::signIn, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Sign in with Google") }
            return@Column
        }
        Text(listOfNotNull(status.email, status.folder?.let { "syncs with “$it”" }).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        status.error?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Color(0xFFEF5350)) }
        Spacer(Modifier.height(8.dp))
        // Signed in again with a folder already picked: carry on with it (no new merge), or pick another below.
        status.folder?.let { folder ->
            if (!Sync.isOn(context)) {
                Button(onClick = { Sync.setOn(context, true); onDone() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Keep syncing with “$folder”") }
                Spacer(Modifier.height(8.dp))
                Text("Or pick another folder:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FolderPicker(
            onPick = { f ->
                engine.pickFolder(f.id, f.name)
                Sync.setOn(context, true)
                onDone()
            },
            onSignOut = {
                Sync.setOn(context, false)
                engine.signOut()
            },
            onError = { message = it },
        )
    }
}

@Composable
private fun FolderPicker(onPick: (RemoteFile) -> Unit, onSignOut: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { Sync.engine(context) }
    var stack by remember { mutableStateOf(listOf("root" to "My Drive")) }
    var folders by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var newFolder by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<RemoteFile?>(null) }
    val here = stack.last()

    LaunchedEffect(here, reload) {
        folders = null
        folders = try {
            withContext(Dispatchers.IO) { engine.client()!!.children(here.first, true) }
        } catch (e: Exception) {
            onError(e.message ?: e.toString()); emptyList()
        }
    }

    newFolder?.let { name ->
        AlertDialog(
            onDismissRequest = { newFolder = null },
            title = { Text("New folder in ${here.second}") },
            text = { OutlinedTextField(value = name, onValueChange = { newFolder = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    newFolder = null
                    scope.launch {
                        try {
                            val f = withContext(Dispatchers.IO) { engine.client()!!.createFolder(here.first, name.trim()) }
                            stack = stack + (f.id to f.name)
                        } catch (e: Exception) {
                            onError("Could not create the folder: ${e.message}")
                        }
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newFolder = null }) { Text("Cancel") } },
        )
    }
    confirm?.let { f ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Sync with “${f.name}”?") },
            text = { Text("The trips folder and this Drive folder are merged: files on only one side are copied to the other, and where both have a file the newer one is kept. Sync turns on.") },
            confirmButton = { TextButton(onClick = { confirm = null; onPick(f) }) { Text("Sync") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            stack.forEachIndexed { i, (_, name) ->
                if (i > 0) Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    name,
                    maxLines = 1,
                    softWrap = false,
                    color = if (i == stack.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(enabled = i < stack.lastIndex) { stack = stack.take(i + 1) }.padding(vertical = 8.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val list = folders
            when {
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> Text("No folders here", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { stack = stack + (f.id to f.name) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFFFFC107))
                            Text(f.name, style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSignOut) { Text("Sign out") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { newFolder = "" }) { Text("New folder") }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { confirm = RemoteFile(here.first, here.second, emptyList(), false, null, 0, 0, DriveApi.FOLDER_MIME) },
            enabled = stack.size > 1,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (stack.size > 1) "Sync with “${here.second}”" else "Open a folder to sync with") }
    }
}
