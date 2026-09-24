package com.lotanbar.tripexplorer.sync

/*
 * What to do with every path, from three views of the trips tree: the phone's folder, the Drive folder,
 * and the base (both as they were after the last sync). A side "changed" a file when its content (MD5)
 * differs from the base. One side changed → copied to the other; both changed → the newer modified time
 * wins (ties: Drive). A path gone from one side is removed from the other (on Drive: to the trash),
 * unless the other side changed it since. A removal plus an addition of the same content is a rename,
 * done as a move: nothing is sent again. The same rules as the PC app (src-tauri/src/sync.rs there).
 */

data class LocalEntry(val dir: Boolean, val size: Long, val mtime: Long, val md5: String?)
data class RemoteEntry(val id: String, val dir: Boolean, val size: Long, val modified: Long, val md5: String?)
data class BaseEntry(val id: String, val dir: Boolean, val size: Long, val mtime: Long, val rmod: Long, val md5: String?)

sealed class Action {
    data class MkdirRemote(val path: String) : Action()
    data class Upload(val path: String, val id: String?) : Action()
    /** A local rename or move, done on Drive without sending the file again. */
    data class MoveRemote(val id: String, val from: String, val to: String) : Action()
    data class MkdirLocal(val path: String) : Action()
    /** A rename or move made on Drive, done here without downloading. */
    data class MoveLocal(val id: String, val from: String, val to: String) : Action()
    data class Download(val path: String, val id: String) : Action()
    data class TrashRemote(val path: String, val id: String) : Action()
    data class DeleteLocal(val path: String) : Action()
    /** Both sides already match: only the base is brought up to date. */
    data class Record(val path: String) : Action()
    data class Forget(val path: String) : Action()

    val isWork get() = this !is Record && this !is Forget
    val isUploadSide get() = this is MkdirRemote || this is Upload || this is MoveRemote || this is TrashRemote

    val key: String
        get() = when (this) {
            is MkdirRemote -> path; is MkdirLocal -> path; is DeleteLocal -> path; is Record -> path; is Forget -> path
            is Upload -> path; is Download -> path; is TrashRemote -> path
            is MoveRemote -> to; is MoveLocal -> to
        }
    val rank: Int
        get() = when (this) {
            is MkdirRemote -> 0; is MoveRemote -> 1; is Upload -> 2; is MkdirLocal -> 3; is MoveLocal -> 4
            is Download -> 5; is TrashRemote -> 6; is DeleteLocal -> 7; is Record -> 8; is Forget -> 9
        }
}

fun under(path: String, dir: String) = path.length > dir.length && path.startsWith(dir) && path[dir.length] == '/'
private fun depth(path: String) = path.count { it == '/' }

