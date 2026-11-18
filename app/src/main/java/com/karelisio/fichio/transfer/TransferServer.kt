package com.karelisio.fichio.transfer

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import android.util.Size
import com.karelisio.fichio.core.model.FileTypes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.parseQueryString
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
internal data class WebEntry(
    val name: String,
    val path: String,
    val dir: Boolean,
    val size: Long,
    val mtime: Long,
    val type: String,
    val hidden: Boolean,
)

@Serializable
internal data class WebListing(val path: String, val parent: String?, val entries: List<WebEntry>)

@Serializable
internal data class WebInfo(val device: String, val free: Long, val total: Long, val root: String)

@Serializable
internal data class WebAuthResult(val ok: Boolean, val error: String? = null, val attemptsLeft: Int? = null)

@Serializable
internal data class WebUploadResult(val saved: List<String>, val skipped: Int)

/** Transfer log actions, stored in [com.karelisio.fichio.data.db.TransferLogEntity.action]. */
object TransferActions {
    const val CONNECT = "connect"
    const val DENIED = "denied"
    const val DOWNLOAD = "download"
    const val UPLOAD = "upload"
    const val MKDIR = "mkdir"
}

/**
 * Ktor CIO server bound to the Wi-Fi address only. Every request is checked for a private
 * client address and the expected Host header (DNS-rebinding guard); every /api route except
 * auth requires the session cookie; every path goes through [PathGuard].
 */
