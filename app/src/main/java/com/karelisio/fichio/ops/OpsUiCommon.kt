package com.karelisio.fichio.ops

import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.MaterialShapes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.graphics.shapes.RoundedPolygon
import com.karelisio.fichio.core.util.Format

/* Small shared helpers of the operations UI. */

internal fun JobKind.icon(): ImageVector = when (this) {
    JobKind.COPY -> Icons.Rounded.ContentCopy
    JobKind.MOVE -> Icons.AutoMirrored.Rounded.DriveFileMove
    JobKind.DELETE -> Icons.Rounded.DeleteForever
    JobKind.TRASH -> Icons.Rounded.AutoDelete
    JobKind.RESTORE -> Icons.Rounded.RestoreFromTrash
    JobKind.EXTRACT -> Icons.Rounded.Unarchive
    JobKind.COMPRESS -> Icons.Rounded.Archive
    JobKind.INSTALL -> Icons.Rounded.InstallMobile
    JobKind.EXPORT -> Icons.Rounded.IosShare
    JobKind.OTHER -> Icons.Rounded.Sync
}

/** Each kind of job has its own expressive shape. */
internal fun JobKind.polygon(): RoundedPolygon = when (this) {
    JobKind.COPY -> MaterialShapes.Cookie9Sided
    JobKind.MOVE -> MaterialShapes.Arrow
    JobKind.DELETE, JobKind.TRASH -> MaterialShapes.SoftBurst
    JobKind.RESTORE -> MaterialShapes.Clover4Leaf
    JobKind.EXTRACT, JobKind.COMPRESS -> MaterialShapes.Cookie4Sided
    JobKind.INSTALL -> MaterialShapes.Gem
    JobKind.EXPORT -> MaterialShapes.Sunny
    JobKind.OTHER -> MaterialShapes.Cookie6Sided
}

internal fun JobState.label(): String = when (this) {
    JobState.QUEUED -> Strings.get(R.string.ops_state_queued)
    JobState.RUNNING -> Strings.get(R.string.ops_state_running)
    JobState.PAUSED -> Strings.get(R.string.ops_state_paused)
    JobState.WAITING_CONFLICT -> Strings.get(R.string.ops_state_conflict)
    JobState.DONE -> Strings.get(R.string.ops_state_done)
    JobState.FAILED -> Strings.get(R.string.ops_state_failed)
    JobState.CANCELLED -> Strings.get(R.string.ops_state_cancelled)
}

/** "12,3 Mo sur 1,2 Go" or "4 sur 12". */
internal fun JobProgress.amountText(): String = when {
    totalBytes > 0 -> Strings.get(R.string.ops_progress_bytes, Format.size(doneBytes), Format.size(totalBytes))
    totalItems > 0 -> Strings.get(R.string.ops_progress_items, doneItems, totalItems)
    else -> ""
}

/** "≈ 2 min restantes", or null when unknown / almost done. */
internal fun JobProgress.etaText(): String? {
    if (speed <= 0 || totalBytes <= 0) return null
    val sec = (totalBytes - doneBytes).coerceAtLeast(0) / speed
    return when {
        sec < 3 -> null
        sec < 60 -> Strings.get(R.string.ops_eta_seconds, sec)
        sec < 3600 -> Strings.get(R.string.ops_eta_minutes, (sec + 30) / 60)
        else -> Strings.get(R.string.ops_eta_hours, sec / 3600, (sec % 3600) / 60)
    }
}

internal fun JobProgress.speedText(): String? = if (speed > 0) "${Format.size(speed)}/s" else null
