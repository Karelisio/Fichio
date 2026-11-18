package com.karelisio.fichio.ui.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.tags.TagDots
import com.karelisio.fichio.ui.components.FileThumbnail
import com.karelisio.fichio.ui.components.FlyRegistry
import com.karelisio.fichio.ui.components.bouncy
import com.karelisio.fichio.ui.components.flySource
import com.karelisio.fichio.ui.components.flyTarget
import com.karelisio.fichio.ui.components.popOn
import com.karelisio.fichio.ui.components.pressScale
import com.karelisio.fichio.ui.navigation.sharedElementKey
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** Callbacks shared by every cell type. */
class ItemActions(
    val onClick: (FileEntry) -> Unit,
    val onLongClick: (FileEntry) -> Unit,
)

/** Child count of a folder, computed lazily off the main thread and cached. */
private val childCountCache = object : LinkedHashMap<String, Int>(512, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 4000
}

/**
 * At most this many `File.list()` in flight: flinging through a folder of 10 000 sub-folders
 * would otherwise queue thousands of directory reads on Dispatchers.IO (64 threads) and starve
 * the listing / thumbnails. Rows scrolled away before getting a permit are simply cancelled.
 */
internal const val CHILD_COUNT_PARALLELISM = 4
internal val childCountPermits = Semaphore(CHILD_COUNT_PARALLELISM)

@Composable
private fun rememberChildCount(entry: FileEntry): Int? {
    if (!entry.isDirectory || StoragePaths.isRestricted(entry.path)) return null
    val key = "${entry.path}@${entry.lastModified}"
    val v by produceState(childCountCache[key], key) {
        if (value == null) {
            value = childCountPermits.withPermit {
                withContext(Dispatchers.IO) { File(entry.path).list()?.size ?: 0 }
            }.also { childCountCache[key] = it }
        }
    }
    return v
}

@Composable
fun metaLine(entry: FileEntry): String {
    val count = rememberChildCount(entry)
    return if (entry.isDirectory) {
        listOfNotNull(count?.let { Format.items(it) }, Format.relative(entry.lastModified).takeIf { entry.lastModified > 0 }).joinToString(" · ")
    } else {
        "${Format.size(entry.size)} · ${Format.relative(entry.lastModified)}"
    }
}

