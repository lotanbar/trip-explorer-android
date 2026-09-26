package com.lotanbar.tripexplorer.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lotanbar.tripexplorer.MainActivity
import com.lotanbar.tripexplorer.R
import com.lotanbar.tripexplorer.data.GpxPoint
import com.lotanbar.tripexplorer.data.GpxWriter
import com.lotanbar.tripexplorer.data.Trips
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

sealed class RecordingState {
    data object Idle : RecordingState()
    data class Active(
        val file: File,
        val trip: String,
        /** Time recorded before the current stretch (pauses and the gap before a resume are not counted). */
        val recordedMs: Long,
        /** When the current stretch started; null while paused. */
        val runningSinceMs: Long?,
        val paused: Boolean,
        val pointCount: Int,
        /** Distance walked so far (m): jitter under 5 m (or under the fix's accuracy) is not counted. */
        val distanceM: Double = 0.0,
        /** Not moving for 10 minutes: GPS is off until the phone senses walking or riding. The timer keeps running. */
        val autoPaused: Boolean = false,
    ) : RecordingState() {
        /** The time recorded so far: what the timer shows. */
        fun elapsedMs(nowMs: Long): Long = recordedMs + (runningSinceMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0)
    }
}

/**
 * Foreground service that records location fixes to a GPX file: one fix per second from the fused
 * provider (GPS helped by Wi-Fi and cell towers; the GPS provider if the phone has no fused one),
 * batched by up to 10 s when the phone supports it, saved to disk every 10 s.
 * Pause switches GPS off and starts a new segment; Stop renames the file to its final name.
 *
 * Auto-pause: after 10 minutes with fewer than 20 steps and every accurate fix within 50 m of their
 * middle, GPS goes off (same segment, the timer keeps running). It comes back on at 10 steps within
 * a minute or when the significant-motion sensor fires (walking, a car, a train). Needs the step
 * detector, the significant-motion sensor and the Physical activity permission; without them GPS
 * simply stays on.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val fileLock = Mutex()
    private var writer: GpxWriter? = null
    private val pending = ArrayList<GpxPoint>()
    private var flushJob: Job? = null
    /** The last fix the distance was counted from. */
    private var anchor: Location? = null
    private var tickerJob: Job? = null
    private var stopping = false

    /** When GPS last came on (elapsedRealtime ms): the still check waits a full window after it. */
    private var gpsOnSinceMs = 0L
    /** Steps (elapsedRealtime ms) and accurate fixes seen lately, for the still check and waking. */
    private val steps = ArrayDeque<Long>()
    private val recentFixes = ArrayDeque<Location>()
    private var sensorsOn = false
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private val stepSensor by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true) ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    }
    private val motionSensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = onStep()
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }
    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) { scope.launch { wake() } }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(listOf(location))
        override fun onLocationChanged(locations: MutableList<Location>) = onFix(locations)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            val st = state.value as? RecordingState.Active ?: return
            if (!st.paused) notify("GPS is off", "Turn location back on to keep recording")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a recording is running"
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val trip = intent.getStringExtra(EXTRA_TRIP) ?: run { stopSelf(); return START_NOT_STICKY }
                startForeground(NOTIF_ID, build("Recording…", trip))
                scope.launch { start(trip) }
            }
            ACTION_RESUME_INCOMPLETE -> {
                val path = intent.getStringExtra(EXTRA_FILE) ?: run { stopSelf(); return START_NOT_STICKY }
                startForeground(NOTIF_ID, build("Resuming recording…", File(path).name))
                scope.launch { resumeIncomplete(File(path)) }
            }
            ACTION_PAUSE -> scope.launch { pause() }
            ACTION_RESUME -> resume()
            ACTION_STOP -> scope.launch { stop() }
            null -> {
                // Restarted by the OS after being killed: continue into the same file as a new segment.
                val path = prefs().getString(KEY_ACTIVE_FILE, null)
                val file = path?.let(::File)
                if (file != null && file.isFile && GpxWriter.isIncomplete(file)) {
                    val ok = runCatching { startForeground(NOTIF_ID, build("Resuming recording…", file.name)) }.isSuccess
                    if (ok) scope.launch { resumeIncomplete(file) } else stopSelf()
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private suspend fun start(trip: String) {
        stopping = false
        val now = System.currentTimeMillis()
        val file = File(Trips.recordingsDir(trip), GpxWriter.recordingFileName(now))
        writer = fileLock.withLock { GpxWriter.create(file) }
        Trips.scan(this, file)
        prefs().edit().putString(KEY_ACTIVE_FILE, file.absolutePath).apply()
        anchor = null
        _state.value = RecordingState.Active(file, trip, recordedMs = 0, runningSinceMs = now, paused = false, pointCount = 0)
        startGps()
        startSensors()
        startTicker()
    }

    private suspend fun resumeIncomplete(file: File) {
        stopping = false
        val trip = file.parentFile?.parentFile?.name ?: ""
        val (w, count) = fileLock.withLock { GpxWriter.open(file) to GpxWriter.countPoints(file) }
        writer = w
        prefs().edit().putString(KEY_ACTIVE_FILE, file.absolutePath).apply()
        anchor = null
        _state.value = RecordingState.Active(
            file, trip, recordedMs = GpxWriter.recordedMs(file), runningSinceMs = System.currentTimeMillis(),
            paused = false, pointCount = count, distanceM = GpxWriter.distanceMeters(file),
        )
        startGps()
        startSensors()
        startTicker()
    }

    private suspend fun pause() {
        val st = state.value as? RecordingState.Active ?: return
        if (st.paused || stopping) return
        stopGps()
        stopSensors()
        flush()
        writer?.breakSegment()
        anchor = null // a new segment: the walk while paused is not counted
        _state.value = st.copy(paused = true, autoPaused = false, recordedMs = st.elapsedMs(System.currentTimeMillis()), runningSinceMs = null)
        notify("Recording paused", st.trip)
    }

    private fun resume() {
        val st = state.value as? RecordingState.Active ?: return
        if (!st.paused || stopping) return
        _state.value = st.copy(paused = false, runningSinceMs = System.currentTimeMillis())
        startGps()
        startSensors()
    }

    private suspend fun stop() {
        val st = state.value as? RecordingState.Active ?: run { stopSelf(); return }
        if (stopping) return
        stopping = true
        val stoppedAt = System.currentTimeMillis()
        stopGps()
        stopSensors()
        tickerJob?.cancel()
        flush()
        val finished = File(st.file.parentFile, GpxWriter.finishedFileName(st.file.name, stoppedAt))
        fileLock.withLock {
            if (!st.file.renameTo(finished)) {
                // Keep the file as it is rather than lose anything; it stays "incomplete".
            }
        }
        Trips.scan(this, finished, st.file)
        prefs().edit().remove(KEY_ACTIVE_FILE).apply()
        writer = null
        _state.value = RecordingState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onFix(locations: List<Location>) {
        val st = state.value as? RecordingState.Active ?: return
        if (st.paused || stopping) return
        var distance = st.distanceM
        for (loc in locations) {
            pending.add(GpxPoint(loc.latitude, loc.longitude, loc.time, if (loc.hasAccuracy()) loc.accuracy else Float.NaN))
            if (loc.hasAccuracy() && loc.accuracy <= STILL_RADIUS_M) recentFixes.addLast(loc)
            val accuracy = if (loc.hasAccuracy()) loc.accuracy else 0f
            if (accuracy > MAX_ACCURACY_FOR_DISTANCE) continue
            val a = anchor
            if (a == null) {
                anchor = loc
            } else {
                val d = a.distanceTo(loc)
                if (d >= maxOf(MIN_STEP_M, accuracy)) {
                    distance += d
                    anchor = loc
                }
            }
        }
        _state.value = st.copy(pointCount = st.pointCount + locations.size, distanceM = distance)
    }

    /** Writes whatever arrived since the last save. */
    private suspend fun flush() {
        if (pending.isEmpty()) return
        val batch = ArrayList(pending)
        pending.clear()
        val w = writer ?: return
        fileLock.withLock {
            kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { w.append(batch) } }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var tick = 0
            while (true) {
                delay(1_000L)
                val st = state.value as? RecordingState.Active ?: break
                tick++
                if (tick % SAVE_EVERY_SEC == 0) flush()
                if (!st.paused && !st.autoPaused) {
                    checkStill()
                    if ((state.value as? RecordingState.Active)?.autoPaused == true) continue
                    notify("Recording: ${formatElapsed(st.elapsedMs(System.currentTimeMillis()))} · ${formatDistance(st.distanceM)}", "${st.trip} · ${st.pointCount} points")
                }
            }
        }
    }

    /** Auto-pauses when the last 10 minutes of GPS had few steps and every accurate fix stayed close together. */
    private fun checkStill() {
        if (!sensorsOn) return
        val now = SystemClock.elapsedRealtime()
        if (now - gpsOnSinceMs < STILL_WINDOW_MS) return
        while (steps.isNotEmpty() && now - steps.first() > STILL_WINDOW_MS) steps.removeFirst()
        while (recentFixes.isNotEmpty() && now - recentFixes.first().elapsedRealtimeNanos / 1_000_000 > STILL_WINDOW_MS) recentFixes.removeFirst()
        if (steps.size >= STILL_MAX_STEPS) return
        if (recentFixes.isNotEmpty()) {
            val lat = recentFixes.sumOf { it.latitude } / recentFixes.size
            val lon = recentFixes.sumOf { it.longitude } / recentFixes.size
            val out = FloatArray(1)
            for (f in recentFixes) {
                Location.distanceBetween(lat, lon, f.latitude, f.longitude, out)
                if (out[0] > STILL_RADIUS_M) return
            }
        }
        val st = state.value as? RecordingState.Active ?: return
        stopGps()
        steps.clear()
        runCatching { sensorManager.requestTriggerSensor(motionListener, motionSensor) }
        _state.value = st.copy(autoPaused = true)
        notify("Recording: not moving, GPS off", "${st.trip} · resumes when you move")
        scope.launch { flush() }
    }

    private fun onStep() {
        val now = SystemClock.elapsedRealtime()
        steps.addLast(now)
        val st = state.value as? RecordingState.Active ?: return
        if (st.autoPaused && steps.count { now - it <= WAKE_WINDOW_MS } >= WAKE_STEPS) wake()
    }

    private fun wake() {
        val st = state.value as? RecordingState.Active ?: return
        if (!st.autoPaused || st.paused || stopping) return
        runCatching { sensorManager.cancelTriggerSensor(motionListener, motionSensor) }
        _state.value = st.copy(autoPaused = false)
        startGps()
    }

    private fun startSensors() {
        if (sensorsOn) return
        val allowed = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        val step = stepSensor
        if (!allowed || step == null || motionSensor == null) return
        sensorsOn = sensorManager.registerListener(stepListener, step, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun stopSensors() {
        if (!sensorsOn) return
        sensorsOn = false
        runCatching { sensorManager.unregisterListener(stepListener) }
        runCatching { sensorManager.cancelTriggerSensor(motionListener, motionSensor) }
        steps.clear()
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        gpsOnSinceMs = SystemClock.elapsedRealtime()
        steps.clear()
        recentFixes.clear()
        val lm = getSystemService(LocationManager::class.java)
        val request = LocationRequest.Builder(1_000L)
            .setMinUpdateDistanceMeters(0f)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setMaxUpdateDelayMillis(10_000L) // batching: used only if the phone supports it
            .build()
        runCatching {
            lm.requestLocationUpdates(locationProvider(lm), request, ContextCompat.getMainExecutor(this), listener)
        }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) notify("GPS is off", "Turn location on to record")
    }

    private fun stopGps() {
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(listener) }
    }

    private fun notify(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(title, text))
    }

    private fun build(title: String, text: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_group_marker)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        // Process going away with a recording still open: save what we have (the file stays "recording").
        if (state.value is RecordingState.Active) runBlocking { flush() }
        stopGps()
        stopSensors()
        scope.cancel()
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("recording", Context.MODE_PRIVATE)

    companion object {
        private const val ACTION_START = "com.lotanbar.tripexplorer.START"
        private const val ACTION_PAUSE = "com.lotanbar.tripexplorer.PAUSE"
        private const val ACTION_RESUME = "com.lotanbar.tripexplorer.RESUME"
        private const val ACTION_STOP = "com.lotanbar.tripexplorer.STOP"
        private const val ACTION_RESUME_INCOMPLETE = "com.lotanbar.tripexplorer.RESUME_INCOMPLETE"
        private const val EXTRA_TRIP = "trip"
        private const val EXTRA_FILE = "file"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 1
        private const val SAVE_EVERY_SEC = 10
        private const val KEY_ACTIVE_FILE = "active_file"

        private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
        val state: StateFlow<RecordingState> = _state

        private const val MIN_STEP_M = 5f
        private const val MAX_ACCURACY_FOR_DISTANCE = 30f

        private const val STILL_WINDOW_MS = 10 * 60_000L
        private const val STILL_MAX_STEPS = 20
        private const val STILL_RADIUS_M = 50f
        private const val WAKE_WINDOW_MS = 60_000L
        private const val WAKE_STEPS = 10

        /** The fused provider (GPS plus Wi-Fi and cell towers) when the phone has one, else plain GPS. */
        fun locationProvider(lm: LocationManager): String =
            if (lm.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER

        fun formatDistance(m: Double): String = if (m < 1000) "${m.toInt()} m" else String.format(java.util.Locale.US, "%.2f km", m / 1000)


        fun start(context: Context, trip: String) =
            ContextCompat.startForegroundService(context, intent(context, ACTION_START).putExtra(EXTRA_TRIP, trip))

        fun resumeIncomplete(context: Context, file: File) =
            ContextCompat.startForegroundService(context, intent(context, ACTION_RESUME_INCOMPLETE).putExtra(EXTRA_FILE, file.absolutePath))

        fun pause(context: Context) = context.startService(intent(context, ACTION_PAUSE))
        fun resume(context: Context) = context.startService(intent(context, ACTION_RESUME))
        fun stop(context: Context) = context.startService(intent(context, ACTION_STOP))

        private fun intent(context: Context, action: String) =
            Intent(context, RecordingService::class.java).setAction(action)

        fun formatElapsed(ms: Long): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }
    }
}
