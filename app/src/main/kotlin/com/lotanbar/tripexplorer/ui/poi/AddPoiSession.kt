package com.lotanbar.tripexplorer.ui.poi

import android.content.Context
import android.location.Location
import com.lotanbar.tripexplorer.data.PoiStore
import com.lotanbar.tripexplorer.ui.main.currentGpsLocation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.log10
import kotlin.math.pow

/**
 * One Add POI, from the tap to Save or Cancel. Kept outside the screen, so turning the phone does not
 * stop the note or the search for the position.
 *
 * The tap is the POI's moment: its time, and the start of the search for its position, which runs in the
 * background while the note is recorded (the first fix taken after the tap; see [currentGpsLocation]).
 */
object AddPoiSession {
    enum class Phase { Recording, Paused, Review, Camera, Category }

    data class State(
        val trip: String,
        /** The tap: the POI's time and folder name. */
        val atMs: Long,
        val phase: Phase,
        /** The current take, in the app's cache until Save; null when the last take recorded nothing. */
        val take: File?,
        /** Time recorded before the current stretch. */
        val recordedMs: Long,
        /** When the current stretch started; null unless recording. */
        val runningSinceMs: Long?,
        /** Photos taken so far (optional), in the app's cache until Save. */
        val photos: List<File> = emptyList(),
    ) {
        fun elapsedMs(nowMs: Long): Long = recordedMs + (runningSinceMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0)
    }

    /** How many level samples the soundwave keeps. */
    const val LEVELS = 56

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state
    private val _levels = MutableStateFlow<List<Float>>(emptyList())
    /** The microphone's recent levels (0..1), oldest first. */
    val levels: StateFlow<List<Float>> = _levels

    private var recorder: NoteRecorder? = null
    private var levelJob: Job? = null
    private var fixJob: Job? = null
    private var fix = CompletableDeferred<Location>()
    @Volatile private var location: Location? = null

    private fun noteDir(context: Context) = File(context.cacheDir, "notes")

    /** Starts a POI now in [trip]: the note starts recording and the position is looked for. Returns an error, or null. */
    fun begin(context: Context, trip: String): String? {
        if (_state.value != null) return null
        val app = context.applicationContext
        val atMs = System.currentTimeMillis()
        noteDir(app).listFiles()?.forEach { it.delete() } // takes left by an earlier, killed app
        location = null
        val f = CompletableDeferred<Location>().also { fix = it }
        fixJob = scope.launch {
            currentGpsLocation(app, timeoutMs = Long.MAX_VALUE)?.let { location = it; f.complete(it) }
        }
        val err = startTake(app, trip, atMs, photos = emptyList())
        if (err != null) end()
        return err
    }

    private fun startTake(app: Context, trip: String, atMs: Long, photos: List<File>): String? {
        val file = File(noteDir(app), "take_${System.currentTimeMillis()}.opus")
        val r = NoteRecorder(app)
        return runCatching { r.start(file) }.fold(
            onSuccess = {
                recorder = r
                _levels.value = emptyList()
                _state.value = State(trip, atMs, Phase.Recording, file, recordedMs = 0, runningSinceMs = System.currentTimeMillis(), photos = photos)
                startLevels()
                null
            },
            onFailure = { file.delete(); "Could not start recording: ${it.message}" },
        )
    }

    private fun startLevels() {
        levelJob?.cancel()
        levelJob = scope.launch {
            while (isActive) {
                val amp = recorder?.maxAmplitude() ?: 0
                // Decibels below full scale: -50 dB and quieter is a flat line; a curve keeps speech from filling the bars.
                val linear = if (amp <= 0) 0f else ((20 * log10(amp / 32767f) + 50f) / 50f).coerceIn(0f, 1f)
                val level = linear.pow(1.8f)
                _levels.value = (_levels.value + level).takeLast(LEVELS)
                delay(70L)
            }
        }
    }

