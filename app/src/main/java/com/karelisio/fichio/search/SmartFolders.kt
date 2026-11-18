package com.karelisio.fichio.search

import androidx.annotation.StringRes
import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import com.karelisio.fichio.core.model.FileType

/** Virtual folders computed from the name index (home "Dossiers intelligents"). */
enum class SmartFolder(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    SCREENSHOTS(R.string.smart_screenshots, R.string.smart_screenshots_desc),
    WHATSAPP(R.string.smart_whatsapp, R.string.smart_whatsapp_desc),
    RECENT_DOWNLOADS(R.string.smart_recent_downloads, R.string.smart_recent_downloads_desc),
    LARGE_VIDEOS(R.string.smart_large_videos, R.string.smart_large_videos_desc),
    CAMERA(R.string.smart_camera, R.string.smart_camera_desc);

    val label: String get() = Strings.get(labelRes)
    val description: String get() = Strings.get(descriptionRes)
}

/**
 * Pure SQL builder for [SmartFolder]s. Every condition uses an index of file_index: `parent =`
 * (screenshots, camera), a `path` range on the primary key (WhatsApp, downloads) or type/size.
 */
object SmartFolders {
    const val LARGE_VIDEO_BYTES = 100L * 1024 * 1024
    const val RECENT_DAYS = 7
    private const val DAY_MS = 24L * 3600 * 1000
    private const val COLUMNS = "f.path, f.name, f.parent, f.extension, f.type, f.isDirectory, f.size, f.modified"

    /** Media folders of WhatsApp (and Business), new scoped location then legacy one, relative to a volume root. */
    val WHATSAPP_MEDIA = listOf(
        "Android/media/com.whatsapp/WhatsApp/Media",
        "WhatsApp/Media",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media",
        "WhatsApp Business/Media",
    )
    private val SCREENSHOT_DIRS = listOf("DCIM/Screenshots", "Pictures/Screenshots", "Pictures/Screenshot", "DCIM/Screenshot")
    private const val CAMERA_DIR = "DCIM/Camera"

    fun whatsAppRoots(volumeRoots: Collection<String>): List<String> =
        volumeRoots.map { it.trimEnd('/') }.distinct().flatMap { r -> WHATSAPP_MEDIA.map { "$r/$it" } }

    /** Whether [path] is media received through WhatsApp: below a Media folder, not in a `Sent` folder. */
    fun isWhatsAppReceived(path: String, volumeRoots: Collection<String>): Boolean {
        val root = whatsAppRoots(volumeRoots).firstOrNull { path.startsWith("$it/") } ?: return false
        val rel = path.removePrefix("$root/")
        val segs = rel.split('/')
        if (segs.size < 2) return false // files directly in Media/ are not chat media
        if (segs.dropLast(1).any { it.equals("Sent", ignoreCase = true) }) return false
        return segs.last() != ".nomedia"
    }

    /** Upper bound for a `path >= prefix AND path < upper` range covering everything under `prefix/`. */
    fun rangeEnd(dir: String): String = dir.trimEnd('/') + "0" // '0' is the character right after '/'

    private fun condition(kind: SmartFolder, roots: List<String>, downloads: String, now: Long, args: MutableList<Any>): String {
        val vols = roots.map { it.trimEnd('/') }.distinct()
        fun parentIn(rel: List<String>): String {
            val dirs = vols.flatMap { r -> rel.map { "$r/$it" } }
            args.addAll(dirs)
            return "f.parent IN (" + dirs.joinToString(",") { "?" } + ")"
        }
        fun under(dirs: List<String>): String = dirs.joinToString(" OR ", "(", ")") { d ->
            args += d.trimEnd('/') + "/"
            args += rangeEnd(d)
            "(f.path >= ? AND f.path < ?)"
        }
        val media = "f.type IN ('${FileType.IMAGE.name}', '${FileType.VIDEO.name}')"
        return when (kind) {
            SmartFolder.SCREENSHOTS -> "${parentIn(SCREENSHOT_DIRS)} AND $media"
            SmartFolder.CAMERA -> "${parentIn(listOf(CAMERA_DIR))} AND $media"
            SmartFolder.WHATSAPP -> {
                val wa = whatsAppRoots(vols)
                val c = under(wa)
                args.addAll(wa)
                "$c AND f.parent NOT IN (${wa.joinToString(",") { "?" }}) AND f.path NOT LIKE '%/Sent/%' AND f.name != '.nomedia'"
            }
            SmartFolder.RECENT_DOWNLOADS -> {
                val c = under(listOf(downloads))
                args += now - RECENT_DAYS * DAY_MS
                "$c AND f.modified >= ?"
            }
            SmartFolder.LARGE_VIDEOS -> {
                args += LARGE_VIDEO_BYTES
                "f.type = '${FileType.VIDEO.name}' AND f.size >= ?"
            }
        }
    }

    /** Files of one smart folder, most recent first (largest first for big videos). */
    fun listQuery(kind: SmartFolder, roots: List<String>, downloads: String, now: Long = System.currentTimeMillis(), limit: Int = 3000): BuiltQuery {
        val args = ArrayList<Any>()
        val cond = condition(kind, roots, downloads, now, args)
        val order = if (kind == SmartFolder.LARGE_VIDEOS) "f.size DESC" else "f.modified DESC"
        return BuiltQuery("SELECT $COLUMNS FROM file_index f WHERE f.isDirectory = 0 AND ($cond) ORDER BY $order LIMIT $limit", args)
    }

    /** One row (kind, count, size) per smart folder, in [SmartFolder] order. */
    fun countQuery(roots: List<String>, downloads: String, now: Long = System.currentTimeMillis()): BuiltQuery {
        val args = ArrayList<Any>()
        val sql = SmartFolder.entries.joinToString(" UNION ALL ") { kind ->
            val cond = condition(kind, roots, downloads, now, args)
            "SELECT '${kind.name}' AS kind, COUNT(*) AS count, COALESCE(SUM(f.size), 0) AS size FROM file_index f WHERE f.isDirectory = 0 AND ($cond)"
        }
        return BuiltQuery(sql, args)
    }
}
