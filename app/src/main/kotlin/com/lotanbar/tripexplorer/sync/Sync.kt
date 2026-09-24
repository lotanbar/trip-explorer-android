package com.lotanbar.tripexplorer.sync

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lotanbar.tripexplorer.BuildConfig
import com.lotanbar.tripexplorer.MainActivity
import com.lotanbar.tripexplorer.R
import com.lotanbar.tripexplorer.data.Trips
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Google Drive sync on the phone: nothing happens by itself. The Sync button runs one pass (Drive's
 * changes come down, the phone's go up; the newer change wins) inside [SyncService], so it carries on
 * if the app goes to the background, with its progress in a notification.
 */
object Sync {
    @SuppressLint("StaticFieldLeak") // the application context
    private var engine: SyncEngine? = null
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status
    /** Bumped whenever the sync changed files here: screens read the folders again. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    val configured get() = BuildConfig.GOOGLE_CLIENT_ID.isNotEmpty()

    fun engine(context: Context): SyncEngine = synchronized(this) {
        engine ?: run {
            val app = context.applicationContext
            SyncEngine(File(app.filesDir, "sync"), BuildConfig.GOOGLE_CLIENT_ID, BuildConfig.GOOGLE_CLIENT_SECRET, object : SyncEngine.Listener {
                override fun onStatus(status: SyncStatus) { _status.value = status }
                override fun onLocalChanged(files: List<File>) {
                    Trips.scan(app, *files.toTypedArray())
                    _changes.value++
                }
            }).also {
                it.root = Trips.root
                engine = it
                _status.value = it.status
            }
        }
    }

    /** The Sync button. */
    fun syncNow(context: Context) {
        engine(context)
        if (_status.value.busy) return
        ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))
    }
}

/** Runs one sync pass in the foreground (so it survives the app going to the background), then stops. */
class SyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    private var watcher: Thread? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Drive sync", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, build(Sync.status.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (watcher != null) return START_NOT_STICKY
        val engine = Sync.engine(this)
        engine.root = Trips.root
        val before = Sync.status.value.lastSync
        val errorBefore = Sync.status.value.error
        engine.request()
        watcher = Thread {
            var lastKey = ""
            val started = System.currentTimeMillis()
            while (true) {
                val s = Sync.status.value
                val key = "${s.busy}/${s.done}/${s.total}/${s.current}"
                if (key != lastKey) {
                    lastKey = key
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(s))
                }
                // Done: a new pass finished, or it failed (a new error, or any error once it stopped being busy).
                val finished = !s.busy && (s.lastSync != before || (s.error != null && (s.error != errorBefore || System.currentTimeMillis() - started > 3000)))
                if (finished) break
                try { Thread.sleep(400) } catch (_: InterruptedException) { break }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.apply { isDaemon = true; start() }
        return START_NOT_STICKY
    }

    private fun build(s: SyncStatus): Notification {
        val tap = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_group_marker)
            .setContentTitle(if (s.total > 0) "Syncing with Google Drive · ${s.done} of ${s.total}" else "Syncing with Google Drive")
            .setContentText(s.current ?: "")
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
        if (s.total > 0) b.setProgress(s.total, s.done, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    override fun onDestroy() {
        watcher?.interrupt()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "drive_sync"
        private const val NOTIF_ID = 2
    }
}

/** A few words about the sync, for the main screen's top row. */
fun shortStatus(s: SyncStatus): String = when {
    !s.signedIn -> "Signed out"
    s.folder == null -> "No folder"
    s.busy && s.total > 0 -> "${s.done}/${s.total}"
    s.busy -> "Syncing"
    s.error != null -> "Error"
    s.lastSync != null -> java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(s.lastSync))
    else -> "Not synced"
}

/** While syncing: the item on its way, MB done and roughly how long is left. */
fun progressLine(s: SyncStatus): String {
    fun mb(b: Long) = if (b < 10L * 1048576) String.format(java.util.Locale.US, "%.1f", b / 1048576.0) else (b / 1048576).toString()
    fun left(sec: Long) = if (sec < 60) "$sec s" else "${(sec + 30) / 60} min"
    return listOfNotNull(
        s.current,
        if (s.bytesTotal > 0) "${mb(s.bytesDone)} of ${mb(s.bytesTotal)} MB" else null,
        s.etaS?.let { "~${left(it)} left" },
    ).joinToString(" · ")
}
