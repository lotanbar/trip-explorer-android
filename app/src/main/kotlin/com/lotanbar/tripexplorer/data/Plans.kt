package com.lotanbar.tripexplorer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Plans are made on the PC: `trips/plans/<name>.txt`, one stop per line in plan order,
 * `lat, lon, name`, with `, visited` at the end once the stop is ticked as visited. The phone only
 * changes that tick. Each stop is driven with Waze, one at a time.
 */
object Plans {
    data class Stop(val lat: Double, val lon: Double, val name: String, val visited: Boolean = false)

    val dir: File get() = File(Trips.root, Names.PLANS_FOLDER)

    /** Plan files, sorted by name case-insensitively. No plans/ folder → no plans. */
    fun list(): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".txt", ignoreCase = true) }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) ?: emptyList()

    fun nameOf(file: File): String = file.name.removeSuffix(".txt").removeSuffix(".TXT")

    fun read(file: File): List<Stop> = runCatching { parse(file.readText()) }.getOrDefault(emptyList())

    private val LINE = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*(?:,\s*(.*))?$""")
    private val VISITED = Regex("""\s*,\s*visited\s*$""", RegexOption.IGNORE_CASE)
    private const val VISITED_SUFFIX = ", visited"

    /** Lines that don't start with two numbers are skipped; a name may hold commas. */
    fun parse(text: String): List<Stop> = text.lineSequence().mapNotNull { stopOf(it) }.toList()

    private fun stopOf(raw: String): Stop? {
        val m = LINE.find(raw.trim()) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val rest = m.groupValues[3].trim()
        val name = rest.replace(VISITED, "").trim().ifEmpty { "$lat, $lon" }
        return Stop(lat, lon, name, VISITED.containsMatchIn(rest))
    }

    /**
     * Ticks the `index`-th stop (as [parse] counts them) visited or not. Only that line of the file
     * changes; everything else is kept as it is. Written to a temp file and renamed over the plan.
     */
    fun setVisited(file: File, index: Int, visited: Boolean): Boolean = runCatching {
        val lines = file.readText().split(Regex("(?<=\n)")).toMutableList()
        var n = -1
        for (i in lines.indices) {
            val body = lines[i].removeSuffix("\n").removeSuffix("\r")
            if (stopOf(body) == null || ++n != index) continue
            val bare = body.trimEnd().replace(VISITED, "")
            lines[i] = (if (visited) bare + VISITED_SUFFIX else bare) + lines[i].substring(body.length)
            break
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(lines.joinToString(""))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)

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
