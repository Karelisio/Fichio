package com.karelisio.fichio.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.karelisio.fichio.domain.file.FileCategory
import java.io.File

/** Icon + accent color for a file category. */
fun FileCategory.icon(): ImageVector = when (this) {
    FileCategory.IMAGE -> Icons.Outlined.Image
    FileCategory.VIDEO -> Icons.Outlined.Movie
    FileCategory.AUDIO -> Icons.Outlined.AudioFile
    FileCategory.DOCUMENT -> Icons.Outlined.Description
    FileCategory.APK -> Icons.Outlined.Android
    FileCategory.ARCHIVE -> Icons.Outlined.Archive
    FileCategory.OTHER -> Icons.Outlined.InsertDriveFile
}

fun FileCategory.tint(): Color = when (this) {
    FileCategory.IMAGE -> Color(0xFF34A853)
    FileCategory.VIDEO -> Color(0xFFEA4335)
    FileCategory.AUDIO -> Color(0xFFFBBC04)
    FileCategory.DOCUMENT -> Color(0xFF4285F4)
    FileCategory.APK -> Color(0xFF8E24AA)
    FileCategory.ARCHIVE -> Color(0xFFF4511E)
    FileCategory.OTHER -> Color(0xFF78909C)
}

/**
 * Thumbnail or icon for a file. Images and videos use Coil; everything else
 * falls back to a tinted type icon.
 */
@Composable
fun FileThumbnail(
    file: File,
    isDirectory: Boolean,
    extension: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp
) {
    if (isDirectory) {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(cornerRadius)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxSize(0.55f)
            )
        }
        return
    }

    val category = FileCategory.fromExtension(extension)
    when (category) {
        FileCategory.IMAGE, FileCategory.VIDEO -> {
            AsyncImage(
                model = file,
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(cornerRadius))
            )
        }
        else -> {
            Box(
                modifier = modifier
                    .background(category.tint().copy(alpha = 0.14f), RoundedCornerShape(cornerRadius)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    category.icon(),
                    contentDescription = null,
                    tint = category.tint(),
                    modifier = Modifier.fillMaxSize(0.55f)
                )
            }
        }
    }
}

/** Small square icon used in list rows. */
@Composable
fun FileTypeIcon(
    isDirectory: Boolean,
    extension: String,
    size: Dp = 20.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val icon = if (isDirectory) Icons.Outlined.Folder else FileCategory.fromExtension(extension).icon()
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}