/** `driveWins`: local changes to already-synced files lose (the PC app's first cycle; the phone never uses it). */
fun plan(local: Map<String, LocalEntry>, remote: Map<String, RemoteEntry>, base: Map<String, BaseEntry>, driveWins: Boolean = false): List<Action> {
    val paths = (local.keys + remote.keys + base.keys).toSortedSet()
    val actions = ArrayList<Action>()
    val dirs = ArrayList<String>()

    for (p in paths) {
        val l = local[p]; val r = remote[p]; val b = base[p]
        if (l != null && r != null && l.dir != r.dir) continue // a file on one side, a folder on the other: left alone
        val isDir = l?.dir ?: r?.dir ?: b?.dir ?: false
        if (isDir) { dirs.add(p); continue }
        when {
            l != null && r != null -> {
                if (l.md5 != null && l.md5 == r.md5) {
                    val same = b != null && b.id == r.id && b.mtime == l.mtime && b.rmod == r.modified && b.md5 == r.md5
                    if (!same) actions.add(Action.Record(p))
                    continue
                }
                val localChanged = b == null || l.md5 != b.md5
                val remoteChanged = b == null || r.md5 != b.md5 || (r.md5 == null && r.modified != b.rmod)
                val upload = when {
                    driveWins && b != null -> false
                    localChanged && !remoteChanged -> true
                    remoteChanged && !localChanged -> false
                    else -> l.mtime > r.modified
                }
                actions.add(if (upload) Action.Upload(p, r.id) else Action.Download(p, r.id))
            }
            l != null -> actions.add(if (b != null && (driveWins || l.md5 == b.md5)) Action.DeleteLocal(p) else Action.Upload(p, null))
            r != null -> actions.add(
                if (b != null && !driveWins && r.md5 == b.md5 && !(r.md5 == null && r.modified != b.rmod)) Action.TrashRemote(p, r.id)
                else Action.Download(p, r.id),
            )
            else -> actions.add(Action.Forget(p))
        }
    }

    // Renames: a local removal plus a local addition with the same content is a move on Drive…
    val replaced = HashMap<Int, Action?>()
    val newUploads = HashMap<Pair<String, Long>, ArrayDeque<Int>>()
    actions.forEachIndexed { i, a ->
        if (a is Action.Upload && a.id == null) local.getValue(a.path).let { l -> l.md5?.let { newUploads.getOrPut(it to l.size) { ArrayDeque() }.add(i) } }
    }
    actions.forEachIndexed { i, a ->
        if (a is Action.TrashRemote) {
            val b = base.getValue(a.path)
            val md5 = b.md5 ?: return@forEachIndexed
            val j = newUploads[md5 to b.size]?.removeLastOrNull() ?: return@forEachIndexed
            replaced[i] = Action.MoveRemote(a.id, a.path, (actions[j] as Action.Upload).path)
            replaced[j] = null
        }
    }
    // …and a Drive removal plus a Drive addition of the same file (same id) is a move here.
    val downloads = HashMap<String, Int>()
    actions.forEachIndexed { i, a -> if (a is Action.Download && a.path !in local && a.path !in base) downloads[a.id] = i }
    actions.forEachIndexed { i, a ->
        if (a is Action.DeleteLocal) {
            val id = base.getValue(a.path).id
            val j = downloads[id] ?: return@forEachIndexed
            if (j in replaced) return@forEachIndexed
            replaced[i] = Action.MoveLocal(id, a.path, (actions[j] as Action.Download).path)
            replaced[j] = null
        }
    }
    val out = actions.mapIndexedNotNull { i, a -> if (i in replaced) replaced[i] else a }.toMutableList()

    // Folders, deepest first, so a folder knows whether anything inside it stays.
    val keepLocal = HashSet<String>() // will exist here and must exist on Drive
    val keepRemote = HashSet<String>() // will exist on Drive and must exist here
    for (a in out) when (a) {
        is Action.Upload -> keepLocal.add(a.path)
        is Action.MoveRemote -> keepLocal.add(a.to)
        is Action.Download -> keepRemote.add(a.path)
        is Action.MoveLocal -> keepRemote.add(a.to)
        else -> {}
    }
    for (p in local.keys) if (p in remote) { keepLocal.add(p); keepRemote.add(p) }
    dirs.sortWith(compareByDescending<String> { depth(it) }.thenBy { it })
    for (p in dirs) {
        val l = local[p]; val r = remote[p]; val b = base[p]
        val a: Action? = when {
            l != null && r != null -> if (b == null || b.id != r.id) Action.Record(p) else null
            l != null && b == null -> Action.MkdirRemote(p)
            l != null -> if (driveWins || keepLocal.none { under(it, p) }) Action.DeleteLocal(p) else Action.MkdirRemote(p)
            r != null && b == null -> Action.MkdirLocal(p)
            r != null -> if (driveWins || keepRemote.any { under(it, p) }) Action.MkdirLocal(p) else Action.TrashRemote(p, r.id)
            b != null -> Action.Forget(p)
            else -> null
        }
        when (a) {
            is Action.MkdirRemote -> keepLocal.add(p)
            is Action.MkdirLocal -> keepRemote.add(p)
            else -> {}
        }
        a?.let { out.add(it) }
    }

    // The order they run in: folders before what goes in them; moves before the trash.
    return out.sortedWith(compareBy<Action> { it.rank }.thenBy { depth(it.key) }.thenBy { it.key })
}
