package com.lotanbar.tripexplorer.service

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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lotanbar.tripexplorer.MainActivity
import com.lotanbar.tripexplorer.R
import com.lotanbar.tripexplorer.data.Trips
import com.lotanbar.tripexplorer.ui.poi.AudioNoteRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

sealed class VoiceState {
    data object Idle : VoiceState()
    data class Active(val file: File, val trip: String, val startedMs: Long) : VoiceState()
    /** The last start or stop went wrong; shown once, then back to Idle. */
    data class Failed(val message: String) : VoiceState()
}

/**
 * A general recording: audio of the trip that belongs to no POI, written straight to
 * trips/<trip>/general_recordings/DD.MM.YYYY HH-MM-SS.opus. A microphone foreground service,
 * so it keeps recording with the screen off or another app open. Stop from the app or the notification.
 */
class VoiceRecordingService : Service() {
    private val recorder by lazy { AudioNoteRecorder(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "General recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a general audio recording is running"
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val trip = intent.getStringExtra(EXTRA_TRIP)
                if (trip == null || _state.value is VoiceState.Active) return START_NOT_STICKY
                start(trip)
            }
            ACTION_STOP -> stop()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(trip: String) {
        val startedMs = System.currentTimeMillis()
        val file = File(Trips.generalRecordingsDir(trip), Trips.voiceFileName(startedMs))
        val ok = runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, build(trip, startedMs), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            recorder.start(file)
        }
        ok.onSuccess { _state.value = VoiceState.Active(file, trip, startedMs) }
            .onFailure {
                _state.value = VoiceState.Failed("Could not start recording: ${it.message}")
                finish()
            }
    }

    private fun stop() {
        val active = _state.value as? VoiceState.Active
        val saved = recorder.stop()
        if (saved != null) Trips.scan(this, saved.parentFile!!, saved)
        _state.value = if (active != null && saved == null) VoiceState.Failed("Nothing was recorded.") else VoiceState.Idle
        finish()
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun build(trip: String, startedMs: Long): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, intent(this, ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_group_marker)
            .setContentTitle("Recording audio")
            .setContentText(trip)
            .setWhen(startedMs)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setContentIntent(tap)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        // Going away while recording: close the file so what was recorded stays playable.
        if (_state.value is VoiceState.Active) {
            recorder.stop()?.let { Trips.scan(this, it) }
            _state.value = VoiceState.Idle
        }
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.lotanbar.tripexplorer.VOICE_START"
        private const val ACTION_STOP = "com.lotanbar.tripexplorer.VOICE_STOP"
        private const val EXTRA_TRIP = "trip"
        private const val CHANNEL = "general_recording"
        private const val NOTIF_ID = 3

        private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
        val state: StateFlow<VoiceState> = _state

        fun start(context: Context, trip: String) =
            ContextCompat.startForegroundService(context, intent(context, ACTION_START).putExtra(EXTRA_TRIP, trip))

        fun stop(context: Context) = context.startService(intent(context, ACTION_STOP))

        fun clearFailure() { if (_state.value is VoiceState.Failed) _state.value = VoiceState.Idle }

        private fun intent(context: Context, action: String) =
            Intent(context, VoiceRecordingService::class.java).setAction(action)
    }
}
