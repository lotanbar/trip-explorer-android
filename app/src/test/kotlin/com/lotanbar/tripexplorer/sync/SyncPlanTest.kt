package com.lotanbar.tripexplorer.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** The same decision table as the PC app's planner tests. */
class SyncPlanTest {
    private fun lf(md5: String, mtime: Long) = LocalEntry(false, md5.length.toLong(), mtime, md5)
    private fun ld() = LocalEntry(true, 0, 0, null)
    private fun rf(id: String, md5: String, modified: Long) = RemoteEntry(id, false, md5.length.toLong(), modified, md5)
    private fun rd(id: String) = RemoteEntry(id, true, 0, 0, null)
    private fun bf(id: String, md5: String) = BaseEntry(id, false, md5.length.toLong(), 1, 1, md5)
    private fun bd(id: String) = BaseEntry(id, true, 0, 0, 0, null)

    @Test
    fun newOnOneSideIsCopied() {
        val a = plan(mapOf("t" to ld(), "t/a" to lf("aa", 5)), mapOf("u" to rd("U"), "u/b" to rf("B", "bb", 5)), emptyMap())
        assertEquals(listOf(Action.MkdirRemote("t"), Action.Upload("t/a", null), Action.MkdirLocal("u"), Action.Download("u/b", "B")), a)
    }

    @Test
    fun changedOnOneSideWinsThatWay() {
        val base = mapOf("a" to bf("A", "old"), "b" to bf("B", "old"))
        val a = plan(mapOf("a" to lf("new", 9), "b" to lf("old", 1)), mapOf("a" to rf("A", "old", 1), "b" to rf("B", "new", 9)), base)
        assertEquals(listOf(Action.Upload("a", "A"), Action.Download("b", "B")), a)
    }

    @Test
    fun changedOnBothSidesNewestWins() {
        val base = mapOf("a" to bf("A", "old"), "b" to bf("B", "old"))
        val a = plan(mapOf("a" to lf("mine", 20), "b" to lf("mine", 10)), mapOf("a" to rf("A", "theirs", 10), "b" to rf("B", "theirs", 20)), base)
        assertEquals(listOf(Action.Upload("a", "A"), Action.Download("b", "B")), a)
    }

    @Test
    fun sameContentOnlyRecords() {
        assertEquals(listOf(Action.Record("a")), plan(mapOf("a" to lf("x", 3)), mapOf("a" to rf("A", "x", 7)), emptyMap()))
    }

    @Test
    fun removalsMirrorUnlessChangedSince() {
        val base = mapOf("a" to bf("A", "x"), "b" to bf("B", "x"), "c" to bf("C", "x"), "d" to bf("D", "x"))
        val a = plan(mapOf("a" to lf("x", 1), "b" to lf("changed", 5)), mapOf("c" to rf("C", "x", 1), "d" to rf("D", "changed", 5)), base)
        assertEquals(listOf(Action.Upload("b", null), Action.Download("d", "D"), Action.TrashRemote("c", "C"), Action.DeleteLocal("a")), a)
    }

    @Test
    fun localRenameIsAMoveOnDrive() {
        val base = mapOf("t" to bd("T"), "t/old" to bd("O"), "t/old/p.jpg" to bf("P", "jpg"))
        val local = mapOf("t" to ld(), "t/new" to ld(), "t/new/p.jpg" to lf("jpg", 1))
        val remote = mapOf("t" to rd("T"), "t/old" to rd("O"), "t/old/p.jpg" to rf("P", "jpg", 1))
        assertEquals(
            listOf(Action.MkdirRemote("t/new"), Action.MoveRemote("P", "t/old/p.jpg", "t/new/p.jpg"), Action.TrashRemote("t/old", "O")),
            plan(local, remote, base),
        )
    }

    @Test
    fun driveRenameIsAMoveHere() {
        val base = mapOf("r" to bd("R"), "r/x - recording.gpx" to bf("G", "gpx"))
        val local = mapOf("r" to ld(), "r/x - recording.gpx" to lf("gpx", 1))
        val remote = mapOf("r" to rd("R"), "r/x - y.gpx" to rf("G", "gpx", 1))
        assertEquals(listOf(Action.MoveLocal("G", "r/x - recording.gpx", "r/x - y.gpx")), plan(local, remote, base))
    }

    @Test
    fun folderRemovedOnDriveStaysIfSomethingNewIsInside() {
        val base = mapOf("t" to bd("T"), "t/a" to bf("A", "x"))
        val local = mapOf("t" to ld(), "t/a" to lf("x", 1), "t/new" to lf("n", 2))
        assertEquals(listOf(Action.MkdirRemote("t"), Action.Upload("t/new", null), Action.DeleteLocal("t/a")), plan(local, emptyMap(), base))
    }

    @Test
    fun remotePathsFollowParents() {
        val nodes = mapOf(
            "T" to RNode("trip", "ROOT", true, 0, 0, null),
            "A" to RNode("a.txt", "T", false, 1, 5, "x"),
            "Z" to RNode("elsewhere", "OTHER", false, 1, 5, null),
        )
        assertEquals(listOf("trip", "trip/a.txt"), remotePaths("ROOT", nodes).keys.toList())
    }

    @Test
    fun driveTimesRoundTrip() {
        val ms = 1790261944123L
        assertEquals(ms, DriveApi.parseTime(DriveApi.formatTime(ms)))
        assertEquals(1790261944000L, DriveApi.parseTime("2026-09-24T14:59:04Z"))
    }
}
