package com.lotanbar.tripexplorer.data

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File

/** The trips/ root in the phone's storage root and the trip folders inside it. */
object Trips {
    val root: File get() = File(Environment.getExternalStorageDirectory(), "trips")

    fun tripDir(trip: String): File = File(root, trip)
    fun recordingsDir(trip: String): File = File(tripDir(trip), Names.RECORDINGS_FOLDER)

    /** Trip folder names, sorted case-insensitively. */
    fun list(): List<String> =
        root.listFiles()?.filter { it.isDirectory }?.map { it.name }
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER) ?: emptyList()

    /** Creates trips/ (if needed) and the trip folder. The name must already pass [Names.check]. */
    fun create(context: Context, name: String): File {
        val dir = tripDir(name)
        dir.mkdirs()
        scan(context, dir)
        return dir
    }

    /** POI folders of a trip: every subfolder except recordings/, sorted case-insensitively. */
    fun poiDirs(trip: String): List<File> =
        tripDir(trip).listFiles()
            ?.filter { it.isDirectory && !it.name.equals(Names.RECORDINGS_FOLDER, ignoreCase = true) }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) ?: emptyList()

    /** GPX files of a trip, newest first. */
    fun recordings(trip: String): List<File> =
        recordingsDir(trip).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gpx", ignoreCase = true) }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** Tells Android's media scanner about written or renamed files, so USB (MTP) shows them. */
    fun scan(context: Context, vararg files: File) {
        if (files.isEmpty()) return
        val paths = files.map { it.absolutePath }.toTypedArray()
        runCatching { MediaScannerConnection.scanFile(context.applicationContext, paths, null, null) }
    }

    /** Scans a folder and everything inside it (after a rename, the old path is scanned too). */
    fun scanTree(context: Context, dir: File, vararg alsoScan: File) {
        val all = ArrayList<File>()
        all.addAll(alsoScan)
        dir.walkTopDown().forEach { all.add(it) }
        scan(context, *all.toTypedArray())
    }

    private const val PREFS = "trip_explorer"
    private const val KEY_CURRENT_TRIP = "current_trip"
    private const val KEY_DISMISSED = "dismissed_incomplete"

    fun currentTrip(context: Context): String? {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CURRENT_TRIP, null)
        val trips = list()
        return when {
            saved != null && trips.any { it == saved } -> saved
            else -> trips.firstOrNull()
        }
    }

    fun setCurrentTrip(context: Context, trip: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CURRENT_TRIP, trip).apply()
    }

    /** Incomplete recordings the user chose "Later" for: never asked about again on launch. */
    fun dismissedIncomplete(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_DISMISSED, emptySet()) ?: emptySet()

    fun dismissIncomplete(context: Context, file: File) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = HashSet(prefs.getStringSet(KEY_DISMISSED, emptySet()) ?: emptySet())
        set.add(file.absolutePath)
        prefs.edit().putStringSet(KEY_DISMISSED, set).apply()
    }
}
