package com.karelisio.fichio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.karelisio.fichio.core.model.FileCategory
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.ui.theme.polygon
import com.karelisio.fichio.ui.theme.specialFolderPolygon
import com.karelisio.fichio.ui.theme.toneOf

fun FileType.icon(): ImageVector = when (this) {
    FileType.FOLDER -> Icons.Rounded.Folder
    FileType.IMAGE -> Icons.Rounded.Image
    FileType.VIDEO -> Icons.Rounded.Movie
    FileType.AUDIO -> Icons.Rounded.MusicNote
    FileType.PDF -> Icons.Rounded.PictureAsPdf
    FileType.DOCUMENT -> Icons.Rounded.Description
    FileType.SPREADSHEET -> Icons.Rounded.TableChart
    FileType.PRESENTATION -> Icons.Rounded.Slideshow
    FileType.TEXT -> Icons.AutoMirrored.Rounded.TextSnippet
    FileType.CODE -> Icons.Rounded.Code
    FileType.ARCHIVE -> Icons.Rounded.Archive
    FileType.APK -> Icons.Rounded.Android
    FileType.EBOOK -> Icons.Rounded.MenuBook
    FileType.FONT -> Icons.Rounded.FontDownload
    FileType.OTHER -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

fun FileCategory.icon(): ImageVector = when (this) {
    FileCategory.IMAGES -> Icons.Rounded.Image
    FileCategory.VIDEOS -> Icons.Rounded.VideoLibrary
    FileCategory.AUDIO -> Icons.Rounded.Audiotrack
    FileCategory.DOCUMENTS -> Icons.Rounded.Description
    FileCategory.APKS -> Icons.Rounded.Android
    FileCategory.ARCHIVES -> Icons.Rounded.Archive
    FileCategory.DOWNLOADS -> Icons.Rounded.Download
}

private fun folderIcon(name: String): ImageVector = when (name.lowercase()) {
    "download", "downloads" -> Icons.Rounded.Download
    "dcim", "camera" -> Icons.Rounded.PhotoCamera
    "pictures", "screenshots" -> Icons.Rounded.Image
    "movies", "videos" -> Icons.Rounded.Movie
    "music", "podcasts", "ringtones", "recordings" -> Icons.Rounded.MusicNote
    "documents" -> Icons.Rounded.Description
    "android" -> Icons.Rounded.Android
    else -> Icons.Rounded.Folder
}

/** The expressive-shaped icon of a type (or special folder) in its dynamic colour. */
@Composable
fun FileIcon(
    type: FileType,
    modifier: Modifier = Modifier,
    name: String = "",
    size: Dp = 44.dp,
) {
    val tone = toneOf(type)
    val polygon = if (type == FileType.FOLDER) specialFolderPolygon(name) ?: type.polygon() else type.polygon()
    val shape: Shape = polygon.toShape()
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(tone.container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (type == FileType.FOLDER) folderIcon(name) else type.icon(),
            contentDescription = null,
            tint = tone.content,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/**
 * Thumbnail when the type supports one (image, video frame, audio cover, APK icon), falling
 * back to [FileIcon] while loading or on error.
 */
@Composable
fun FileThumbnail(
    entry: FileEntry,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    shape: Shape? = null,
    fill: Boolean = false,
) {
    val type = entry.type
    if (!Thumb.supports(type)) {
        FileIcon(type, modifier, entry.name, size)
        return
    }
    var failed by remember(entry.path) { mutableStateOf(false) }
    var loaded by remember(entry.path) { mutableStateOf(false) }
    val clipShape = shape ?: if (type == FileType.APK) type.polygon().toShape() else androidx.compose.material3.MaterialTheme.shapes.small
    Box(if (fill) modifier.fillMaxSize() else modifier.size(size), contentAlignment = Alignment.Center) {
        if (!loaded || failed) FileIcon(type, Modifier.then(if (fill) Modifier.size(size) else Modifier), entry.name, size)
        if (!failed) {
            AsyncImage(
                model = Thumb(entry),
                contentDescription = null,
                contentScale = if (type == FileType.APK) ContentScale.Fit else ContentScale.Crop,
                onSuccess = { loaded = true },
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().clip(clipShape),
            )
        }
    }
}
