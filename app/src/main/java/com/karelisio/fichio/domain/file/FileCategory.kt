package com.karelisio.fichio.domain.file

/** High-level file categories used for home tiles, coloring and filtering. */
enum class FileCategory {
    IMAGE, VIDEO, AUDIO, DOCUMENT, APK, ARCHIVE, OTHER;

    companion object {
        private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "avif", "raw", "tiff")
        private val videoExt = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "ts", "m4v", "mpg", "mpeg", "wmv")
        private val audioExt = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma", "mid", "midi", "amr")
        private val documentExt = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "csv",
            "odt", "ods", "odp", "epub", "mobi", "html", "htm", "xml", "json", "log", "ini", "conf"
        )
        private val archiveExt = setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "lz4", "zst", "iso", "tgz", "tbz2", "apks", "jar")
        private val apkExt = setOf("apk", "apks", "xapk")

        fun fromExtension(ext: String): FileCategory = when (ext.lowercase()) {
            in imageExt -> IMAGE
            in videoExt -> VIDEO
            in audioExt -> AUDIO
            in documentExt -> DOCUMENT
            in apkExt -> APK
            in archiveExt -> ARCHIVE
            else -> OTHER
        }

        fun isImage(ext: String) = ext.lowercase() in imageExt
        fun isVideo(ext: String) = ext.lowercase() in videoExt
        fun isAudio(ext: String) = ext.lowercase() in audioExt
        fun isDocument(ext: String) = ext.lowercase() in documentExt
        fun isApk(ext: String) = ext.lowercase() in apkExt
        fun isArchive(ext: String) = ext.lowercase() in archiveExt
    }
}

/** Extension of a path without the dot, lowercase. */
fun extensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0 && dot < name.length - 1) name.substring(dot + 1).lowercase() else ""
}
