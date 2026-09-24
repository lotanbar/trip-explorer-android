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
 * Google Drive sync on the phone. The Sync switch (remembered) runs [SyncService]: while it is on the
 * trips folder syncs live on any network, also in the background; while it is off nothing is sent or
 * fetched. Edits made while it is off go up when it is turned on (newest wins per file).
 */
object Sync {
    private const val PREFS = "trip_explorer"
    private const val KEY_ON = "drive_sync_on"

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

    fun isOn(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        val intent = Intent(context, SyncService::class.java)
        if (on) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
    }

    /** Starts the service again after the app starts, if the switch was left on. */
    fun resume(context: Context) {
        engine(context)
        if (isOn(context) && !(_status.value.running)) setOn(context, true)
    }

    /** Something was written in trips/ (e.g. a recording save): look now instead of within 5 s. */
    fun poke() {
        engine?.poke()
    }
}

class SyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Drive sync", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
    }

    private var lastNotified = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Sync.isOn(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, build(Sync.status.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val engine = Sync.engine(this)
        engine.root = Trips.root
        engine.start()
        engine.poke()
        watchJob?.interrupt()
        watchJob = Thread {
            try {
                while (true) {
                    val s = Sync.status.value
                    val key = "${s.busy}/${s.done}/${s.total}/${s.error}/${s.folder}"
                    if (key != lastNotified) {
                        lastNotified = key
                        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(s))
                    }
                    Thread.sleep(700)
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
        return START_STICKY
    }

    private var watchJob: Thread? = null

    private fun build(s: SyncStatus): Notification {
        val tap = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_group_marker)
            .setContentTitle(if (s.busy && s.total > 0) "Syncing with Google Drive" else "Drive sync on")
            .setContentText(statusLine(s))
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
        if (s.busy && s.total > 0) b.setProgress(s.total, s.done, false)
        return b.build()
    }

    override fun onDestroy() {
        watchJob?.interrupt()
        Sync.engine(this).stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "drive_sync"
        private const val NOTIF_ID = 2
    }
}

/** One line about the sync, for the main screen and the notification. */
fun statusLine(s: SyncStatus): String = when {
    !s.signedIn -> s.error ?: "Not signed in"
    s.folder == null -> "No Drive folder picked"
    s.error != null -> s.error
    s.busy && s.total > 0 -> "Syncing ${s.done} of ${s.total}"
    s.busy -> "Checking…"
    !s.running -> "Off · “${s.folder}”"
    s.lastSync != null -> "“${s.folder}” · synced ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(s.lastSync))}"
    else -> "“${s.folder}”"
}
