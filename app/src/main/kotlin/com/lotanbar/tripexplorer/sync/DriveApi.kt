package com.lotanbar.tripexplorer.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A Drive error; [auth] is set when the sign-in no longer works (sign in again). */
class DriveException(message: String, val auth: Boolean = false) : IOException(message)

data class RemoteFile(
    val id: String,
    val name: String,
    val parents: List<String>,
    val trashed: Boolean,
    val md5: String?,
    val size: Long,
    val modifiedMs: Long,
    val mimeType: String,
) {
    val isDir get() = mimeType == DriveApi.FOLDER_MIME
    /** Google Docs, Sheets and the like have no bytes to download; the sync leaves them alone. */
    val isGoogleDoc get() = mimeType.startsWith("application/vnd.google-apps.") && !isDir

    companion object {
        fun from(o: JSONObject) = RemoteFile(
            id = o.getString("id"),
            name = o.optString("name"),
            parents = o.optJSONArray("parents")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
            trashed = o.optBoolean("trashed"),
            md5 = o.optString("md5Checksum").ifEmpty { null },
            size = o.optString("size").toLongOrNull() ?: 0,
            modifiedMs = o.optString("modifiedTime").ifEmpty { null }?.let { DriveApi.parseTime(it) } ?: 0,
            mimeType = o.optString("mimeType"),
        )
    }
}

class Change(val removed: Boolean, val fileId: String, val file: RemoteFile?)

/**
 * The few Drive v3 calls the sync needs, over plain HttpURLConnection (no Google libraries).
 * PATCH goes as POST with X-HTTP-Method-Override, which Google's APIs accept. Blocking.
 */
class DriveApi(private val clientId: String, private val clientSecret: String, private val refreshToken: String) {
    private var access: String? = null
    private var accessUntil = 0L

    private fun token(): String {
        access?.let { if (System.currentTimeMillis() < accessUntil) return it }
        val (code, text) = post(TOKEN_URL, form("client_id" to clientId, "client_secret" to clientSecret, "refresh_token" to refreshToken, "grant_type" to "refresh_token"))
        if (code !in 200..299) throw DriveException("Sign-in expired: $text", auth = code == 400 || code == 401)
        val o = JSONObject(text)
        access = o.getString("access_token")
        accessUntil = System.currentTimeMillis() + (o.getLong("expires_in") - 60) * 1000
        return access!!
    }

    /** One request with a fresh token (retried once after a 401); returns the open connection on success. */
    private fun send(method: String, url: String, contentType: String? = null, headers: Map<String, String> = emptyMap(), body: ((OutputStream) -> Unit)? = null, length: Long = -1): HttpURLConnection {
        repeat(2) { attempt ->
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            c.setRequestProperty("Authorization", "Bearer ${token()}")
            if (method == "PATCH") {
                c.requestMethod = "POST"
                c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else c.requestMethod = method
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                contentType?.let { c.setRequestProperty("Content-Type", it) }
                if (length >= 0) c.setFixedLengthStreamingMode(length)
                c.outputStream.use(body)
            }
            val code = c.responseCode
            if (code == 401 && attempt == 0) {
                access = null
                c.disconnect()
                return@repeat
            }
            if (code !in 200..299) {
                val text = c.errorStream?.bufferedReader()?.readText().orEmpty()
                c.disconnect()
                throw DriveException("Drive $code: ${text.take(300)}")
            }
            return c
        }
        throw DriveException("Drive refused the sign-in", auth = true)
    }

    private fun json(method: String, url: String, body: JSONObject? = null): JSONObject {
        val bytes = body?.toString()?.toByteArray()
        val c = send(method, url, "application/json; charset=UTF-8", body = bytes?.let { b -> { it.write(b) } }, length = bytes?.size?.toLong() ?: -1)
        return c.inputStream.bufferedReader().use { JSONObject(it.readText()) }.also { c.disconnect() }
    }

    fun aboutEmail(): String = json("GET", "$API/about?fields=user(emailAddress)").getJSONObject("user").getString("emailAddress")

