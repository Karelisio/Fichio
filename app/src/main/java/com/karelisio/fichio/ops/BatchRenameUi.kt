package com.karelisio.fichio.ops

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karelisio.fichio.container
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.ui.components.rememberHaptics
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BatchRenameSheet(entries: List<FileEntry>, onDismiss: () -> Unit) {
    val ops = rememberOperations()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf(BatchRenameRules()) }
    var busy by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf<String?>(null) }

    // Names already present in each parent folder (for collision detection).
    val siblings = remember { mutableStateMapOf<String, List<String>>() }
    LaunchedEffect(entries) {
        val fs = context.container.fileSystems
        withContext(Dispatchers.IO) {
            entries.map { it.parentPath }.distinct().forEach { parent ->
                val names = runCatching { fs.list(parent).map { it.name } }.getOrDefault(emptyList())
                siblings[parent] = names
            }
        }
    }
    val sources = remember(entries) { entries.map { RenameSource(it.path, it.name, it.isDirectory) } }
    val preview = remember(sources, rules, siblings.toMap()) {
        BatchRename.preview(sources, rules) { siblings[it].orEmpty() }
    }

    fun apply() {
        if (!preview.canApply) { haptics.reject(); return }
        busy = true
        scope.launch {
            ops.batchRename(preview.renames())
                .onSuccess { n ->
                    haptics.confirm()
                    ops.message(Strings.plural(R.plurals.ops_undo_renamed, n))
                    onDismiss()
                }
                .onFailure { haptics.reject(); applyError = it.message }
            busy = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            Modifier.imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.browser_batch_rename), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                        Text(
                            pluralStringResource(R.plurals.format_items, entries.size, entries.size) + " · " + pluralStringResource(R.plurals.rename_changed_count, preview.changed, preview.changed) +
                                if (preview.problems > 0) " · " + pluralStringResource(R.plurals.rename_problem_count, preview.problems, preview.problems) else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (preview.problems > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { apply() }, enabled = preview.canApply && !busy, shapes = ButtonDefaults.shapes()) {
                        if (busy) {
                            LoadingIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.rename_apply))
                    }
                }
                applyError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            item(key = "affix") {
                RuleCard(stringResource(R.string.rename_prefix_suffix)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            rules.prefix, { rules = rules.copy(prefix = it) }, Modifier.weight(1f),
                            label = { Text(stringResource(R.string.rename_prefix)) }, singleLine = true, shape = MaterialTheme.shapes.medium,
                        )
                        OutlinedTextField(
                            rules.suffix, { rules = rules.copy(suffix = it) }, Modifier.weight(1f),
                            label = { Text(stringResource(R.string.rename_suffix)) }, singleLine = true, shape = MaterialTheme.shapes.medium,
                        )
                    }
                }
            }

            item(key = "numbering") {
                RuleCard(stringResource(R.string.rename_numbering), checked = rules.numbering, onChecked = { rules = rules.copy(numbering = it); haptics.tick() }) {
                    AnimatedVisibility(rules.numbering, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                NumberField(stringResource(R.string.rename_start), rules.numberStart, Modifier.weight(1f)) { rules = rules.copy(numberStart = it) }
                                NumberField(stringResource(R.string.rename_step), rules.numberStep, Modifier.weight(1f)) { rules = rules.copy(numberStep = it) }
                                NumberField(stringResource(R.string.rename_digits), rules.numberDigits, Modifier.weight(1f), min = 1, max = 9) {
                                    rules = rules.copy(numberDigits = it)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(NumberPosition.START to stringResource(R.string.rename_start), NumberPosition.END to stringResource(R.string.rename_end), NumberPosition.REPLACE to stringResource(R.string.rename_replace_name))
                                    .forEach { (pos, label) ->
                                        ToggleButton(
                                            checked = rules.numberPosition == pos,
                                            onCheckedChange = { rules = rules.copy(numberPosition = pos); haptics.tick() },
                                            modifier = Modifier.weight(if (pos == NumberPosition.REPLACE) 1.6f else 1f),
                                        ) { Text(label, maxLines = 1) }
                                    }
                            }
                            AnimatedVisibility(rules.numberPosition != NumberPosition.REPLACE) {
                                OutlinedTextField(
                                    rules.numberSeparator, { rules = rules.copy(numberSeparator = it) }, Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.rename_separator)) }, singleLine = true, shape = MaterialTheme.shapes.medium,
                                )
                            }
                        }
                    }
                }
            }

            item(key = "find") {
                RuleCard(stringResource(R.string.rename_find_replace)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            rules.find, { rules = rules.copy(find = it) }, Modifier.weight(1f),
                            label = { Text(stringResource(R.string.shortcut_search)) }, singleLine = true, shape = MaterialTheme.shapes.medium,
                            isError = preview.patternError != null,
                            supportingText = preview.patternError?.let { e -> { Text(e) } },
                        )
                        OutlinedTextField(
                            rules.replace, { rules = rules.copy(replace = it) }, Modifier.weight(1f),
                            label = { Text(stringResource(R.string.rename_replace_with)) }, singleLine = true, shape = MaterialTheme.shapes.medium,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(rules.regex, { rules = rules.copy(regex = !rules.regex); haptics.tick() }, label = { Text(stringResource(R.string.rename_regex)) })
                        FilterChip(
                            rules.caseSensitive, { rules = rules.copy(caseSensitive = !rules.caseSensitive); haptics.tick() },
                            label = { Text(stringResource(R.string.rename_match_case)) },
                        )
                    }
                }
            }

            item(key = "case") {
                RuleCard(stringResource(R.string.rename_case)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(CaseChange.NONE to "Aa", CaseChange.LOWER to "abc", CaseChange.UPPER to "ABC", CaseChange.TITLE to stringResource(R.string.rename_title_case))
                            .forEach { (c, label) ->
                                ToggleButton(
                                    checked = rules.case == c,
                                    onCheckedChange = { rules = rules.copy(case = c); haptics.tick() },
                                    modifier = Modifier.weight(1f),
                                ) { Text(label, maxLines = 1) }
                            }
                    }
                }
            }

            item(key = "ext") {
                RuleCard(stringResource(R.string.rename_keep_extension), checked = rules.keepExtension, onChecked = { rules = rules.copy(keepExtension = it); haptics.tick() }) {}
            }

            item(key = "previewTitle") {
                Text(stringResource(R.string.rename_preview), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp))
            }
            itemsIndexed(preview.items, key = { _, it -> it.path }) { _, item -> PreviewRow(item) }
            item(key = "bottom") { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

@Composable
private fun RuleCard(
    title: String,
    checked: Boolean? = null,
    onChecked: (Boolean) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (checked != null) Switch(checked, onChecked)
            }
            content()
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, modifier: Modifier, min: Int = -99999, max: Int = 999999, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text,
        { t ->
            text = t.filter { it.isDigit() || it == '-' }.take(7)
            text.toIntOrNull()?.let { onChange(it.coerceIn(min, max)) }
        },
        modifier,
        label = { Text(label) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun PreviewRow(item: RenamePreviewItem) {
    val scheme = MaterialTheme.colorScheme
    val color by animateColorAsState(
        when (item.status) {
            RenameStatus.OK -> scheme.primary
            RenameStatus.UNCHANGED -> scheme.onSurfaceVariant
            else -> scheme.error
        },
        label = "previewColor",
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Icon(
            when (item.status) {
                RenameStatus.OK -> Icons.Rounded.CheckCircle
                RenameStatus.UNCHANGED -> Icons.Rounded.RemoveCircleOutline
                else -> Icons.Rounded.ErrorOutline
            },
            null, Modifier.size(18.dp), tint = color,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.oldName, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                textDecoration = if (item.status == RenameStatus.OK) TextDecoration.LineThrough else null,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(14.dp), tint = color)
                Spacer(Modifier.width(4.dp))
                Text(
                    item.newName, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                )
            }
            item.message?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.error) }
        }
    }
}
