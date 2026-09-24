package com.lotanbar.tripexplorer.sync

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** What the UI shows about the sync. */
data class SyncStatus(
    val signedIn: Boolean = false,
    val email: String? = null,
    val folder: String? = null,
    val running: Boolean = false,
    val busy: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val error: String? = null,
    val lastSync: Long? = null,
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
 * Keeps [root] in sync with a Drive folder on its own thread while started. Local changes are looked
 * for every 5 s (or at once after [poke]), Drive's every 30 s. The state (Drive folder, the known
 * Drive tree and the base) lives in [dataDir]; so does the sign-in (drive_auth.json, the same shape
 * as the PC app's).
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
    private var dirty = false
    /** Bumped by start and stop: a thread whose number is no longer current ends (even mid-transfer). */
    @Volatile private var generation = 0
    private var thread: Thread? = null
    private var drive: DriveApi? = null
    private var state = loadState()
    private val md5Cache = HashMap<String, Triple<Long, Long, String>>()
    private var lastRemote = 0L
    private var lastLocal = 0L
    private var retryAt = 0L

    @Volatile var status = SyncStatus()
        private set

    private val authFile get() = File(dataDir, "drive_auth.json")
    private val stateFile get() = File(dataDir, "drive_sync.json")

    init {
        dataDir.mkdirs()
        val auth = loadAuth()
        status = status.copy(signedIn = auth != null, email = auth?.second, folder = state.folderName)
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
        poke()
    }

    fun signOut() {
        authFile.delete()
        synchronized(lock) { drive = null }
        update { it.copy(signedIn = false, email = null) }
    }

    /** A Drive client for one-off calls (the folder picker). */
    fun client(): DriveApi? = loadAuth()?.let { DriveApi(clientId, clientSecret, it.first) }

    /** Syncs with this Drive folder from now on (a fresh start: both sides are merged). */
    fun pickFolder(id: String, name: String) {
        synchronized(lock) {
            state = SyncState(folderId = id, folderName = name)
            saveState()
            lastRemote = 0
            retryAt = 0
            dirty = true
            lock.notifyAll()
        }
        update { it.copy(folder = name, error = null) }
    }

    // ── Running ──

    fun start() {
        synchronized(lock) {
            if (thread != null) return
            val gen = ++generation
            thread = Thread({ run(gen) }, "drive-sync").apply { isDaemon = true; start() }
        }
        update { it.copy(running = true) }
    }

    fun stop() {
        synchronized(lock) {
            generation++
            lock.notifyAll()
            thread?.interrupt()
            thread = null
        }
        update { it.copy(running = false, busy = false) }
    }

    /** Something was written here: look now. */
    fun poke() {
        synchronized(lock) {
            dirty = true
            lock.notifyAll()
        }
    }

    private fun update(f: (SyncStatus) -> SyncStatus) {
        val s = synchronized(this) { f(status).also { status = it } }
        listener.onStatus(s)
    }

    private fun run(gen: Int) {
        val stopped = { gen != generation }
        while (true) {
            val wasDirty: Boolean
            synchronized(lock) {
                if (!dirty && !stopped()) runCatching { lock.wait(1000) }
                if (stopped()) return
                wasDirty = dirty
            }
            val root = root ?: continue
            if (state.folderId == null || !root.isDirectory) continue
            val d = synchronized(lock) {
                drive ?: loadAuth()?.let { DriveApi(clientId, clientSecret, it.first) }.also { drive = it }
            } ?: continue
            val now = System.currentTimeMillis()
            if (now < retryAt && !wasDirty) continue
            val remoteDue = now - lastRemote >= REMOTE_EVERY
            val localDue = wasDirty || now - lastLocal >= LOCAL_EVERY
            if (!remoteDue && !localDue) continue
            try {
                cycle(d, root, remoteDue, stopped)
                retryAt = 0
                if (status.error != null) update { it.copy(error = null) }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                if (stopped()) return
                retryAt = System.currentTimeMillis() + RETRY_AFTER
                val auth = e is DriveException && e.auth
                if (auth) signOut()
                update { it.copy(busy = false, error = if (auth) "Signed out of Google: sign in again" else (e.message ?: e.toString())) }
            }
        }
    }

    private fun cycle(d: DriveApi, root: File, remoteDue: Boolean, stopped: () -> Boolean) {
        val folder = state.folderId!!
        if (state.pageToken == null) {
            update { it.copy(busy = true) }
            val token = d.startPageToken()
            val nodes = HashMap<String, RNode>()
            listTree(d, folder, nodes)
            state.nodes = nodes
            state.pageToken = token
            saveState()
            lastRemote = System.currentTimeMillis()
        } else if (remoteDue) {
            pullChanges(d, folder)
            lastRemote = System.currentTimeMillis()
        }

        synchronized(lock) { dirty = false }
        val local = scanLocal(root)
        lastLocal = System.currentTimeMillis()
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
        val actions = plan(local, remote, state.base)

        fun sizeOf(a: Action) = when (a) {
            is Action.Upload -> local[a.path]?.size ?: 0
            is Action.Download -> remote[a.path]?.size ?: 0
            else -> 0
        }
        val work = actions.filter { it.isWork }
        val total = work.size
        val bytesTotal = work.sumOf { sizeOf(it) }
        if (total > 0) update { it.copy(busy = true, done = 0, total = total, bytesDone = 0, bytesTotal = bytesTotal) }

        val ids = HashMap<String, String>()
        remote.forEach { (p, e) -> if (e.dir) ids[p] = e.id }
        ids[""] = folder
        val goneRemote = ArrayList<String>()
        val goneLocal = ArrayList<String>()
        val changedHere = ArrayList<File>()
        var done = 0
        var bytesDone = 0L
        var lastSave = System.currentTimeMillis()
        for (a in actions) {
            if (stopped()) throw InterruptedException()
            val skip = when (a) {
                is Action.TrashRemote -> goneRemote.any { under(a.path, it) }
                is Action.DeleteLocal -> goneLocal.any { under(a.path, it) }
                else -> false
            }
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
                val (dn, bd) = done to bytesDone
                update { it.copy(done = dn, bytesDone = bd) }
            }
        }
        saveState()
        if (changedHere.isNotEmpty()) listener.onLocalChanged(changedHere)
        update { it.copy(busy = false, lastSync = System.currentTimeMillis(), done = if (total == 0) 0 else it.done, total = if (total == 0) 0 else it.total) }
    }

    /** Applies Drive's changes since the last look to the known tree. */
    private fun pullChanges(d: DriveApi, folder: String) {
        val (changes, token) = d.changes(state.pageToken!!)
        if (changes.isEmpty()) { state.pageToken = token; return }
        val before = remotePaths(folder, state.nodes).values.map { it.id }.toHashSet()
        for (c in changes) {
            if (c.fileId == folder) {
                if (c.removed || c.file?.trashed == true) throw DriveException("The Drive folder was removed or trashed; pick a folder again")
                continue
            }
            if (!c.removed && c.file == null) continue
            val n = if (c.removed) null else c.file?.let { RNode.from(it) }
            if (n != null) state.nodes[c.fileId] = n else state.nodes.remove(c.fileId)
        }
        // Keep only what is inside the folder; a folder that newly appeared in it (moved in from elsewhere
        // in Drive) has its contents listed, as those files did not change themselves.
        val now = remotePaths(folder, state.nodes)
        val inside = now.values.map { it.id }.toHashSet()
        state.nodes.keys.retainAll(inside)
        for (e in now.values) if (e.dir && e.id !in before) listTree(d, e.id, state.nodes)
        state.pageToken = token
        saveState()
    }

    /** Drops a path and everything under it from the base. (Drive's tree is left alone: a folder renamed on
     * Drive has the same id at its new path.) */
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
    )

    private fun loadState(): SyncState = runCatching {
        val o = JSONObject(stateFile.readText())
        val s = SyncState(o.optString("folder_id").ifEmpty { null }, o.optString("folder_name").ifEmpty { null }, o.optString("page_token").ifEmpty { null })
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
        val tmp = File(dataDir, "drive_sync.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(stateFile)
    }

    companion object {
        private const val REMOTE_EVERY = 30_000L
        private const val LOCAL_EVERY = 5_000L
        private const val RETRY_AFTER = 30_000L

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

        /** Everything under a Drive folder, into [nodes] (one request per folder). */
        fun listTree(d: DriveApi, top: String, nodes: MutableMap<String, RNode>) {
            val queue = ArrayDeque(listOf(top))
            while (queue.isNotEmpty()) {
                for (f in d.children(queue.removeLast(), false)) {
                    val n = RNode.from(f) ?: continue
                    if (n.dir) queue.add(f.id)
                    nodes[f.id] = n
                }
            }
        }
    }
}