    /** Every non-trashed child of a folder (`foldersOnly`: just the subfolders), by name. */
    fun children(parent: String, foldersOnly: Boolean): List<RemoteFile> {
        var q = "'${parent.replace("\\", "\\\\").replace("'", "\\'")}' in parents and trashed = false"
        if (foldersOnly) q += " and mimeType = '$FOLDER_MIME'"
        val out = ArrayList<RemoteFile>()
        var page: String? = null
        do {
            var url = "$API/files?q=${enc(q)}&fields=${enc("nextPageToken,files($FILE_FIELDS)")}&pageSize=1000&orderBy=name"
            page?.let { url += "&pageToken=${enc(it)}" }
            val o = json("GET", url)
            o.optJSONArray("files")?.let { a -> for (i in 0 until a.length()) out.add(RemoteFile.from(a.getJSONObject(i))) }
            page = o.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    fun startPageToken(): String = json("GET", "$API/changes/startPageToken").getString("startPageToken")

    /** Every change since [token], and the token to continue from next time. */
    fun changes(token: String): Pair<List<Change>, String> {
        val out = ArrayList<Change>()
        var page = token
        while (true) {
            val o = json("GET", "$API/changes?pageToken=${enc(page)}&fields=${enc("nextPageToken,newStartPageToken,changes(removed,fileId,file($FILE_FIELDS))")}&pageSize=1000&includeRemoved=true&spaces=drive")
            o.optJSONArray("changes")?.let { a ->
                for (i in 0 until a.length()) {
                    val c = a.getJSONObject(i)
                    val id = c.optString("fileId").ifEmpty { null } ?: continue
                    out.add(Change(c.optBoolean("removed"), id, c.optJSONObject("file")?.let { RemoteFile.from(it) }))
                }
            }
            o.optString("newStartPageToken").ifEmpty { null }?.let { return out to it }
            page = o.optString("nextPageToken").ifEmpty { null } ?: throw DriveException("Drive changes list ended without a token")
        }
    }

    fun createFolder(parent: String, name: String): RemoteFile =
        RemoteFile.from(json("POST", "$API/files?fields=${enc(FILE_FIELDS)}", JSONObject().put("name", name).put("mimeType", FOLDER_MIME).put("parents", JSONArray().put(parent))))

    /**
     * Uploads [file] as a new file in [parent] ([id] null) or as new content of file [id]. Drive's
     * modified time is set to [modifiedMs] (the local file's), so both sides agree on "newer".
     */
    fun upload(id: String?, parent: String, name: String, file: File, modifiedMs: Long): RemoteFile {
        val meta = JSONObject().put("name", name).put("modifiedTime", formatTime(modifiedMs))
        if (id == null) meta.put("parents", JSONArray().put(parent))
        val url = if (id != null) "$UPLOAD/files/$id" else "$UPLOAD/files"
        val method = if (id != null) "PATCH" else "POST"
        val size = file.length()
        if (size <= MULTIPART_MAX) {
            val boundary = "te" + randomToken(12).replace('-', 'x').replace('_', 'x')
            val head = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray()
            val tail = "\r\n--$boundary--\r\n".toByteArray()
            val c = send(method, "$url?uploadType=multipart&fields=${enc(FILE_FIELDS)}", "multipart/related; boundary=$boundary", body = { out ->
                out.write(head)
                file.inputStream().use { it.copyTo(out) }
                out.write(tail)
            }, length = head.size + size + tail.size)
            return c.inputStream.bufferedReader().use { RemoteFile.from(JSONObject(it.readText())) }.also { c.disconnect() }
        }
        // Resumable: open a session, then send the whole file in one PUT.
        val metaBytes = meta.toString().toByteArray()
        val s = send(method, "$url?uploadType=resumable&fields=${enc(FILE_FIELDS)}", "application/json; charset=UTF-8", mapOf("X-Upload-Content-Length" to size.toString()), { it.write(metaBytes) }, metaBytes.size.toLong())
        val location = s.getHeaderField("Location") ?: throw DriveException("No upload session")
        s.disconnect()
        val c = URL(location).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.doOutput = true
        c.connectTimeout = 20_000
        c.readTimeout = 120_000
        c.setFixedLengthStreamingMode(size)
        c.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
        if (c.responseCode !in 200..299) throw DriveException("Upload ${c.responseCode}: ${c.errorStream?.bufferedReader()?.readText().orEmpty().take(300)}")
        return c.inputStream.bufferedReader().use { RemoteFile.from(JSONObject(it.readText())) }.also { c.disconnect() }
    }

    /** Downloads a file's bytes to [dest] through `<dest>.sync.tmp` next to it. */
    fun download(id: String, dest: File) {
        val c = send("GET", "$API/files/$id?alt=media")
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".sync.tmp")
        c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        c.disconnect()
        if (dest.exists() && !dest.delete()) throw DriveException("Could not replace ${dest.path}")
        if (!tmp.renameTo(dest)) throw DriveException("Could not write ${dest.path}")
    }

    /** Renames and/or moves a file or folder. */
    fun moveTo(id: String, name: String, newParent: String, oldParent: String): RemoteFile {
        var url = "$API/files/$id?fields=${enc(FILE_FIELDS)}"
        if (newParent != oldParent) url += "&addParents=${enc(newParent)}&removeParents=${enc(oldParent)}"
        return RemoteFile.from(json("PATCH", url, JSONObject().put("name", name)))
    }

    /** Moves a file or folder to Drive's trash (kept 30 days). */
    fun trash(id: String) {
        json("PATCH", "$API/files/$id?fields=id", JSONObject().put("trashed", true))
    }

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val SCOPE = "https://www.googleapis.com/auth/drive"
        private const val FILE_FIELDS = "id,name,parents,trashed,md5Checksum,size,modifiedTime,mimeType"
        /** Files up to this size go up in one multipart request; bigger ones through a resumable session. */
        private const val MULTIPART_MAX = 5L * 1024 * 1024

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun form(vararg p: Pair<String, String>) = p.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }

        private fun post(url: String, body: String): Pair<Int, String> {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            c.disconnect()
            return code to text
        }

        private fun randomToken(bytes: Int): String {
            val b = ByteArray(bytes)
            SecureRandom().nextBytes(b)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }

        private fun utcFormat(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        fun parseTime(s: String): Long? {
            // "2026-09-24T14:21:58.123Z" (Drive always sends UTC, with or without millis)
            val clean = s.removeSuffix("Z")
            val (base, frac) = clean.split('.').let { it[0] to it.getOrNull(1) }
            val ms = runCatching { utcFormat("yyyy-MM-dd'T'HH:mm:ss").parse(base)!!.time }.getOrNull() ?: return null
            return ms + (frac?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0)
        }

        fun formatTime(ms: Long): String = utcFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(Date(ms))

        /**
         * The browser sign-in (OAuth for installed apps: loopback redirect + PKCE). [openUrl] shows
         * Google's page; this waits up to five minutes for the redirect to a one-shot local server and
         * returns the refresh token.
         */
        fun signIn(clientId: String, clientSecret: String, backLink: String? = null, openUrl: (String) -> Unit): String {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val redirect = "http://127.0.0.1:${server.localPort}"
                val verifier = randomToken(48)
                val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
                val state = randomToken(16)
                openUrl(
                    "https://accounts.google.com/o/oauth2/v2/auth?" + form(
                        "client_id" to clientId, "redirect_uri" to redirect, "response_type" to "code", "scope" to SCOPE,
                        "code_challenge" to challenge, "code_challenge_method" to "S256", "access_type" to "offline",
                        "prompt" to "consent", "state" to state,
                    ),
                )
                server.soTimeout = 1000
                val deadline = System.currentTimeMillis() + 300_000
                var code: String? = null
                while (code == null) {
                    if (System.currentTimeMillis() > deadline) throw DriveException("Sign-in timed out")
                    if (Thread.currentThread().isInterrupted) throw DriveException("Sign-in cancelled")
                    val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    socket.use { s ->
                        val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                        val target = line.split(' ').getOrNull(1).orEmpty()
                        val query = target.substringAfter('?', "").split('&').filter { '=' in it }
                            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                        val out = s.getOutputStream()
                        if (query.isEmpty()) {
                            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                            return@use
                        }
                        val ok = query["state"] == state && query["code"] != null
                        // A phone-sized page; the link opens the app again (Android intent: URL, `backLink`).
                        val page = { title: String, extra: String ->
                            "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>" +
                                "<body style=\"font-family:sans-serif;background:#121212;color:#e0e0e0;padding:24px\"><h2>$title</h2>$extra</body></html>"
                        }
                        val body = if (ok) page("Trip Explorer is signed in.", backLink?.let { "<p><a style=\"color:#2196f3;font-size:1.2em\" href=\"$it\">Back to Trip Explorer</a></p>" } ?: "<p>Go back to the app.</p>")
                        else page("Sign-in did not finish.", "")
                        val bytes = body.toByteArray()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(bytes)
                        out.flush()
                        if (!ok) throw DriveException(query["error"] ?: "Sign-in was refused")
                        code = query["code"]
                    }
                }
                val (status, text) = post(TOKEN_URL, form(
                    "client_id" to clientId, "client_secret" to clientSecret, "code" to code!!, "code_verifier" to verifier,
                    "redirect_uri" to redirect, "grant_type" to "authorization_code",
                ))
                if (status !in 200..299) throw DriveException("Token exchange failed: $text")
                return JSONObject(text).optString("refresh_token").ifEmpty { null } ?: throw DriveException("Google sent no refresh token")
            }
        }
    }
}