    fun pause() {
        val st = _state.value ?: return
        if (st.phase != Phase.Recording) return
        runCatching { recorder?.pause() }
        levelJob?.cancel()
        _state.value = st.copy(phase = Phase.Paused, recordedMs = st.elapsedMs(System.currentTimeMillis()), runningSinceMs = null)
    }

    fun resume() {
        val st = _state.value ?: return
        if (st.phase != Phase.Paused) return
        runCatching { recorder?.resume() }
        _state.value = st.copy(phase = Phase.Recording, runningSinceMs = System.currentTimeMillis())
        startLevels()
    }

    /** Stop: the take is closed and can be listened to. */
    fun stop() {
        val st = _state.value ?: return
        if (st.phase != Phase.Recording && st.phase != Phase.Paused) return
        levelJob?.cancel()
        val file = recorder?.stop()
        recorder = null
        _state.value = st.copy(phase = Phase.Review, take = file, recordedMs = st.elapsedMs(System.currentTimeMillis()), runningSinceMs = null)
    }

    /** Throws this take away and starts a new one at once. The POI keeps its moment, its position and its photos. */
    fun recordAgain(context: Context): String? {
        val st = _state.value ?: return null
        levelJob?.cancel()
        recorder?.stop()
        recorder = null
        st.take?.delete()
        val err = startTake(context.applicationContext, st.trip, st.atMs, st.photos)
        if (err != null) _state.value = st.copy(phase = Phase.Review, take = null, recordedMs = 0, runningSinceMs = null)
        return err
    }

    fun next() {
        val st = _state.value ?: return
        if (st.phase == Phase.Review && st.take != null) _state.value = st.copy(phase = Phase.Category)
    }

    fun back() {
        val st = _state.value ?: return
        if (st.phase == Phase.Category || st.phase == Phase.Camera) _state.value = st.copy(phase = Phase.Review)
    }

    /** Add photo: the camera, from the review step. */
    fun openCamera() {
        val st = _state.value ?: return
        if (st.phase == Phase.Review) _state.value = st.copy(phase = Phase.Camera)
    }

    /** Where the camera writes the next photo (the app's cache, like the note). */
    fun newPhotoFile(context: Context): File =
        File(noteDir(context.applicationContext), "photo_${System.currentTimeMillis()}.jpg")

    /** Back from the camera with the photos it took. */
    fun closeCamera(taken: List<File>) {
        val st = _state.value ?: return
        if (st.phase == Phase.Camera) _state.value = st.copy(phase = Phase.Review, photos = st.photos + taken)
    }

    /** The position, if it is in already. */
    fun locationNow(): Location? = location

    /** Waits up to [timeoutMs] for the position. */
    suspend fun awaitLocation(timeoutMs: Long): Location? = location ?: withTimeoutOrNull(timeoutMs) { fix.await() }

    /** Writes the POI folder (see [PoiStore.create]) and ends the session. On failure nothing is lost: it throws and stays open. */
    fun save(context: Context, categoryKey: String, at: Location): File {
        val st = _state.value ?: throw IllegalStateException("No POI is being added.")
        val note = st.take ?: throw IllegalStateException("There is no note.")
        val dir = PoiStore.create(context, st.trip, st.atMs, at.latitude, at.longitude, categoryKey, note, st.photos)
        end()
        return dir
    }

    /** Cancel: the take and the photos are deleted (they are only in the app's cache) and nothing is written. */
    fun discard() {
        val st = _state.value
        levelJob?.cancel()
        recorder?.stop()
        recorder = null
        st?.take?.delete()
        st?.photos?.forEach { it.delete() }
        end()
    }

    private fun end() {
        fixJob?.cancel() // stops the location request if no fix came yet
        fixJob = null
        levelJob?.cancel()
        levelJob = null
        recorder = null
        location = null
        _levels.value = emptyList()
        _state.value = null
    }
}
