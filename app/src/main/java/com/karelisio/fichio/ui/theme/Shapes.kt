package com.karelisio.fichio.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.karelisio.fichio.core.model.FileCategory
import com.karelisio.fichio.core.model.FileType

val FichioShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Expressive shape per home category (gauge badges + category tiles). */
fun FileCategory.polygon(): RoundedPolygon = when (this) {
    FileCategory.IMAGES -> MaterialShapes.Flower
    FileCategory.VIDEOS -> MaterialShapes.Cookie6Sided
    FileCategory.AUDIO -> MaterialShapes.SoftBurst
    FileCategory.DOCUMENTS -> MaterialShapes.Cookie4Sided
    FileCategory.APKS -> MaterialShapes.Clover4Leaf
    FileCategory.ARCHIVES -> MaterialShapes.Gem
    FileCategory.DOWNLOADS -> MaterialShapes.Sunny
}

/** Shape of a file-type icon container. Folders get the soft "cookie" silhouette. */
fun FileType.polygon(): RoundedPolygon = when (this) {
    FileType.FOLDER -> MaterialShapes.Cookie9Sided
    FileType.IMAGE -> MaterialShapes.Flower
    FileType.VIDEO -> MaterialShapes.Cookie6Sided
    FileType.AUDIO -> MaterialShapes.SoftBurst
    FileType.PDF, FileType.DOCUMENT, FileType.TEXT, FileType.EBOOK -> MaterialShapes.Cookie4Sided
    FileType.SPREADSHEET, FileType.PRESENTATION -> MaterialShapes.Square
    FileType.CODE -> MaterialShapes.Pill
    FileType.ARCHIVE -> MaterialShapes.Gem
    FileType.APK -> MaterialShapes.Clover4Leaf
    FileType.FONT -> MaterialShapes.Arch
    FileType.OTHER -> MaterialShapes.Circle
}

/** Special folders get their own silhouettes so they stand out in listings. */
fun specialFolderPolygon(name: String): RoundedPolygon? = when (name.lowercase()) {
    "download", "downloads" -> MaterialShapes.Sunny
    "dcim", "pictures", "camera", "screenshots" -> MaterialShapes.Flower
    "movies", "videos" -> MaterialShapes.Cookie6Sided
    "music", "podcasts", "ringtones", "notifications", "alarms", "recordings" -> MaterialShapes.SoftBurst
    "documents" -> MaterialShapes.Cookie4Sided
    "android" -> MaterialShapes.Clover4Leaf
    else -> null
}
