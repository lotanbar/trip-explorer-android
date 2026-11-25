package com.lotanbar.tripexplorer.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A POI as read from its folder, for the POI list and the view-only POI screen. */
data class PoiEntry(
    val dir: File,
    /** The tap's local time, from the folder name (null if the folder isn't named DD.MM.YYYY HH-MM-SS). */
    val time: LocalDateTime?,
    val lat: Double?,
    val lon: Double?,
    /** A category key; a missing or unknown one reads as none. */
    val category: String,
    val note: File?,
    val photos: List<File>,
)

/**
 * Writes a POI in the spec's format: a folder named by the local date and time Add POI was tapped
 * (DD.MM.YYYY HH-MM-SS), holding note.opus, optional photos (IMG_001.jpg…) and data.txt. Reading them
 * back ([list], [read]) is for viewing only; the phone never changes a POI.
 */
object PoiStore {
    const val NOTE = "note.opus"
    const val DATA = "data.txt"
    private val FOLDER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH-mm-ss", Locale.US)
    private val DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm", Locale.US)

    private fun local(ms: Long, zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)

    /** The POI's folder name: the tap's local time, to the second. */
    fun folderName(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = FOLDER.format(local(atMs, zone))

    /** The [n]th photo's name (1-based): IMG_001.jpg, IMG_002.jpg… */
    fun photoName(n: Int): String = String.format(Locale.US, "IMG_%03d.jpg", n)

    /** data.txt: one key: value per line. */
    fun dataText(lat: Double, lon: Double, atMs: Long, categoryKey: String, zone: ZoneId = ZoneId.systemDefault()): String =
        String.format(Locale.US, "coordinates: %.5f, %.5f\n", lat, lon) +
            "datetime: ${DATETIME.format(local(atMs, zone))}\n" +
            "category: $categoryKey\n"

    /**
     * Creates <trip>/<folder>/ with [note] and [photos] moved in first and data.txt written last (as data.txt.tmp,
     * then renamed), so a folder with data.txt is always complete. A folder left without data.txt by a
     * failed earlier Save is reused. Returns the POI folder.
     */
    fun create(context: Context, trip: String, atMs: Long, lat: Double, lon: Double, categoryKey: String, note: File, photos: List<File> = emptyList()): File {
        val dir = File(Trips.tripDir(trip), folderName(atMs))
        if (File(dir, DATA).exists()) throw IOException("A POI named ${dir.name} already exists.")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create ${dir.absolutePath}")
        moveIn(note, File(dir, NOTE), "The note is missing.")
        photos.forEachIndexed { i, photo -> moveIn(photo, File(dir, photoName(i + 1)), "Photo ${i + 1} is missing.") }
        val tmp = File(dir, "$DATA.tmp")
        tmp.writeText(dataText(lat, lon, atMs, categoryKey))
        if (!tmp.renameTo(File(dir, DATA))) throw IOException("Could not write $DATA")
        Trips.scanTree(context, dir)
        return dir
    }

    /** data.txt's keys and values (one key: value per line; unknown keys are kept, the caller ignores them). */
    fun parseData(text: String): Map<String, String> =
        text.lineSequence().mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()

    /** "45.46420, 9.19000" → (45.4642, 9.19); null if it doesn't parse. */
    fun parseCoordinates(value: String?): Pair<Double, Double>? {
        val parts = value?.split(',')?.map { it.trim().toDoubleOrNull() } ?: return null
        if (parts.size != 2) return null
        val lat = parts[0] ?: return null
        val lon = parts[1] ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }

    fun parseFolderTime(name: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(name, FOLDER) }.getOrNull()

    /** One POI folder; null if it has no data.txt (not a POI, or a Save still in progress). */
    fun read(dir: File): PoiEntry? {
        val data = File(dir, DATA)
        if (!data.isFile) return null
        val map = runCatching { parseData(data.readText()) }.getOrDefault(emptyMap())
        val coords = parseCoordinates(map["coordinates"])
        val key = map["category"]?.takeIf { Categories.byKey(it) != null } ?: Categories.NONE
        val photos = dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg") }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        return PoiEntry(
            dir = dir,
            time = parseFolderTime(dir.name),
            lat = coords?.first,
            lon = coords?.second,
            category = key,
            note = File(dir, NOTE).takeIf { it.isFile },
            photos = photos,
        )
    }

    /** The trip's POIs, newest first. */
    fun list(trip: String): List<PoiEntry> =
        Trips.tripDir(trip).listFiles()
            ?.filter { it.isDirectory && !it.name.equals(Names.RECORDINGS_FOLDER, ignoreCase = true) }
            ?.mapNotNull(::read)
            ?.sortedWith(compareByDescending<PoiEntry> { it.time }.thenByDescending { it.dir.name })
            ?: emptyList()

    /** Moves a file from the app's cache into the POI folder (copy + delete across storage). A retry after a
     * failed Save finds it already there. */
    private fun moveIn(source: File, target: File, missing: String) {
        if (source.exists()) {
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete() // the copy in the app cache, not user data
            }
        } else if (!target.exists()) {
            throw IOException(missing)
        }
    }
}
