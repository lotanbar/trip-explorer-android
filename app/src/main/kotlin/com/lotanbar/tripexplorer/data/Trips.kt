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

    /** Trip folder names, sorted case-insensitively. plans/ is not a trip. */
    fun list(): List<String> =
        root.listFiles()?.filter { it.isDirectory && !it.name.equals(Names.PLANS_FOLDER, ignoreCase = true) }?.map { it.name }
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER) ?: emptyList()

    /** Creates trips/ (if needed) and the trip folder. The name must already pass [Names.check]. */
    fun create(context: Context, name: String): File {
        val dir = tripDir(name)
        dir.mkdirs()
        scan(context, dir)
        return dir
    }

    /** GPX files of a trip, newest first. */
    fun recordings(trip: String): List<File> =
        recordingsDir(trip).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gpx", ignoreCase = true) }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** Broken (incomplete) recordings of every trip, newest first, except [active] (the one being recorded). */
    fun brokenRecordings(active: File?): List<File> =
        list().flatMap { recordings(it) }.filter { GpxWriter.isIncomplete(it) && it != active }

    /**
     * "Don't show again": closes a broken recording by renaming it with its last point's time (the start
     * time when it has no points), the end's date added when that is on a later day. Returns the new file, or null.
     */
    fun finishIncomplete(context: Context, file: File): File? {
        val endMs = GpxWriter.lastPointTimeMs(file) ?: GpxWriter.startMsFromName(file.name) ?: System.currentTimeMillis()
        val target = File(file.parentFile, GpxWriter.finishedFileName(file.name, endMs))
        if (!file.renameTo(target)) return null
        scan(context, target, file)
        return target
    }

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
}
