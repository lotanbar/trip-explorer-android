package com.lotanbar.tripexplorer

import com.lotanbar.tripexplorer.data.GpxPoint
import com.lotanbar.tripexplorer.data.GpxWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class GpxWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun point(sec: Int) = GpxPoint(37.1 + sec * 1e-5, 25.3, Instant.parse("2026-09-26T07:10:00Z").toEpochMilli() + sec * 1000L, 6f)

    private fun closing(text: String) = text.endsWith("    </trkseg>\n  </trk>\n</gpx>\n")

    @Test
    fun fileIsAlwaysValid() {
        val file = File(tmp.root, "r.gpx")
        val w = GpxWriter.create(file)
        assertTrue(closing(file.readText()))
        w.append(listOf(point(0), point(1)))
        assertTrue(closing(file.readText()))
        assertEquals(2, GpxWriter.countPoints(file))
        w.append(listOf(point(2)))
        assertEquals(3, GpxWriter.countPoints(file))
        assertTrue(closing(file.readText()))
        val text = file.readText()
        assertTrue(text.contains("<trkpt lat=\"37.100010\" lon=\"25.300000\"><time>2026-09-26T07:10:01Z</time><extensions><accuracy>6</accuracy></extensions></trkpt>"))
        assertEquals(1, Regex("<trkseg>").findAll(text).count())
    }

    @Test
    fun pauseStartsNewSegment() {
        val file = File(tmp.root, "r.gpx")
        val w = GpxWriter.create(file)
        w.append(listOf(point(0)))
        w.breakSegment()
        w.append(listOf(point(5)))
        val text = file.readText()
        assertEquals(2, Regex("<trkseg>").findAll(text).count())
        assertEquals(2, Regex("</trkseg>").findAll(text).count())
        assertTrue(closing(text))
    }

    @Test
    fun openResumesAndCutsHalfWrittenPoint() {
        val file = File(tmp.root, "r.gpx")
        val w = GpxWriter.create(file)
        w.append(listOf(point(0), point(1)))
        // Simulate a crash mid-save: a half-written point, no closing tags.
        val text = file.readText()
        val cut = text.lastIndexOf("    </trkseg>")
        file.writeText(text.substring(0, cut) + "      <trkpt lat=\"37.1")
        assertEquals(2, GpxWriter.countPoints(file))
        assertEquals(point(1).timeMs, GpxWriter.lastPointTimeMs(file))

        val w2 = GpxWriter.open(file)
        assertTrue(closing(file.readText()))
        assertEquals(2, GpxWriter.countPoints(file))
        w2.append(listOf(point(9)))
        val after = file.readText()
        assertEquals(3, GpxWriter.countPoints(file))
        assertEquals(2, Regex("<trkseg>").findAll(after).count()) // resumed as a new segment
        assertTrue(closing(after))
    }

    @Test
    fun emptyFileHasNoLastPoint() {
        val file = File(tmp.root, "r.gpx")
        GpxWriter.create(file)
        assertNull(GpxWriter.lastPointTimeMs(file))
        assertEquals(0, GpxWriter.countPoints(file))
    }

    @Test
    fun finishedNameAddsDateOnlyAcrossMidnight() {
        val sameDay = "2026-09-14 08-10-05 - recording.gpx"
        assertEquals(sameDay.removeSuffix(" - recording.gpx").substring(0, 10), "2026-09-14")
        // The end time is formatted in the local zone, so build the instant from a local time.
        val zone = java.time.ZoneId.systemDefault()
        val end1 = java.time.LocalDateTime.of(2026, 9, 14, 17, 45, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals("2026-09-14 08-10-05 - 17-45-30.gpx", GpxWriter.finishedFileName(sameDay, end1))
        val end2 = java.time.LocalDateTime.of(2026, 9, 15, 1, 30, 48).atZone(zone).toInstant().toEpochMilli()
        assertEquals("2026-09-14 08-10-05 - 2026-09-15 01-30-48.gpx", GpxWriter.finishedFileName(sameDay, end2))
    }
}
