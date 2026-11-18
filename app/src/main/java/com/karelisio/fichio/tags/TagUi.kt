package com.karelisio.fichio.tags

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karelisio.fichio.container
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.ui.components.bouncy
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tag colour of a palette slot, harmonised with the scheme: the hue is pulled a little towards
 * the primary colour and lightness follows light/dark mode. Slot 6 is the neutral "grey" tag.
 */
fun ColorScheme.tagColor(slot: Int): Color {
    val dark = surface.luminance() < 0.5f
    if (slot !in TagPalette.HUES.indices) return if (dark) outline.copy(alpha = 1f) else onSurfaceVariant
    val p = FloatArray(3).also { ColorUtils.colorToHSL(primary.toArgb(), it) }
    val hue = TagPalette.harmonizedHue(TagPalette.HUES[slot], p[0])
    val sat = if (dark) 0.62f else 0.66f
    val light = if (dark) 0.68f else if (slot == 2) 0.42f else 0.47f
    return Color(ColorUtils.HSLToColor(floatArrayOf(hue, sat, light)))
}

@Composable
@ReadOnlyComposable
fun tagColor(slot: Int): Color = MaterialTheme.colorScheme.tagColor(slot)

/** Small overlapping colour dots (rows, cells, results). */
@Composable
fun TagDots(slots: List<Int>, modifier: Modifier = Modifier, size: Dp = 9.dp) {
    if (slots.isEmpty()) return
    val ring = MaterialTheme.colorScheme.surface
    Row(modifier) {
        slots.forEachIndexed { i, s ->
            Box(
                Modifier
                    .offset(x = -(size * 0.3f) * i)
                    .size(size + 2.dp)
                    .clip(CircleShape)
                    .background(ring)
                    .padding(1.dp)
                    .clip(CircleShape)
                    .background(tagColor(s)),
            )
        }
    }
}

/** A tag chip content: dot + name (+ count). */
@Composable
fun TagLabel(tag: Tag, showCount: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(tagColor(tag.slot)))
        Spacer(Modifier.width(8.dp))
        Text(tag.displayName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (showCount) {
            Spacer(Modifier.width(6.dp))
            Text("${tag.count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Bottom sheet to (un)tag [paths]: tapping a tag adds it to every item, or removes it when they
 * all carry it already. Each tag can be renamed.
 */
@Composable
fun TagAssignSheet(paths: List<String>, onDismiss: () -> Unit) {
    val repo = LocalContext.current.container.tags
    val tags by repo.tags.collectAsStateWithLifecycle(emptyList())
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var carried by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var version by remember { mutableStateOf(0) }
    var renaming by remember { mutableStateOf<Tag?>(null) }
    LaunchedEffect(paths, version) {
        carried = withContext(Dispatchers.IO) {
            runCatching { repo.tagsOf(paths) }.getOrDefault(emptyMap())
                .values.flatten().groupingBy { it }.eachCount()
        }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text("Étiquettes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 12.dp))
            Text(
                if (paths.size == 1) "Touchez une couleur pour l'ajouter ou la retirer." else "Pour ${Format.items(paths.size)} : touchez pour ajouter ou retirer.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            tags.forEach { tag ->
                val n = carried[tag.id] ?: 0
                val state = when { n == 0 -> 0; n >= paths.size -> 2; else -> 1 }
                TagRow(
                    tag = tag,
                    state = state,
                    onToggle = {
                        scope.launch {
                            if (state == 2) { repo.unassign(tag.id, paths); haptics.tick() } else { repo.assign(tag.id, paths); haptics.confirm() }
                            version++
                        }
                    },
                    onRename = { renaming = tag },
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Terminé") }
            }
        }
    }
    renaming?.let { t ->
        RenameTagDialog(t, onDismiss = { renaming = null }, onRename = { name ->
            scope.launch { repo.rename(t.id, name) }
            renaming = null
        })
    }
}

/** state: 0 = none, 1 = some of the items, 2 = all. */
@Composable
private fun TagRow(tag: Tag, state: Int, onToggle: () -> Unit, onRename: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val color = tagColor(tag.slot)
    val scale by animateFloatAsState(if (state == 2 && !reduce) 1.12f else 1f, bouncy(), label = "tagScale")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(if (state == 0) Color.Transparent else color)
                .border(2.5.dp, color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(state, transitionSpec = { (scaleIn(bouncy()) + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "tagState") { s ->
                when (s) {
                    2 -> Icon(Icons.Rounded.Check, "Attribuée", tint = Color.White, modifier = Modifier.size(18.dp))
                    1 -> Icon(Icons.Rounded.Remove, "Attribuée en partie", tint = Color.White, modifier = Modifier.size(18.dp))
                    else -> Spacer(Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(tag.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (tag.count > 0) Text(Format.items(tag.count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRename) { Icon(Icons.Rounded.Edit, "Renommer l'étiquette", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun RenameTagDialog(tag: Tag, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(tag.name.ifBlank { tag.displayName }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Box(Modifier.size(24.dp).clip(CircleShape).background(tagColor(tag.slot))) },
        title = { Text("Renommer l'étiquette") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(TagsRepository.MAX_NAME) },
                singleLine = true,
                label = { Text("Nom") },
                placeholder = { Text(TagPalette.defaultName(tag.slot)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRename(name) }),
            )
        },
        confirmButton = { TextButton(onClick = { onRename(name) }) { Text("Renommer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
