package com.lotanbar.tripexplorer.sync

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** What the UI shows about the sync. */
data class SyncStatus(
    val signedIn: Boolean = false,
    val email: String? = null,
    val folder: String? = null,
    val busy: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val error: String? = null,
    val lastSync: Long? = null,
    /** What is being sent or fetched right now ("↑ …" / "↓ …"). */
    val current: String? = null,
    /** Seconds left, from the transfer rate so far. */
    val etaS: Long? = null,
    /** What the last sync did, one line per item ("↑ …" went up, "↓ …" came down). */
    val lastChanges: List<String> = emptyList(),
)

data class RNode(val name: String, val parent: String, val dir: Boolean, val size: Long, val modified: Long, val md5: String?) {
    companion object {
        fun from(f: RemoteFile): RNode? =
            if (f.trashed || f.isGoogleDoc || '/' in f.name || '\\' in f.name) null
            else RNode(f.name, f.parents.firstOrNull().orEmpty(), f.isDir, f.size, f.modifiedMs, f.md5)
    }
}

/** Path of every node under [root] (nodes outside it are left out). Two items with one path: the newer one. */
fun remotePaths(root: String, nodes: Map<String, RNode>): Map<String, RemoteEntry> {
    val memo = HashMap<String, String?>()
    fun pathOf(id: String, depth: Int): String? {
        if (id == root) return ""
        if (id in memo) return memo[id]
        val n = nodes[id]
        val result = if (n == null || depth > 64) null else pathOf(n.parent, depth + 1)?.let { if (it.isEmpty()) n.name else "$it/${n.name}" }
        memo[id] = result
        return result
    }
    val out = sortedMapOf<String, RemoteEntry>()
    for ((id, n) in nodes) {
        val p = pathOf(id, 0) ?: continue
        val e = RemoteEntry(id, n.dir, n.size, n.modified, n.md5)
        val old = out[p]
        if (old == null || old.modified < e.modified || (old.modified == e.modified && old.id < e.id)) out[p] = e
    }
    return out
}

/** Files the sync never touches: temp files of in-progress writes, thumbnails caches. */
fun ignored(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".tmp") || lower == "desktop.ini" || lower == "thumbs.db"
}