class TransferServer(
    private val context: Context,
    val host: String,
    val port: Int,
    private val auth: TransferAuth,
    private val guard: PathGuard,
    private val log: (action: String, path: String, size: Long, client: String) -> Unit,
    /** Directory (absolute) whose content changed after an upload / mkdir. */
    private val onChanged: (String) -> Unit,
) {
    private var engine: EmbeddedServer<*, *>? = null
    private val json = Json { encodeDefaults = true }
    val lastRequest = AtomicLong(System.currentTimeMillis())

    private val indexHtml: ByteArray by lazy {
        runCatching { context.assets.open("web/index.html").use { it.readBytes() } }
            .getOrDefault("<h1>Fichio</h1>".toByteArray())
    }

    fun start() {
        engine = embeddedServer(CIO, port = port, host = host) { module() }.start(wait = false)
    }

    fun stop() {
        runCatching { engine?.stop(300, 1_500) }
        engine = null
    }

    private val expectedHosts = setOf("$host:$port", if (port == 80) host else "$host:$port")
    private val expectedOrigin = "http://$host:$port"

    private fun Application.module() {
        intercept(ApplicationCallPipeline.Plugins) {
            val c = call
            val client = c.request.local.remoteAddress
            if (!NetUtils.isPrivateAddress(client)) {
                log(TransferActions.DENIED, c.request.local.uri, 0, client)
                c.respondText("Accès refusé", status = HttpStatusCode.Forbidden)
                finish(); return@intercept
            }
            val hostHeader = c.request.headers[HttpHeaders.Host]
            if (hostHeader !in expectedHosts) {
                c.respondText("Hôte invalide", status = HttpStatusCode.BadRequest)
                finish(); return@intercept
            }
            val origin = c.request.headers[HttpHeaders.Origin]
            if (c.request.local.method.value != "GET" && origin != null && origin != expectedOrigin) {
                c.respondText("Origine refusée", status = HttpStatusCode.Forbidden)
                finish(); return@intercept
            }
            lastRequest.set(System.currentTimeMillis())
            TransferController.touch()
            c.response.header("X-Content-Type-Options", "nosniff")
            c.response.header("X-Frame-Options", "DENY")
            c.response.header("Referrer-Policy", "no-referrer")
            c.response.header("Cache-Control", "no-store")
        }

        routing {
            get("/") {
                val code = call.request.queryParameters["code"]
                if (code != null) {
                    when (val r = auth.submit(code)) {
                        is TransferAuth.Result.Success -> {
                            setSession(call, r.token)
                            log(TransferActions.CONNECT, "/", 0, call.client())
                            call.respondRedirect("/")
                        }
                        else -> {
                            log(TransferActions.DENIED, "code", 0, call.client())
                            call.respondRedirect("/?e=${errorKey(r)}")
                        }
                    }
                    return@get
                }
                call.response.header(
                    "Content-Security-Policy",
                    "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; " +
                        "img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; form-action 'self'",
                )
                call.respondBytes(indexHtml, ContentType.Text.Html.withCharset(Charsets.UTF_8))
            }

            post("/api/auth") {
                val body = runCatching { call.receiveText() }.getOrDefault("")
                val code = parseQueryString(body)["code"] ?: body.trim()
                val r = auth.submit(code)
                if (r is TransferAuth.Result.Success) {
                    setSession(call, r.token)
                    log(TransferActions.CONNECT, "/", 0, call.client())
                    call.respondJson(WebAuthResult(true))
                } else {
                    log(TransferActions.DENIED, "code", 0, call.client())
                    call.respondJson(
                        WebAuthResult(false, errorKey(r), (r as? TransferAuth.Result.Invalid)?.attemptsLeft),
                        HttpStatusCode.Unauthorized,
                    )
                }
            }

            get("/api/session") {
                val ok = auth.isValidToken(call.token())
                call.respondJson(WebAuthResult(ok), if (ok) HttpStatusCode.OK else HttpStatusCode.Unauthorized)
            }

            get("/api/info") {
                if (!call.requireSession()) return@get
                val stat = runCatching { StatFs(guard.root.path) }.getOrNull()
                val name = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
                    ?: "${Build.MANUFACTURER} ${Build.MODEL}"
                call.respondJson(WebInfo(name, stat?.availableBytes ?: 0, stat?.totalBytes ?: 0, "Stockage interne"))
            }

            get("/api/list") {
                if (!call.requireSession()) return@get
                val dir = guard.resolve(call.request.queryParameters["path"])
                if (dir == null || !dir.isDirectory) return@get call.notFound()
                val entries = withContext(Dispatchers.IO) {
                    dir.listFiles().orEmpty().asSequence()
                        .filter { guard.isAllowed(it) }
                        .map { f ->
                            val isDir = f.isDirectory
                            WebEntry(
                                name = f.name,
                                path = guard.relative(f),
                                dir = isDir,
                                size = if (isDir) 0 else f.length(),
                                mtime = f.lastModified(),
                                type = if (isDir) "FOLDER" else FileTypes.typeOfName(f.name).name,
                                hidden = f.name.startsWith("."),
                            )
                        }
                        .sortedWith(compareBy<WebEntry>({ !it.dir }, { it.name.lowercase() }))
                        .toList()
                }
                val rel = guard.relative(dir)
                val parent = if (rel == "/") null else rel.substringBeforeLast('/').ifEmpty { "/" }
                call.respondJson(WebListing(rel, parent, entries))
            }

            get("/api/download") {
                if (!call.requireSession()) return@get
                val f = guard.resolve(call.request.queryParameters["path"])
                if (f == null || !f.exists()) return@get call.notFound()
                val inline = call.request.queryParameters["inline"] == "1"
                if (f.isDirectory) {
                    call.response.header(HttpHeaders.ContentDisposition, disposition("${if (f == guard.root) "Stockage" else f.name}.zip", false))
                    log(TransferActions.DOWNLOAD, guard.relative(f) + "/", 0, call.client())
                    call.respondOutputStream(ContentType.parse("application/zip")) {
                        ZipOutputStream(this).use { zip -> zipDir(zip, f, "") }
                    }
                } else {
                    call.response.header(HttpHeaders.ContentDisposition, disposition(f.name, inline))
                    respondFileRanged(call, f, logDownload = !inline)
                }
            }

            get("/api/thumb") {
                if (!call.requireSession()) return@get
                val f = guard.resolve(call.request.queryParameters["path"])
                if (f == null || !f.isFile) return@get call.notFound()
                val bytes = withContext(Dispatchers.IO) { thumbnail(f) } ?: return@get call.notFound()
                call.response.header("Cache-Control", "private, max-age=3600")
                call.respondBytes(bytes, ContentType.Image.JPEG)
            }

            post("/api/upload") {
                if (!call.requireSession()) return@post
                val dir = guard.resolve(call.request.queryParameters["path"])
                if (dir == null || !dir.isDirectory) return@post call.notFound()
                val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (length != null && length > dir.usableSpace) {
                    return@post call.respondJson(WebAuthResult(false, "space"), HttpStatusCode.InsufficientStorage)
                }
                val saved = ArrayList<String>()
                var skipped = 0
                val multipart = call.receiveMultipart(formFieldLimit = Long.MAX_VALUE)
                multipart.forEachPart { part ->
                    try {
                        if (part is PartData.FileItem) {
                            val name = PathGuard.safeName(part.originalFileName)
                            if (name == null) {
                                skipped++
                            } else {
                                val written = receiveFile(dir, name, part)
                                if (written != null) {
                                    saved += written.name
                                    log(TransferActions.UPLOAD, guard.relative(written), written.length(), call.client())
                                } else skipped++
                            }
                        }
                    } finally {
                        part.dispose()
                    }
                }
                if (saved.isNotEmpty()) onChanged(dir.path)
                call.respondJson(WebUploadResult(saved, skipped))
            }

            post("/api/mkdir") {
                if (!call.requireSession()) return@post
                val dir = guard.resolve(call.request.queryParameters["path"])
                val name = PathGuard.safeName(call.request.queryParameters["name"])
                if (dir == null || !dir.isDirectory || name == null) return@post call.notFound()
                val target = File(dir, name)
                if (!guard.isAllowed(target)) return@post call.notFound()
                if (target.exists()) return@post call.respondJson(WebAuthResult(false, "exists"), HttpStatusCode.Conflict)
                val ok = withContext(Dispatchers.IO) { target.mkdir() }
                if (ok) {
                    log(TransferActions.MKDIR, guard.relative(target), 0, call.client())
                    onChanged(dir.path)
                }
                call.respondJson(WebAuthResult(ok), if (ok) HttpStatusCode.OK else HttpStatusCode.InternalServerError)
            }

            post("/api/logout") {
                call.response.header(HttpHeaders.SetCookie, "$COOKIE=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0")
                call.respondJson(WebAuthResult(true))
            }
        }
    }

    /** Streams one multipart file to a temporary file then renames it, resolving name conflicts. */
    /**
     * Sends [f] honouring `Range` / `If-Range` (206 + Content-Range, 416 when out of bounds), so
     * that browsers and download managers can resume an interrupted transfer.
     */
    private suspend fun respondFileRanged(call: ApplicationCall, f: File, logDownload: Boolean) {
        val total = f.length()
        val modified = f.lastModified()
        val tag = HttpRange.etag(total, modified)
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        call.response.header(HttpHeaders.ETag, tag)
        call.response.header(HttpHeaders.LastModified, HttpRange.httpDate(modified))
        val decision = HttpRange.decide(
            call.request.headers[HttpHeaders.Range], call.request.headers[HttpHeaders.IfRange], total, tag, modified,
        )
        when (decision) {
            HttpRange.Decision.Full -> {
                if (logDownload) log(TransferActions.DOWNLOAD, guard.relative(f), total, call.client())
                call.respondFile(f)
            }
            HttpRange.Decision.Unsatisfiable -> {
                call.response.header(HttpHeaders.ContentRange, HttpRange.unsatisfiedRange(total))
                call.respondText("", status = HttpStatusCode.RequestedRangeNotSatisfiable)
            }
            is HttpRange.Decision.Partial -> {
                val span = decision.span
                // A resumed download is logged once, by its first request.
                if (logDownload && span.start == 0L) log(TransferActions.DOWNLOAD, guard.relative(f), total, call.client())
                call.response.header(HttpHeaders.ContentRange, HttpRange.contentRange(span, total))
                val type = runCatching { ContentType.parse(FileTypes.mimeOfName(f.name)) }.getOrDefault(ContentType.Application.OctetStream)
                call.respondOutputStream(type, HttpStatusCode.PartialContent, span.length) {
                    withContext(Dispatchers.IO) {
                        RandomAccessFile(f, "r").use { raf ->
                            raf.seek(span.start)
                            val buf = ByteArray(64 * 1024)
                            var left = span.length
                            while (left > 0) {
                                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) break
                                write(buf, 0, n)
                                left -= n
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun receiveFile(dir: File, name: String, part: PartData.FileItem): File? {
        val tmp = File(dir, ".${name.take(80)}.${System.nanoTime()}.fichio-part")
        if (!guard.isAllowed(tmp)) return null
        return try {
            withContext(Dispatchers.IO) { FileOutputStream(tmp).use { out -> part.provider().copyTo(out) } }
            withContext(Dispatchers.IO) {
                val finalName = PathGuard.uniqueName(name) { File(dir, it).exists() }
                val dest = File(dir, finalName)
                if (guard.isAllowed(dest) && tmp.renameTo(dest)) dest else null
            }
        } catch (e: Exception) {
            null
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun zipDir(zip: ZipOutputStream, dir: File, prefix: String) {
        val children = dir.listFiles().orEmpty()
        if (children.isEmpty() && prefix.isNotEmpty()) {
            zip.putNextEntry(ZipEntry("$prefix/")); zip.closeEntry()
        }
        for (f in children) {
            if (Files.isSymbolicLink(f.toPath()) || !guard.isAllowed(f)) continue
            val entryName = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
            if (f.isDirectory) zipDir(zip, f, entryName)
            else runCatching {
                f.inputStream().use { input ->
                    zip.putNextEntry(ZipEntry(entryName).apply { time = f.lastModified() })
                    input.copyTo(zip, 256 * 1024)
                    zip.closeEntry()
                }
            }
        }
    }

    private fun thumbnail(f: File): ByteArray? = runCatching {
        val size = Size(256, 256)
        val bmp: Bitmap = when (FileTypes.typeOfName(f.name)) {
            com.karelisio.fichio.core.model.FileType.IMAGE -> ThumbnailUtils.createImageThumbnail(f, size, null)
            com.karelisio.fichio.core.model.FileType.VIDEO -> ThumbnailUtils.createVideoThumbnail(f, size, null)
            com.karelisio.fichio.core.model.FileType.AUDIO -> ThumbnailUtils.createAudioThumbnail(f, size, null)
            else -> return null
        }
        ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bmp.recycle()
            out.toByteArray()
        }
    }.getOrNull()

    private fun setSession(call: ApplicationCall, token: String) {
        call.response.header(
            HttpHeaders.SetCookie,
            "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=${auth.sessionTtlMs / 1000}",
        )
    }

    private fun ApplicationCall.token(): String? = request.cookies[COOKIE]

    private fun ApplicationCall.client(): String = request.local.remoteAddress

    private suspend fun ApplicationCall.requireSession(): Boolean {
        if (auth.isValidToken(token())) return true
        respondJson(WebAuthResult(false, "auth"), HttpStatusCode.Unauthorized)
        return false
    }

    private suspend fun ApplicationCall.notFound() =
        respondJson(WebAuthResult(false, "notfound"), HttpStatusCode.NotFound)

    private suspend inline fun <reified T> ApplicationCall.respondJson(value: T, status: HttpStatusCode = HttpStatusCode.OK) =
        respondText(json.encodeToString(value), ContentType.Application.Json.withCharset(Charsets.UTF_8), status)

    private fun errorKey(r: TransferAuth.Result) = when (r) {
        is TransferAuth.Result.Invalid -> "invalid"
        TransferAuth.Result.Locked -> "locked"
        TransferAuth.Result.NoCode -> "nocode"
        is TransferAuth.Result.Success -> "ok"
    }

    companion object {
        const val COOKIE = "fichio_session"

        /** RFC 6266 Content-Disposition with an ASCII fallback and a UTF-8 filename*. */
        fun disposition(name: String, inline: Boolean): String {
            val ascii = name.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\') it else '_' }.joinToString("")
            val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            return "${if (inline) "inline" else "attachment"}; filename=\"$ascii\"; filename*=UTF-8''$encoded"
        }
    }
}
