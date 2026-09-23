package com.lotanbar.tripexplorer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.util.Locale

/**
 * Plans are made on the PC and only read here: `trips/plans/<name>.txt`, one stop per line in
 * plan order, `lat, lon, name`. Each stop is driven with Waze, one at a time.
 */
object Plans {
    data class Stop(val lat: Double, val lon: Double, val name: String)

    val dir: File get() = File(Trips.root, Names.PLANS_FOLDER)

    /** Plan files, sorted by name case-insensitively. No plans/ folder → no plans. */
    fun list(): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".txt", ignoreCase = true) }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) ?: emptyList()

    fun nameOf(file: File): String = file.name.removeSuffix(".txt").removeSuffix(".TXT")

    fun read(file: File): List<Stop> = runCatching { parse(file.readText()) }.getOrDefault(emptyList())

    private val LINE = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*(?:,\s*(.*))?$""")

    /** Lines that don't start with two numbers are skipped; a name may hold commas. */
    fun parse(text: String): List<Stop> = text.lineSequence().mapNotNull { raw ->
        val m = LINE.find(raw.trim()) ?: return@mapNotNull null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
        val name = m.groupValues[3].trim().ifEmpty { "$lat, $lon" }
        Stop(lat, lon, name)
    }.toList()

    const val WAZE_PACKAGE = "com.waze"

    fun wazeUrl(stop: Stop): String = String.format(Locale.US, "https://waze.com/ul?ll=%.6f,%.6f&navigate=yes", stop.lat, stop.lon)

    fun isWazeInstalled(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(WAZE_PACKAGE) != null

    /** Opens Waze navigating to the stop. Returns false when Waze is not installed. */
    fun openInWaze(context: Context, stop: Stop): Boolean {
        if (!isWazeInstalled(context)) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(wazeUrl(stop))).setPackage(WAZE_PACKAGE)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    private const val PREFS = "trip_explorer"

    /** Index of the last stop opened in Waze for this plan file, or -1; the next one is highlighted. */
    fun lastOpened(context: Context, file: File): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("plan_last:${file.absolutePath}", -1)

    fun setLastOpened(context: Context, file: File, index: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("plan_last:${file.absolutePath}", index).apply()
    }
}
