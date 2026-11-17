package com.karelisio.fichio.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.karelisio.fichio.core.model.FileCategory
import com.karelisio.fichio.core.model.FileType

@Immutable
data class TonePair(val container: Color, val content: Color)

/**
 * Dynamic per-type colours: each type has a fixed hue offset that is "harmonised" with the
 * current scheme by borrowing the lightness/saturation of the primary container. The result
 * follows the wallpaper (Material You) while keeping types distinguishable.
 */
private fun ColorScheme.tone(hueShift: Float): TonePair {
    val base = FloatArray(3).also { ColorUtils.colorToHSL(primaryContainer.toArgb(), it) }
    val onBase = FloatArray(3).also { ColorUtils.colorToHSL(onPrimaryContainer.toArgb(), it) }
    val hue = (base[0] + hueShift + 360f) % 360f
    val sat = base[1].coerceIn(0.28f, 0.75f)
    val container = Color(ColorUtils.HSLToColor(floatArrayOf(hue, sat, base[2])))
    val content = Color(ColorUtils.HSLToColor(floatArrayOf(hue, onBase[1].coerceIn(0.3f, 0.9f), onBase[2])))
    return TonePair(container, content)
}

private fun hueShiftOf(type: FileType): Float? = when (type) {
    FileType.FOLDER -> null
    FileType.IMAGE -> 150f
    FileType.VIDEO -> -40f
    FileType.AUDIO -> 60f
    FileType.PDF -> 175f
    FileType.DOCUMENT -> 0f
    FileType.SPREADSHEET -> 110f
    FileType.PRESENTATION -> -150f
    FileType.TEXT -> 20f
    FileType.CODE -> -80f
    FileType.ARCHIVE -> -110f
    FileType.APK -> 90f
    FileType.EBOOK -> 200f
    FileType.FONT -> 240f
    FileType.OTHER -> null
}

fun ColorScheme.toneFor(type: FileType): TonePair = when (type) {
    FileType.FOLDER -> TonePair(primaryContainer, onPrimaryContainer)
    FileType.OTHER -> TonePair(surfaceContainerHighest, onSurfaceVariant)
    else -> tone(hueShiftOf(type)!!)
}

fun ColorScheme.toneFor(category: FileCategory): TonePair = when (category) {
    FileCategory.IMAGES -> toneFor(FileType.IMAGE)
    FileCategory.VIDEOS -> toneFor(FileType.VIDEO)
    FileCategory.AUDIO -> toneFor(FileType.AUDIO)
    FileCategory.DOCUMENTS -> toneFor(FileType.DOCUMENT)
    FileCategory.APKS -> toneFor(FileType.APK)
    FileCategory.ARCHIVES -> toneFor(FileType.ARCHIVE)
    FileCategory.DOWNLOADS -> TonePair(tertiaryContainer, onTertiaryContainer)
}

/** Strong (non-container) colour for a category, used for gauge segments. */
fun ColorScheme.strongFor(category: FileCategory): Color = toneFor(category).content

@Composable
@ReadOnlyComposable
fun toneOf(type: FileType): TonePair = MaterialTheme.colorScheme.toneFor(type)

@Composable
@ReadOnlyComposable
fun toneOf(category: FileCategory): TonePair = MaterialTheme.colorScheme.toneFor(category)
