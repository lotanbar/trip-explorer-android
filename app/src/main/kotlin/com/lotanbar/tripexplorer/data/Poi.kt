package com.lotanbar.tripexplorer.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes a POI in the spec's format: a folder named by the local date and time Add POI was tapped
 * (DD.MM.YYYY HH-MM-SS), holding note.opus and data.txt. The phone only writes POIs; it never reads them.
 */
object PoiStore {
    const val NOTE = "note.opus"
    const val DATA = "data.txt"
    private val FOLDER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH-mm-ss", Locale.US)
    private val DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm", Locale.US)

    private fun local(ms: Long, zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)

    /** The POI's folder name: the tap's local time, to the second. */
    fun folderName(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = FOLDER.format(local(atMs, zone))

    /** data.txt: one key: value per line. */
    fun dataText(lat: Double, lon: Double, atMs: Long, categoryKey: String, zone: ZoneId = ZoneId.systemDefault()): String =
        String.format(Locale.US, "coordinates: %.5f, %.5f\n", lat, lon) +
            "datetime: ${DATETIME.format(local(atMs, zone))}\n" +
            "category: $categoryKey\n"

    /**
     * Creates <trip>/<folder>/ with [note] moved in first and data.txt written last (as data.txt.tmp,
     * then renamed), so a folder with data.txt is always complete. A folder left without data.txt by a
     * failed earlier Save is reused. Returns the POI folder.
     */
    fun create(context: Context, trip: String, atMs: Long, lat: Double, lon: Double, categoryKey: String, note: File): File {
        val dir = File(Trips.tripDir(trip), folderName(atMs))
        if (File(dir, DATA).exists()) throw IOException("A POI named ${dir.name} already exists.")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create ${dir.absolutePath}")
        val target = File(dir, NOTE)
        if (note.exists()) {
            if (!note.renameTo(target)) {
                note.copyTo(target, overwrite = true)
                note.delete() // the take in the app cache, not user data
            }
        } else if (!target.exists()) {
            throw IOException("The note is missing.")
        }
        val tmp = File(dir, "$DATA.tmp")
        tmp.writeText(dataText(lat, lon, atMs, categoryKey))
        if (!tmp.renameTo(File(dir, DATA))) throw IOException("Could not write $DATA")
        Trips.scanTree(context, dir)
        return dir
    }
}