fun fileMd5(f: File): String {
    val md = MessageDigest.getInstance("MD5")
    f.inputStream().use { input ->
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Syncs [root] with a Drive folder, one pass per [request] (the Sync button), on its own thread. The
 * state (Drive folder and the base) lives in [dataDir]; so does the sign-in (drive_auth.json, the same
 * shape as the PC app's).
 */
class SyncEngine(
    private val dataDir: File,
    private val clientId: String,
    private val clientSecret: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(status: SyncStatus)
        /** Files were written, moved or removed here by the sync. */
        fun onLocalChanged(files: List<File>)
    }

    @Volatile var root: File? = null
    private val lock = Object()
    private var requested = false
    private var thread: Thread? = null
    private var drive: DriveApi? = null
    private var state = loadState()
    private val md5Cache = HashMap<String, Triple<Long, Long, String>>()

    @Volatile var status = SyncStatus()
        private set

    private val authFile get() = File(dataDir, "drive_auth.json")
    private val stateFile get() = File(dataDir, "drive_sync.json")

    init {
        dataDir.mkdirs()
        val auth = loadAuth()
        status = status.copy(signedIn = auth != null, email = auth?.second, folder = state.folderName, lastSync = state.lastSync, lastChanges = state.lastChanges)
    }

    // ── Sign-in and folder ──

    /** (refresh token, email) */
    fun loadAuth(): Pair<String, String?>? = runCatching {
        val o = JSONObject(authFile.readText())
        o.getString("refresh_token") to o.optString("email").ifEmpty { null }
    }.getOrNull()

    fun saveAuth(refreshToken: String, email: String?) {
        authFile.writeText(JSONObject().put("refresh_token", refreshToken).put("email", email).toString())
        synchronized(lock) { drive = null }
        update { it.copy(signedIn = true, email = email, error = null) }
    }

    fun signOut() {
        authFile.delete()
        synchronized(lock) { drive = null }
        update { it.copy(signedIn = false, email = null) }
    }

    /** A Drive client for one-off calls (the folder picker). */
    fun client(): DriveApi? = loadAuth()?.let { DriveApi(clientId, clientSecret, it.first) }

    /** Syncs with this Drive folder from now on (a fresh start: both sides are merged on the next pass). */
    fun pickFolder(id: String, name: String) {
        synchronized(lock) {
            state = SyncState(folderId = id, folderName = name)
            saveState()
        }
        update { it.copy(folder = name, error = null, lastSync = null, lastChanges = emptyList()) }
    }

    // ── Running ──

    /** One pass: Drive's changes come down, this folder's go up; the newer change wins. */
    fun request() {
        synchronized(lock) {
            if (thread == null) thread = Thread(::run, "drive-sync").apply { isDaemon = true; start() }
            requested = true
            lock.notifyAll()
        }
    }

    private fun update(f: (SyncStatus) -> SyncStatus) {
        val s = synchronized(this) { f(status).also { status = it } }
        listener.onStatus(s)
    }

    private fun run() {
        while (true) {
            synchronized(lock) {
                while (!requested) lock.wait()
                requested = false
            }
            val root = root
            val problem = when {
                root == null || !root.isDirectory -> "The trips folder is missing"
                state.folderId == null -> "Pick a Drive folder first"
                loadAuth() == null -> "Sign in to Google first"
                else -> null
            }
            if (problem != null) {
                update { it.copy(error = problem) }
                continue
            }
            val d = synchronized(lock) { drive ?: DriveApi(clientId, clientSecret, loadAuth()!!.first).also { drive = it } }
            try {
                cycle(d, root!!)
                update { it.copy(error = null) }
            } catch (e: Exception) {
                val auth = e is DriveException && e.auth
                if (auth) signOut()
                update { it.copy(busy = false, current = null, etaS = null, error = if (auth) "Signed out of Google: sign in again" else (e.message ?: e.toString())) }
            }
        }
    }

    private fun cycle(d: DriveApi, root: File) {
        val folder = state.folderId!!
        update { it.copy(busy = true, done = 0, total = 0, bytesDone = 0, bytesTotal = 0, current = "Checking Drive…", etaS = null) }
        // Drive as it is right now: the whole folder is listed on every pass (Drive's change feed can lag
        // behind by seconds, and a pass usually follows right after the other device's).
        if (d.get(folder).trashed) throw DriveException("The Drive folder was removed or trashed; pick a folder again")
        val nodes = HashMap<String, RNode>()
        listTree(d, folder, nodes)
        state.nodes = nodes

        update { it.copy(current = "Looking at the trips folder…") }
        val local = scanLocal(root)
        for ((path, e) in local.entries.toList()) {
            if (e.dir) continue
            val b = state.base[path]
            if (b != null && b.size == e.size && b.mtime == e.mtime) { local[path] = e.copy(md5 = b.md5); continue }
            val c = md5Cache[path]
            if (c != null && c.first == e.size && c.second == e.mtime) { local[path] = e.copy(md5 = c.third); continue }
            val m = runCatching { fileMd5(localFile(root, path)) }.getOrNull() ?: continue
            md5Cache[path] = Triple(e.size, e.mtime, m)
            local[path] = e.copy(md5 = m)
        }
        val remote = remotePaths(folder, state.nodes)
        if (remote.isEmpty() && state.base.isNotEmpty()) {
            throw DriveException("The Drive folder is empty, but it was synced before: nothing was changed here. Pick the folder again to start over")
        }
        val actions = plan(local, remote, state.base)

        fun sizeOf(a: Action) = when (a) {
            is Action.Upload -> local[a.path]?.size ?: 0
            is Action.Download -> remote[a.path]?.size ?: 0
            else -> 0
        }
        val work = actions.filter { it.isWork }
        val total = work.size
        val bytesTotal = work.sumOf { sizeOf(it) }
        update { it.copy(total = total, bytesTotal = bytesTotal, current = null) }

        val ids = HashMap<String, String>()
        remote.forEach { (p, e) -> if (e.dir) ids[p] = e.id }
        ids[""] = folder
        val goneRemote = ArrayList<String>()
        val goneLocal = ArrayList<String>()
        val changedHere = ArrayList<File>()
        val changes = ArrayList<String>()
        val started = System.currentTimeMillis()
        var done = 0
        var bytesDone = 0L
        var lastSave = System.currentTimeMillis()
        for (a in actions) {
            val skip = when (a) {
                is Action.TrashRemote -> goneRemote.any { under(a.path, it) }
                is Action.DeleteLocal -> goneLocal.any { under(a.path, it) }
                else -> false
            }
            // Items inside a folder that was just removed went with it: not listed on their own.
            if (!skip) describe(a)?.let { line -> changes.add(line); update { it.copy(current = line) } }
            if (skip) forgetTree(a.key) else apply(d, root, a, local, remote, ids)
            when (a) {
                is Action.TrashRemote -> goneRemote.add(a.path)
                is Action.DeleteLocal -> { goneLocal.add(a.path); changedHere.add(localFile(root, a.path)) }
                is Action.Download -> changedHere.add(localFile(root, a.path))
                is Action.MkdirLocal -> changedHere.add(localFile(root, a.path))
                is Action.MoveLocal -> { changedHere.add(localFile(root, a.from)); changedHere.add(localFile(root, a.to)) }
                else -> {}
            }
            if (a.isWork) {
                done++
                bytesDone += sizeOf(a)
                if (System.currentTimeMillis() - lastSave > 2000) { saveState(); lastSave = System.currentTimeMillis() }
                // Time left: each item costs its bytes plus a fixed request overhead, at the pace so far.
                val secs = (System.currentTimeMillis() - started) / 1000.0
                val workDone = bytesDone + done * ITEM_OVERHEAD
                val workLeft = (bytesTotal - bytesDone) + (total - done) * ITEM_OVERHEAD
                val eta = if (secs > 1.0 && done < total) Math.round(workLeft / (workDone / secs)) else null
                val (dn, bd) = done to bytesDone
                update { it.copy(done = dn, bytesDone = bd, etaS = eta) }
            }
        }
        state.lastSync = System.currentTimeMillis()
        state.lastChanges = changes
        saveState()
        if (changedHere.isNotEmpty()) listener.onLocalChanged(changedHere)
        update { it.copy(busy = false, current = null, etaS = null, lastSync = state.lastSync, lastChanges = changes) }
    }

    private fun forgetTree(path: String) {
        state.base.keys.filter { it == path || under(it, path) }.forEach { state.base.remove(it) }
    }

    private fun record(path: String, id: String, dir: Boolean, file: File, n: RNode) {
        val (size, mtime) = if (dir) 0L to 0L else file.length() to file.lastModified()
        state.base[path] = BaseEntry(id, dir, size, mtime, n.modified, n.md5)
    }

    private fun putNode(f: RemoteFile): RNode {
        val n = RNode.from(f) ?: RNode(f.name, "", f.isDir, f.size, f.modifiedMs, f.md5)
        state.nodes[f.id] = n
        return n
    }

    private fun parentId(ids: Map<String, String>, rel: String): String {
        val dir = rel.substringBeforeLast('/', "")
        return ids[dir] ?: throw DriveException("No Drive folder for $dir")
    }

    private fun apply(d: DriveApi, root: File, a: Action, local: Map<String, LocalEntry>, remote: Map<String, RemoteEntry>, ids: MutableMap<String, String>) {
        when (a) {
            is Action.MkdirRemote -> {
                val f = d.createFolder(parentId(ids, a.path), a.path.substringAfterLast('/'))
                val n = putNode(f)
                ids[a.path] = f.id
                record(a.path, f.id, true, localFile(root, a.path), n)
            }
            is Action.Upload -> {
                val file = localFile(root, a.path)
                val f = d.upload(a.id, parentId(ids, a.path), a.path.substringAfterLast('/'), file, local[a.path]?.mtime ?: file.lastModified())
                record(a.path, f.id, false, file, putNode(f))
            }
            is Action.MoveRemote -> {
                val newParent = parentId(ids, a.to)
                val oldParent = state.nodes[a.id]?.parent ?: newParent
                val f = d.moveTo(a.id, a.to.substringAfterLast('/'), newParent, oldParent)
                val n = putNode(f)
                state.base.remove(a.from)
                record(a.to, a.id, false, localFile(root, a.to), n)
            }
            is Action.MkdirLocal -> {
                localFile(root, a.path).mkdirs()
                val r = remote.getValue(a.path)
                ids[a.path] = r.id
                record(a.path, r.id, true, localFile(root, a.path), state.nodes.getValue(r.id))
            }
            is Action.MoveLocal -> {
                val dest = localFile(root, a.to)
                dest.parentFile?.mkdirs()
                if (!localFile(root, a.from).renameTo(dest)) throw DriveException("Could not move ${a.from} to ${a.to}")
                val n = state.nodes.getValue(a.id)
                if (n.modified > 0) dest.setLastModified(n.modified)
                state.base.remove(a.from)
                record(a.to, a.id, false, dest, n)
            }
            is Action.Download -> {
                val dest = localFile(root, a.path)
                d.download(a.id, dest)
                val n = state.nodes.getValue(a.id)
                if (n.modified > 0) dest.setLastModified(n.modified)
                record(a.path, a.id, false, dest, n)
            }
            is Action.TrashRemote -> {
                d.trash(a.id)
                forgetTree(a.path)
                state.nodes.remove(a.id)
            }
            is Action.DeleteLocal -> {
                val f = localFile(root, a.path)
                if (f.exists() && !f.deleteRecursively()) throw DriveException("Could not remove ${a.path}")
                forgetTree(a.path)
            }
            is Action.Record -> {
                val r = remote.getValue(a.path)
                if (r.dir) ids[a.path] = r.id
                record(a.path, r.id, r.dir, localFile(root, a.path), state.nodes.getValue(r.id))
            }
            is Action.Forget -> state.base.remove(a.path)
        }
    }

    // ── State file ──

    class SyncState(
        var folderId: String? = null,
        var folderName: String? = null,
        var pageToken: String? = null,
        var nodes: HashMap<String, RNode> = HashMap(),
        val base: java.util.TreeMap<String, BaseEntry> = java.util.TreeMap(),
        var lastSync: Long? = null,
        var lastChanges: List<String> = emptyList(),
    )

    private fun loadState(): SyncState = runCatching {
        val o = JSONObject(stateFile.readText())
        val s = SyncState(o.optString("folder_id").ifEmpty { null }, o.optString("folder_name").ifEmpty { null }, o.optString("page_token").ifEmpty { null })
        s.lastSync = o.optLong("last_sync").takeIf { it > 0 }
        s.lastChanges = o.optJSONArray("last_changes")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        o.optJSONObject("nodes")?.let { n ->
            for (id in n.keys()) n.getJSONObject(id).let { e ->
                s.nodes[id] = RNode(e.getString("name"), e.getString("parent"), e.getBoolean("dir"), e.getLong("size"), e.getLong("modified"), e.optString("md5").ifEmpty { null })
            }
        }
        o.optJSONObject("base")?.let { b ->
            for (p in b.keys()) b.getJSONObject(p).let { e ->
                s.base[p] = BaseEntry(e.getString("id"), e.getBoolean("dir"), e.getLong("size"), e.getLong("mtime"), e.getLong("rmod"), e.optString("md5").ifEmpty { null })
            }
        }
        s
    }.getOrElse { SyncState() }

    private fun saveState() {
        val nodes = JSONObject()
        state.nodes.forEach { (id, n) ->
            nodes.put(id, JSONObject().put("name", n.name).put("parent", n.parent).put("dir", n.dir).put("size", n.size).put("modified", n.modified).put("md5", n.md5 ?: ""))
        }
        val base = JSONObject()
        state.base.forEach { (p, e) ->
            base.put(p, JSONObject().put("id", e.id).put("dir", e.dir).put("size", e.size).put("mtime", e.mtime).put("rmod", e.rmod).put("md5", e.md5 ?: ""))
        }
        val o = JSONObject().put("folder_id", state.folderId ?: "").put("folder_name", state.folderName ?: "").put("page_token", state.pageToken ?: "")
            .put("nodes", nodes).put("base", base)
            .put("last_sync", state.lastSync ?: 0).put("last_changes", org.json.JSONArray(state.lastChanges))
        val tmp = File(dataDir, "drive_sync.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(stateFile)
    }

    companion object {
        /** The cost of one item for the time-left estimate, as bytes (a request's round trip). */
        private const val ITEM_OVERHEAD = 64.0 * 1024

        fun localFile(root: File, rel: String): File = rel.split('/').fold(root) { f, part -> File(f, part) }

        fun scanLocal(root: File): MutableMap<String, LocalEntry> {
            val out = sortedMapOf<String, LocalEntry>()
            fun walk(dir: File, rel: String) {
                for (f in dir.listFiles() ?: return) {
                    if (ignored(f.name)) continue
                    val path = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                    if (f.isDirectory) {
                        out[path] = LocalEntry(true, 0, 0, null)
                        walk(f, path)
                    } else if (f.isFile) out[path] = LocalEntry(false, f.length(), f.lastModified(), null)
                }
            }
            walk(root, "")
            return out
        }

        /** Everything under a Drive folder, into [nodes]: level by level, up to 8 folders listed at once. */
        fun listTree(d: DriveApi, top: String, nodes: MutableMap<String, RNode>) {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
            try {
                var level = listOf(top)
                while (level.isNotEmpty()) {
                    val results = level.map { dir -> pool.submit<List<RemoteFile>> { d.children(dir, false) } }.map {
                        try { it.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
                    }
                    val next = ArrayList<String>()
                    for (list in results) for (f in list) {
                        val n = RNode.from(f) ?: continue
                        if (n.dir) next.add(f.id)
                        nodes[f.id] = n
                    }
                    level = next
                }
            } finally {
                pool.shutdown()
            }
        }

        /** One line for the progress and the "what changed" list: ↑ went to Drive, ↓ came from Drive. */
        fun describe(a: Action): String? = when (a) {
            is Action.MkdirRemote -> "↑ new folder ${a.path}"
            is Action.Upload -> "↑ ${a.path}"
            is Action.MoveRemote -> "↑ renamed ${a.from} → ${a.to}"
            is Action.TrashRemote -> "↑ removed ${a.path}"
            is Action.MkdirLocal -> "↓ new folder ${a.path}"
            is Action.Download -> "↓ ${a.path}"
            is Action.MoveLocal -> "↓ renamed ${a.from} → ${a.to}"
            is Action.DeleteLocal -> "↓ removed ${a.path}"
            is Action.Record, is Action.Forget -> null
        }
    }
}
