package com.lotanbar.tripexplorer.data

import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** One raw fix: position, time (epoch ms, UTC) and the location service's accuracy in meters. */
data class GpxPoint(val lat: Double, val lon: Double, val timeMs: Long, val accuracyM: Float)

/**
 * Writes the recording format: GPX 1.1, one <trk>, one <trkseg> per un-paused stretch.
 *
 * The file is always valid: every save writes the new points followed by the closing tags, and the
 * next save overwrites those closing tags with more points. A crash can at most leave a half-written
 * last point, which the PC app ignores and [open] cuts off when the recording is resumed.
 */
class GpxWriter private constructor(val file: File, private var tailPos: Long, private var needSegmentBreak: Boolean) {

    /** Appends [points] (and a segment break first, if a pause happened) and re-closes the file. */
    @Synchronized
    fun append(points: List<GpxPoint>) {
        if (points.isEmpty()) return
        val sb = StringBuilder()
        if (needSegmentBreak) {
            sb.append(SEGMENT_BREAK)
            needSegmentBreak = false
        }
        for (p in points) sb.append(formatPoint(p))
        val body = sb.toString().toByteArray(Charsets.US_ASCII)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(tailPos)
            raf.write(body)
            raf.write(CLOSING_BYTES)
            raf.setLength(raf.filePointer)
            raf.fd.sync()
        }
        tailPos += body.size
    }

    /** Pause: the next points go into a new <trkseg>. */
    @Synchronized
    fun breakSegment() {
        needSegmentBreak = true
    }

    companion object {
        private const val HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<gpx version=\"1.1\" creator=\"Trip Explorer\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n" +
            "  <trk>\n" +
            "    <trkseg>\n"
        private const val CLOSING = "    </trkseg>\n  </trk>\n</gpx>\n"
        private const val SEGMENT_BREAK = "    </trkseg>\n    <trkseg>\n"
        private val CLOSING_BYTES = CLOSING.toByteArray(Charsets.US_ASCII)

        const val RECORDING_SUFFIX = " - recording.gpx"
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss", Locale.US)
        private val FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
        private val FILE_CLOCK = DateTimeFormatter.ofPattern("HH-mm-ss", Locale.US)

        private fun formatPoint(p: GpxPoint): String {
            val time = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(p.timeMs / 1000 * 1000))
            return String.format(
                Locale.US,
                "      <trkpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time><extensions><accuracy>%d</accuracy></extensions></trkpt>\n",
                p.lat, p.lon, time, p.accuracyM.roundToInt(),
            )
        }

        /** The name of a new recording started at [startMs] (local time). */
        fun recordingFileName(startMs: Long): String =
            FILE_TIME.format(local(startMs)) + RECORDING_SUFFIX

        /**
         * The finished name for a file that started as [recordingName] and ended at [endMs]:
         * "<date> <start> - <end>.gpx", with the end's date added when it is on a later day.
         */
        fun finishedFileName(recordingName: String, endMs: Long): String {
            val start = recordingName.removeSuffix(RECORDING_SUFFIX)
            val startDate = start.substringBefore(' ')
            val end = local(endMs)
            val endDate = FILE_DATE.format(end)
            val endPart = if (endDate == startDate) FILE_CLOCK.format(end) else "$endDate ${FILE_CLOCK.format(end)}"
            return "$start - $endPart.gpx"
        }

        fun isIncomplete(file: File): Boolean = file.name.endsWith(RECORDING_SUFFIX, ignoreCase = true)

        /** The start of a recording from its file name, or null when the name isn't ours. */
        fun startMsFromName(name: String): Long? = runCatching {
            LocalDateTime.parse(name.substring(0, 19), FILE_TIME).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()

        private fun local(ms: Long): LocalDateTime =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

        /** Creates a new, empty but valid recording file. */
        fun create(file: File): GpxWriter {
            file.parentFile?.mkdirs()
            val header = HEADER.toByteArray(Charsets.US_ASCII)
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                raf.write(header)
                raf.write(CLOSING_BYTES)
                raf.fd.sync()
            }
            return GpxWriter(file, header.size.toLong(), needSegmentBreak = false)
        }

        /**
         * Opens an unfinished recording to continue it as a new segment. Anything after the last
         * complete point (the closing tags, or a half-written point) is replaced by fresh closing tags.
         */
        fun open(file: File): GpxWriter {
            val bytes = file.readBytes()
            val text = String(bytes, Charsets.ISO_8859_1)
            val lastPoint = text.lastIndexOf("</trkpt>")
            var tail: Int
            var hasPoints = true
            if (lastPoint >= 0) {
                tail = lastPoint + "</trkpt>".length
                if (tail < text.length && text[tail] == '\n') tail++
            } else {
                val seg = text.indexOf("<trkseg>")
                hasPoints = false
                tail = if (seg >= 0) {
                    var t = seg + "<trkseg>".length
                    if (t < text.length && text[t] == '\n') t++
                    t
                } else {
                    // Not a file we wrote; start over with a clean header, keeping nothing.
                    return create(file)
                }
            }
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(tail.toLong())
                if (tail < text.length && !text.startsWith("\n", tail) && !text.regionMatches(tail, "    </trkseg>", 0, 13)) {
                    // Half-written last point: make sure it ends on its own line before the closing tags.
                    raf.write("\n".toByteArray(Charsets.US_ASCII))
                    tail++
                }
                raf.write(CLOSING_BYTES)
                raf.setLength(raf.filePointer)
                raf.fd.sync()
            }
            return GpxWriter(file, tail.toLong(), needSegmentBreak = hasPoints)
        }

        /** Time (epoch ms) of the last complete point in [file], or null when it has none. */
        fun lastPointTimeMs(file: File): Long? {
            val text = runCatching { String(file.readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return null
            val lastPoint = text.lastIndexOf("</trkpt>")
            if (lastPoint < 0) return null
            val timeStart = text.lastIndexOf("<time>", lastPoint)
            if (timeStart < 0) return null
            val timeEnd = text.indexOf("</time>", timeStart)
            if (timeEnd < 0 || timeEnd > lastPoint) return null
            val iso = text.substring(timeStart + "<time>".length, timeEnd).trim()
            return runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
        }

        /**
         * Time recorded in [file] (ms): per segment, from its first point to its last. Pauses and the
         * gap before a resume (each starts a new segment) are not counted.
         */
        fun recordedMs(file: File): Long {
            val text = runCatching { String(file.readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return 0
            var total = 0L
            for (seg in text.split("<trkseg>").drop(1)) {
                val times = Regex("<time>([^<]+)</time>(?=(?:(?!<trkpt).)*?</trkpt>)").findAll(seg)
                    .mapNotNull { runCatching { Instant.parse(it.groupValues[1].trim()).toEpochMilli() }.getOrNull() }.toList()
                if (times.size >= 2) total += (times.last() - times.first()).coerceAtLeast(0)
            }
            return total
        }

        /** The last complete point in [file], or null when it has none. */
        fun lastPoint(file: File): GpxPoint? {
            val text = runCatching { String(file.readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return null
            val end = text.lastIndexOf("</trkpt>")
            if (end < 0) return null
            val start = text.lastIndexOf("<trkpt ", end)
            if (start < 0) return null
            val m = Regex("""lat="([-\d.]+)" lon="([-\d.]+)"""").find(text.substring(start, end)) ?: return null
            return GpxPoint(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), lastPointTimeMs(file) ?: 0L, Float.NaN)
        }

        /** Number of complete points in [file]. */
        fun countPoints(file: File): Int {
            val text = runCatching { String(file.readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return 0
            var n = 0
            var i = text.indexOf("</trkpt>")
            while (i >= 0) { n++; i = text.indexOf("</trkpt>", i + 8) }
            return n
        }

        /** Distance along [file]'s points (m), segment by segment, with the recorder's rule: steps under 5 m (or
         * under the point's accuracy) are jitter; points less accurate than 30 m are skipped. */
        fun distanceMeters(file: File): Double {
            val text = runCatching { String(file.readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return 0.0
            var total = 0.0
            for (seg in text.split("<trkseg>").drop(1)) {
                var anchor: DoubleArray? = null
                for (m in Regex("""<trkpt lat="([-\d.]+)" lon="([-\d.]+)">(?:(?!</trkpt>).)*?(?:<accuracy>(\d+)</accuracy>)?(?:(?!</trkpt>).)*</trkpt>""").findAll(seg)) {
                    val lat = m.groupValues[1].toDouble()
                    val lon = m.groupValues[2].toDouble()
                    val acc = m.groupValues[3].toDoubleOrNull() ?: 0.0
                    if (acc > 30) continue
                    val a = anchor
                    if (a == null) { anchor = doubleArrayOf(lat, lon); continue }
                    val d = FloatArray(1).also { android.location.Location.distanceBetween(a[0], a[1], lat, lon, it) }[0].toDouble()
                    if (d >= maxOf(5.0, acc)) { total += d; anchor = doubleArrayOf(lat, lon) }
                }
            }
            return total
        }
    }
}
