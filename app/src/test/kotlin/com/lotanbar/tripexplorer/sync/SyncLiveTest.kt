package com.lotanbar.tripexplorer.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Runs the engine against real Google Drive, on the PC (JVM), with the PC app's saved sign-in.
 * Only with LIVE=1: `LIVE=1 ./gradlew testDebugUnitTest --tests '*SyncLiveTest*'`.
 * Works in a fresh subfolder of "Trip Explorer test" and a temp copy of Desktop/trips-sample.
 */
class SyncLiveTest {
    private val events = mutableListOf<SyncStatus>()

    private fun settle(e: SyncEngine, what: String, maxMs: Long = 90_000) {
        val t0 = System.currentTimeMillis()
        while (true) {
            Thread.sleep(300)
            val s = e.status
            s.error?.let { throw AssertionError("$what: sync error $it") }
            if (!s.busy && (s.lastSync ?: 0) > t0) {
                println("[$what] settled in ${(System.currentTimeMillis() - t0) / 1000.0}s (${s.total} actions)")
                return
            }
            if (System.currentTimeMillis() - t0 > maxMs) throw AssertionError("$what: did not settle")
        }
    }

    private fun remoteTree(d: DriveApi, folder: String): Map<String, RemoteEntry> {
        val nodes = HashMap<String, RNode>()
        SyncEngine.listTree(d, folder, nodes)
        return remotePaths(folder, nodes)
    }

    private fun assertSame(d: DriveApi, folder: String, root: File, what: String) {
        val r = remoteTree(d, folder).mapValues { if (it.value.dir) null else it.value.md5 }
        val l = SyncEngine.scanLocal(root).mapValues { (p, e) -> if (e.dir) null else fileMd5(SyncEngine.localFile(root, p)) }
        assertEquals("$what: local and Drive differ", l.toSortedMap(), r.toSortedMap())
        println("[$what] local == Drive (${l.size} items)")
    }

    @Test
    fun liveRoundTrip() {
        assumeTrue(System.getenv("LIVE") == "1")
        val oauth = JSONObject(File("../google_oauth.json").readText())
        val (id, secret) = oauth.getString("client_id") to oauth.getString("client_secret")
        val pcAuth = File(System.getenv("APPDATA"), "com.lotanbar.tripexplorer/drive_auth.json")
        val d = DriveApi(id, secret, JSONObject(pcAuth.readText()).getString("refresh_token"))

        val top = d.children("root", true).firstOrNull { it.name == "Trip Explorer test" } ?: d.createFolder("root", "Trip Explorer test")
        val run = "android run " + SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(Date())
        val folder = d.createFolder(top.id, run).id

        val tmp = File(System.getProperty("java.io.tmpdir"), "te-android-sync-${System.nanoTime()}")
        val root = File(tmp, "trips")
        File("C:/Users/Lotan/Desktop/trips-sample").copyRecursively(root)
        val data = File(tmp, "data").apply { mkdirs() }
        pcAuth.copyTo(File(data, "drive_auth.json"))

        val engine = SyncEngine(data, id, secret, object : SyncEngine.Listener {
            override fun onStatus(status: SyncStatus) { synchronized(events) { events.add(status) } }
            override fun onLocalChanged(files: List<File>) {}
        })
        engine.root = root
        engine.pickFolder(folder, run)
        engine.start()
        try {
            settle(engine, "initial upload", 120_000)
            assertSame(d, folder, root, "initial upload")
            assertTrue("progress was reported", events.any { it.busy && it.total > 0 && it.done in 1 until it.total })
            val before = remoteTree(d, folder)

            // A growing recording uploads on every save, as the same file.
            val gpx = File(root, "Greece 2026/recordings/2026-09-27 16-02-40 - recording.gpx")
            gpx.appendText("<!-- more points -->\n")
            engine.poke()
            settle(engine, "recording save")
            assertSame(d, folder, root, "recording save")
            assertEquals(before["Greece 2026/recordings/2026-09-27 16-02-40 - recording.gpx"]!!.id, remoteTree(d, folder)["Greece 2026/recordings/2026-09-27 16-02-40 - recording.gpx"]!!.id)

            // Stop renames the recording: a move on Drive, not a new upload.
            val stopped = File(gpx.parentFile, "2026-09-27 16-02-40 - 17-00-00.gpx")
            assertTrue(gpx.renameTo(stopped))
            // A new POI with a photo.
            File(root, "Greece 2026/New spring/media").mkdirs()
            File(root, "Greece 2026/New spring/coordinates.txt").writeText("37.1, 25.4")
            File(root, "Greece 2026/New spring/media/1.jpg").writeBytes(ByteArray(300_000) { (it % 251).toByte() })
            engine.poke()
            settle(engine, "stop + new POI")
            assertSame(d, folder, root, "stop + new POI")
            val after = remoteTree(d, folder)
            assertEquals(before["Greece 2026/recordings/2026-09-27 16-02-40 - recording.gpx"]!!.id, after["Greece 2026/recordings/2026-09-27 16-02-40 - 17-00-00.gpx"]!!.id)

            // The PC ticks a plan stop (edit on Drive), renames a POI on Drive and trashes a file.
            val edited = File(tmp, "naxos.txt").apply { writeText("37.1, 25.4, Portara, visited\n") }
            d.upload(after["plans/Naxos.txt"]!!.id, "", "Naxos.txt", edited, System.currentTimeMillis())
            d.moveTo(after["Greece 2026/Kastro cave"]!!.id, "Kastro cave (closed)", after["Greece 2026"]!!.id, after["Greece 2026"]!!.id)
            d.trash(after["plans/Naxos Imported.txt"]!!.id)
            Thread.sleep(32_000)
            settle(engine, "Drive changes")
            assertSame(d, folder, root, "Drive changes")
            assertEquals("37.1, 25.4, Portara, visited\n", File(root, "plans/Naxos.txt").readText())
            assertTrue(File(root, "Greece 2026/Kastro cave (closed)/coordinates.txt").exists())
            assertFalse(File(root, "Greece 2026/Kastro cave").exists())
            assertFalse(File(root, "plans/Naxos Imported.txt").exists())

            // Sync off: edits wait; on again, they go up (the phone never loses offline edits).
            engine.stop()
            File(root, "Greece 2026/Portara/description.txt").writeText("Written with Sync off")
            Thread.sleep(6_000)
            assertEquals(before["Greece 2026/Portara/description.txt"]!!.md5, remoteTree(d, folder)["Greece 2026/Portara/description.txt"]!!.md5)
            engine.start()
            engine.poke()
            settle(engine, "sync back on")
            assertSame(d, folder, root, "sync back on")
        } finally {
            engine.stop()
            d.trash(folder)
            tmp.deleteRecursively()
        }
        println("OK: all steps passed; Drive run folder trashed")
    }
}
