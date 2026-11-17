package com.karelisio.fichio.core.model

import android.webkit.MimeTypeMap

/** Extension → type / MIME lookup. Pure and fast (hash lookups only). */
object FileTypes {
    private val types: Map<String, FileType> = buildMap {
        fun put(t: FileType, vararg ext: String) = ext.forEach { put(it, t) }
        put(FileType.IMAGE, "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "ico", "dng", "raw", "tif", "tiff")
        put(FileType.VIDEO, "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "ts", "flv", "wmv", "mpg", "mpeg")
        put(FileType.AUDIO, "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr", "mid", "midi", "oga")
        put(FileType.PDF, "pdf")
        put(FileType.DOCUMENT, "doc", "docx", "odt", "rtf", "pages")
        put(FileType.SPREADSHEET, "xls", "xlsx", "ods", "csv", "tsv", "numbers")
        put(FileType.PRESENTATION, "ppt", "pptx", "odp", "key")
        put(FileType.TEXT, "txt", "md", "markdown", "log", "ini", "cfg", "conf", "properties", "srt", "vtt", "nfo")
        put(
            FileType.CODE, "kt", "kts", "java", "js", "mjs", "ts", "tsx", "jsx", "py", "c", "h", "cpp", "hpp", "cc", "cs",
            "go", "rs", "swift", "rb", "php", "sh", "bash", "zsh", "bat", "ps1", "html", "htm", "css", "scss", "xml",
            "json", "yaml", "yml", "toml", "gradle", "sql", "lua", "dart", "vue", "svelte", "r", "pl", "smali",
        )
        put(FileType.ARCHIVE, "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "zst", "lz", "lzma", "jar", "cab", "iso")
        put(FileType.APK, "apk", "apks", "xapk", "apkm")
        put(FileType.EBOOK, "epub", "mobi", "azw3", "fb2", "djvu", "cbz", "cbr")
        put(FileType.FONT, "ttf", "otf", "woff", "woff2")
    }.let { map -> map.toMutableMap().also { it.remove("ts"); it["ts"] = FileType.VIDEO } }

    private val extraMime = mapOf(
        "kt" to "text/x-kotlin", "kts" to "text/x-kotlin", "md" to "text/markdown", "log" to "text/plain",
        "apks" to "application/vnd.android.apks", "xapk" to "application/vnd.android.xapk",
        "apk" to "application/vnd.android.package-archive", "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar", "heic" to "image/heic", "avif" to "image/avif", "opus" to "audio/opus",
        "json" to "application/json", "yaml" to "text/yaml", "yml" to "text/yaml", "toml" to "text/plain",
    )

    fun typeOf(extension: String): FileType = types[extension.lowercase()] ?: FileType.OTHER

    fun typeOfName(name: String): FileType = typeOf(name.substringAfterLast('.', ""))

    fun mimeOf(extension: String): String {
        val ext = extension.lowercase()
        extraMime[ext]?.let { return it }
        val fromMap = runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }.getOrNull()
        if (fromMap != null) return fromMap
        return when (typeOf(ext)) {
            FileType.IMAGE -> "image/*"
            FileType.VIDEO -> "video/*"
            FileType.AUDIO -> "audio/*"
            FileType.TEXT, FileType.CODE -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    fun mimeOfName(name: String): String = mimeOf(name.substringAfterLast('.', ""))

    /** Whether Fichio can show this type in its own text viewer. */
    fun isTextLike(type: FileType) = type == FileType.TEXT || type == FileType.CODE

    fun extensionsOf(category: FileCategory): Set<String> =
        types.filterValues { it.category == category }.keys
}
