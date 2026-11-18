package com.karelisio.fichio.core.model

import androidx.annotation.StringRes
import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import androidx.compose.runtime.Immutable

/**
 * One entry of a directory listing. Immutable and cheap to compare so that lazy lists
 * with 10 000+ items can diff and skip efficiently. [path] is the stable key.
 */
@Immutable
data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    /** Number of children for directories when known, -1 otherwise. */
    val childCount: Int = -1,
) {
    val isHidden: Boolean get() = name.startsWith(".")
    val extension: String get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()
    val nameWithoutExtension: String
        get() = if (isDirectory || !name.contains('.') || name.startsWith(".") && name.count { it == '.' } == 1) name
        else name.substringBeforeLast('.')
    val type: FileType get() = if (isDirectory) FileType.FOLDER else FileTypes.typeOf(extension)
    val mimeType: String get() = if (isDirectory) "resource/folder" else FileTypes.mimeOf(extension)
    val parentPath: String get() = path.substringBeforeLast('/', "/").ifEmpty { "/" }
}

/** Fine-grained type used for icons, previews and colours. */
enum class FileType {
    FOLDER, IMAGE, VIDEO, AUDIO, PDF, DOCUMENT, SPREADSHEET, PRESENTATION, TEXT, CODE, ARCHIVE, APK, EBOOK, FONT, OTHER;

    val category: FileCategory?
        get() = when (this) {
            IMAGE -> FileCategory.IMAGES
            VIDEO -> FileCategory.VIDEOS
            AUDIO -> FileCategory.AUDIO
            PDF, DOCUMENT, SPREADSHEET, PRESENTATION, TEXT, EBOOK -> FileCategory.DOCUMENTS
            APK -> FileCategory.APKS
            ARCHIVE -> FileCategory.ARCHIVES
            else -> null
        }
}

/** Home screen categories (the gauge segments + category grid). */
enum class FileCategory(@StringRes val labelRes: Int) {
    IMAGES(R.string.category_images),
    VIDEOS(R.string.category_videos),
    AUDIO(R.string.category_audio),
    DOCUMENTS(R.string.category_documents),
    APKS(R.string.category_apks),
    ARCHIVES(R.string.category_archives),
    DOWNLOADS(R.string.category_downloads);

    /** Localised name (read at call time so that a language change is followed). */
    val label: String get() = Strings.get(labelRes)
}

/** Sort options (per folder, persisted in Room). */
enum class SortBy { NAME, DATE, SIZE, TYPE }

enum class ViewMode { LIST, GRID, LARGE }

@Immutable
data class SortSpec(
    val by: SortBy = SortBy.NAME,
    val ascending: Boolean = true,
    val foldersFirst: Boolean = true,
)