/** Animated selection tick: circle pops in and the check stroke is drawn. */
@Composable
fun SelectionCheck(selected: Boolean, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val draw = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected) { if (reduce) draw.snapTo(if (selected) 1f else 0f) else draw.animateTo(if (selected) 1f else 0f, tween(260)) }
    AnimatedVisibility(selected, modifier, enter = scaleIn(bouncy()) + fadeIn(), exit = scaleOut() + fadeOut()) {
        val primary = MaterialTheme.colorScheme.primary
        val onPrimary = MaterialTheme.colorScheme.onPrimary
        Canvas(Modifier.size(24.dp)) {
            drawCircle(primary)
            val p = Path().apply {
                moveTo(size.width * 0.28f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.67f)
                lineTo(size.width * 0.73f, size.height * 0.36f)
            }
            val measure = PathMeasure().apply { setPath(p, false) }
            val seg = Path()
            measure.getSegment(0f, measure.length * draw.value, seg, true)
            drawPath(seg, onPrimary, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun selectionBackground(selected: Boolean): Color {
    val target = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val c by animateColorAsState(target, label = "selBg")
    return c
}

/** Common modifiers: click handling, press scale, spring on selection, fly registration. */
@Composable
private fun Modifier.itemBehaviour(
    entry: FileEntry,
    selected: Boolean,
    actions: ItemActions,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val reduce = LocalReduceMotion.current
    val selScale by animateFloatAsState(if (selected && !reduce) 0.97f else 1f, bouncy(), label = "selScale")
    val arrival = if (entry.isDirectory) FlyRegistry.arrivals[entry.path] else null
    return this
        .then(if (entry.isDirectory) Modifier.flyTarget("folder:${entry.path}") else Modifier)
        .flySource(entry)
        .popOn(arrival)
        .graphicsLayer { scaleX = selScale; scaleY = selScale }
        .pressScale(interaction, 0.97f)
        .combinedClickable(
            interactionSource = interaction,
            indication = androidx.compose.material3.ripple(),
            onClick = { actions.onClick(entry) },
            onLongClick = { actions.onLongClick(entry) },
        )
}

@Composable
fun FileListRow(
    entry: FileEntry,
    selected: Boolean,
    favorite: Boolean,
    actions: ItemActions,
    modifier: Modifier = Modifier,
    drawerKey: Modifier = Modifier,
    tags: List<Int> = emptyList(),
) {
    val radius by animateDpAsState(if (selected) 28.dp else 20.dp, bouncy(), label = "radius")
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .itemBehaviour(entry, selected, actions)
            .clip(RoundedCornerShape(radius))
            .background(selectionBackground(selected))
            .then(drawerKey)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            FileThumbnail(entry, size = 46.dp, modifier = Modifier.sharedElementKey("thumb-${entry.path}"))
            SelectionCheck(selected, Modifier.padding(0.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f, fill = false),
                    color = if (entry.isHidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                if (favorite) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.Star, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                }
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    TagDots(tags)
                }
            }
            Text(
                metaLine(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun FileGridCell(
    entry: FileEntry,
    selected: Boolean,
    favorite: Boolean,
    actions: ItemActions,
    modifier: Modifier = Modifier,
    drawerKey: Modifier = Modifier,
    tags: List<Int> = emptyList(),
) {
    val radius by animateDpAsState(if (selected) 32.dp else 24.dp, bouncy(), label = "radius")
    Column(
        modifier
            .padding(4.dp)
            .itemBehaviour(entry, selected, actions)
            .clip(RoundedCornerShape(radius))
            .background(if (selected) selectionBackground(true) else MaterialTheme.colorScheme.surfaceContainerLow)
            .then(drawerKey)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            FileThumbnail(entry, size = 64.dp, modifier = Modifier.sharedElementKey("thumb-${entry.path}"))
            Box(Modifier.align(Alignment.TopEnd)) { SelectionCheck(selected) }
            if (favorite) Icon(Icons.Rounded.Star, null, Modifier.align(Alignment.BottomEnd).size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
            TagDots(tags, Modifier.align(Alignment.BottomStart), size = 10.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            entry.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            minLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            if (entry.isDirectory) rememberChildCount(entry)?.let { Format.items(it) } ?: " " else Format.size(entry.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Large thumbnail tile: square preview with the name on a scrim. */
@Composable
fun FileLargeCell(
    entry: FileEntry,
    selected: Boolean,
    favorite: Boolean,
    actions: ItemActions,
    modifier: Modifier = Modifier,
    drawerKey: Modifier = Modifier,
    tags: List<Int> = emptyList(),
) {
    val radius by animateDpAsState(if (selected) 36.dp else 26.dp, bouncy(), label = "radius")
    val inset by animateDpAsState(if (selected) 6.dp else 0.dp, bouncy(), label = "inset")
    Box(
        modifier
            .padding(4.dp)
            .aspectRatio(1f)
            .itemBehaviour(entry, selected, actions)
            .padding(inset)
            .clip(RoundedCornerShape(radius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(drawerKey),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            FileThumbnail(entry, size = 72.dp, fill = true, shape = RoundedCornerShape(0.dp), modifier = Modifier.sharedElementKey("thumb-${entry.path}"))
        }
        Box(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Column {
                Text(entry.name, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(
                    if (entry.isDirectory) rememberChildCount(entry)?.let { Format.items(it) } ?: "" else Format.size(entry.size),
                    style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1,
                )
            }
        }
        TagDots(tags, Modifier.align(Alignment.TopStart).padding(10.dp), size = 12.dp)
        Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (favorite) Icon(Icons.Rounded.Star, null, Modifier.size(18.dp).background(Color.Black.copy(0.3f), CircleShape).padding(2.dp), tint = Color.White)
            SelectionCheck(selected)
        }
    }
}
