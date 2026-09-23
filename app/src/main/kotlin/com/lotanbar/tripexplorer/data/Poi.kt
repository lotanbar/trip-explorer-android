package com.lotanbar.tripexplorer.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A POI as read from its folder. The name is the folder name. */
data class Poi(
    val dir: File,
    val trip: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val datetime: String,
    val description: String,
    val groupKey: String?,
    val media: List<File>,
)

object PoiStore {
    private const val MEDIA = "media"
    private val DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm", Locale.US)

    fun mediaDir(dir: File): File = File(dir, MEDIA)

    /** Photos and audio notes, in name order (IMG_001, IMG_002, note_001…). */
    fun listMedia(dir: File): List<File> =
        mediaDir(dir).listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase(Locale.US) } ?: emptyList()

    fun isAudio(file: File): Boolean = file.extension.lowercase(Locale.US) in setOf("m4a", "aac", "mp3", "ogg", "wav", "opus")

    fun read(dir: File): Poi? {
        if (!dir.isDirectory) return null
        val coords = readText(File(dir, "coordinates.txt")).split(",")
        if (coords.size < 2) return null
        val lat = coords[0].trim().toDoubleOrNull() ?: return null
        val lon = coords[1].trim().toDoubleOrNull() ?: return null
        val groupKey = dir.listFiles()
            ?.firstOrNull { it.isFile && it.name.startsWith("group-") && it.name.endsWith(".txt") }
            ?.name?.removePrefix("group-")?.removeSuffix(".txt")
        return Poi(
            dir = dir,
            trip = dir.parentFile?.name ?: "",
            name = dir.name,
            lat = lat,
            lon = lon,
            datetime = readText(File(dir, "datetime.txt")),
            description = readText(File(dir, "description.txt")),
            groupKey = groupKey,
            media = listMedia(dir),
        )
    }

    /** All POIs of all trips, following the folders. */
    fun readAll(): List<Poi> =
        Trips.list().flatMap { trip -> Trips.poiDirs(trip).mapNotNull { read(it) } }

    /** Creates the POI folder in [trip]. [photos] and [notes] are temp files that are moved into media/. */
    fun create(
        context: Context,
        trip: String,
        name: String,
        lat: Double,
        lon: Double,
        atMs: Long,
        description: String,
        groupKey: String?,
        photos: List<File>,
        notes: List<File>,
    ): File {
        val dir = File(Trips.tripDir(trip), name)
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Could not create ${dir.absolutePath}")
        File(dir, "coordinates.txt").writeText(String.format(Locale.US, "%.6f, %.6f", lat, lon))
        File(dir, "datetime.txt").writeText(DATETIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(atMs), ZoneId.systemDefault())))
        File(dir, "description.txt").writeText(description)
        Groups.byKey(groupKey)?.let { File(dir, it.fileName).writeText("") }
        for (p in photos) addMedia(dir, p, MediaKind.PHOTO)
        for (n in notes) addMedia(dir, n, MediaKind.NOTE)
        Trips.scanTree(context, dir)
        return dir
    }

    enum class MediaKind(val prefix: String, val ext: String) { PHOTO("IMG_", "jpg"), NOTE("note_", "m4a") }

    /** The next free media file: the number after the highest existing one; gaps are never filled. */
    fun nextMediaFile(dir: File, kind: MediaKind): File {
        val media = mediaDir(dir)
        media.mkdirs()
        val highest = media.listFiles()
            ?.filter { it.name.startsWith(kind.prefix, ignoreCase = true) }
            ?.mapNotNull { it.nameWithoutExtension.substring(kind.prefix.length).toIntOrNull() }
            ?.maxOrNull() ?: 0
        return File(media, String.format(Locale.US, "%s%03d.%s", kind.prefix, highest + 1, kind.ext))
    }

    /** Moves a captured temp file into media/ under the next number. Returns the new file. */
    fun addMedia(dir: File, source: File, kind: MediaKind): File {
        val target = nextMediaFile(dir, kind)
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = false)
            source.delete() // the temp copy in the app cache, not user data
        }
        return target
    }

    /**
     * Edits a POI: renames the folder (name rules already checked), rewrites description.txt and
     * renames the group file (group-water.txt → group-caves.txt). Nothing is deleted: clearing the
     * group renames the file to a hidden ".group-<old>.txt", which both apps read as "No group".
     */
    fun update(context: Context, poi: Poi, newName: String, description: String, groupKey: String?): File {
        var dir = poi.dir
        val oldDir = dir
        if (newName != poi.name) {
            val target = File(dir.parentFile, newName)
            if (!dir.renameTo(target)) throw IOException("Could not rename to $newName")
            dir = target
        }
        File(dir, "description.txt").writeText(description)
        val oldGroupFile = dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("group-") && it.name.endsWith(".txt") }
        val newGroup = Groups.byKey(groupKey)
        when {
            oldGroupFile == null && newGroup != null -> File(dir, newGroup.fileName).writeText("")
            oldGroupFile != null && newGroup != null && oldGroupFile.name != newGroup.fileName ->
                oldGroupFile.renameTo(File(dir, newGroup.fileName))
            oldGroupFile != null && newGroup == null ->
                oldGroupFile.renameTo(File(dir, ".${oldGroupFile.name}"))
        }
        if (dir != oldDir) Trips.scanTree(context, dir, oldDir) else Trips.scan(context, dir)
        return dir
    }

    private fun readText(file: File): String = runCatching { file.readText().trim() }.getOrDefault("")
}
