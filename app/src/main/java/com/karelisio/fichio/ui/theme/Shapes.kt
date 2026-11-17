package com.karelisio.fichio.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Varied shapes used across the app: category icons, folder cards and the
 * space gauge each get a distinct silhouette (Material 3 Expressive).
 */
val FichioShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

/** Category icon tiles: a softly squircle-ish shape. */
val CategoryTileShape = RoundedCornerShape(22.dp)

/** Folder cards: strongly rounded "squircle". */
val FolderCardShape = RoundedCornerShape(24.dp)

/** The space gauge ring. */
val GaugeShape = CircleShape
