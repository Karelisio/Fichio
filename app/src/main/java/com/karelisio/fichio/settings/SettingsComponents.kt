package com.karelisio.fichio.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.karelisio.fichio.ui.components.SectionHeader
import com.karelisio.fichio.ui.components.bouncy
import com.karelisio.fichio.ui.components.cascadeIn
import com.karelisio.fichio.ui.components.rememberHaptics
import kotlin.math.roundToInt

/** One row of a segmented settings group; receives the shapes for its position. */
typealias SettingRow = @Composable (shapes: ListItemShapes) -> Unit

/** A titled block of segmented list items (first/last rows get the large corners). */
@Composable
fun SettingsGroup(title: String, index: Int, rows: List<SettingRow>) {
    Column(Modifier.fillMaxWidth().cascadeIn(index)) {
        SectionHeader(title)
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            rows.forEachIndexed { i, row -> row(ListItemDefaults.segmentedShapes(i, rows.size)) }
        }
    }
}

/** Icon inside a small expressive shape. */
@Composable
fun SettingIcon(icon: ImageVector, shape: RoundedPolygon = MaterialShapes.Cookie9Sided, tint: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(40.dp).clip(shape.toShape()).background(if (tint) cs.primary else cs.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (tint) cs.onPrimary else cs.onPrimaryContainer)
    }
}

fun switchRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
): SettingRow = { shapes ->
    val haptics = rememberHaptics()
    SegmentedListItem(
        checked = checked,
        onCheckedChange = { haptics.tick(); onChange(it) },
        shapes = shapes,
        enabled = enabled,
        leadingContent = { SettingIcon(icon) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked, onCheckedChange = null, enabled = enabled,
                thumbContent = if (checked) { { Icon(Icons.Rounded.Check, null, Modifier.size(SwitchDefaults.IconSize)) } } else null,
            )
        },
    ) { Text(title) }
}

fun clickRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
): SettingRow = { shapes ->
    val haptics = rememberHaptics()
    SegmentedListItem(
        onClick = { haptics.click(); onClick() },
        shapes = shapes,
        enabled = enabled,
        leadingContent = { SettingIcon(icon) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = trailing,
    ) { Text(title) }
}

/** Static row (no click), e.g. "Version". */
fun infoRow(icon: ImageVector, title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null): SettingRow = { shapes ->
    SegmentedListItem(
        shapes = shapes,
        leadingContent = { SettingIcon(icon) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = trailing,
    ) { Text(title) }
}

/** Row with arbitrary content under the title (segmented buttons, sliders…). */
fun customRow(icon: ImageVector, title: String, subtitle: String? = null, body: @Composable () -> Unit): SettingRow = { shapes ->
    SegmentedListItem(
        shapes = shapes,
        leadingContent = { SettingIcon(icon) },
        supportingContent = {
            Column(Modifier.fillMaxWidth()) {
                subtitle?.let { Text(it) }
                Box(Modifier.padding(top = 8.dp)) { body() }
            }
        },
    ) { Text(title) }
}

/**
 * Slider snapping to [values]; [label] formats the value shown next to the title.
 * The setting is written when the finger is lifted.
 */
fun stepSliderRow(
    icon: ImageVector,
    title: String,
    values: List<Int>,
    current: Int,
    label: (Int) -> String,
    onChange: (Int) -> Unit,
): SettingRow = { shapes ->
    val haptics = rememberHaptics()
    val startIndex = values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 0
    var pos by remember(current) { mutableFloatStateOf(startIndex.toFloat()) }
    val shown = values[pos.roundToInt().coerceIn(0, values.lastIndex)]
    val scale by animateFloatAsState(if (pos.roundToInt() != startIndex) 1.12f else 1f, bouncy(), label = "valueScale")
    SegmentedListItem(
        shapes = shapes,
        leadingContent = { SettingIcon(icon) },
        trailingContent = {
            Text(
                label(shown), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
            )
        },
        supportingContent = {
            Slider(
                value = pos,
                onValueChange = {
                    if (it.roundToInt() != pos.roundToInt()) haptics.segment()
                    pos = it
                },
                onValueChangeFinished = { onChange(values[pos.roundToInt().coerceIn(0, values.lastIndex)]) },
                valueRange = 0f..values.lastIndex.toFloat(),
                steps = (values.size - 2).coerceAtLeast(0),
            )
        },
    ) { Text(title) }
}

/** Continuous integer slider (e.g. trash delay 1–90 days). */
fun intSliderRow(
    icon: ImageVector,
    title: String,
    range: IntRange,
    current: Int,
    enabled: Boolean = true,
    label: (Int) -> String,
    onChange: (Int) -> Unit,
): SettingRow = { shapes ->
    val haptics = rememberHaptics()
    var v by remember(current) { mutableFloatStateOf(current.coerceIn(range).toFloat()) }
    SegmentedListItem(
        shapes = shapes,
        enabled = enabled,
        leadingContent = { SettingIcon(icon) },
        trailingContent = {
            Text(label(v.roundToInt()), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = v,
                    enabled = enabled,
                    onValueChange = {
                        if (it.roundToInt() != v.roundToInt()) haptics.tick()
                        v = it
                    },
                    onValueChangeFinished = { onChange(v.roundToInt()) },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                )
            }
        },
    ) { Text(title) }
}
