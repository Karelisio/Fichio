package com.karelisio.fichio.ops

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
                    ops.message("${com.karelisio.fichio.core.util.Format.items(n)} renommé${if (n > 1) "s" else ""}")
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
                        Text("Renommer en lot", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${entries.size} éléments · ${preview.changed} modifié${if (preview.changed > 1) "s" else ""}" +
                                if (preview.problems > 0) " · ${preview.problems} problème${if (preview.problems > 1) "s" else ""}" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (preview.problems > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { apply() }, enabled = preview.canApply && !busy, shapes = ButtonDefaults.shapes()) {
                        if (busy) {
                            LoadingIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Valider")
                    }
                }
                applyError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            item(key = "affix") {
                RuleCard("Préfixe et suffixe") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            rules.prefix, { rules = rules.copy(prefix = it) }, Modifier.weight(1f),
                            label = { Text("Préfixe") }, singleLine = true, shape = MaterialTheme.shapes.medium,
                        )
                        OutlinedTextField(
                            rules.suffix, { rules = rules.copy(suffix = it) }, Modifier.weight(1f),
                            label = { Text("Suffixe") }, singleLine = true, shape = MaterialTheme.shapes.medium,
                        )
                    }
                }
            }

            item(key = "numbering") {
                RuleCard("Numérotation", checked = rules.numbering, onChecked = { rules = rules.copy(numbering = it); haptics.tick() }) {
                    AnimatedVisibility(rules.numbering, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                NumberField("Début", rules.numberStart, Modifier.weight(1f)) { rules = rules.copy(numberStart = it) }
                                NumberField("Pas", rules.numberStep, Modifier.weight(1f)) { rules = rules.copy(numberStep = it) }
                                NumberField("Chiffres", rules.numberDigits, Modifier.weight(1f), min = 1, max = 9) {
                                    rules = rules.copy(numberDigits = it)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(NumberPosition.START to "Début", NumberPosition.END to "Fin", NumberPosition.REPLACE to "Remplacer le nom")
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
                                    label = { Text("Séparateur") }, singleLine = true, shape = MaterialTheme.shapes.medium,
                                )
                            }
                        }
                    }
                }
            }

            item(key = "find") {
                RuleCard("Rechercher et remplacer") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            rules.find, { rules = rules.copy(find = it) }, Modifier.weight(1f),
                            label = { Text("Rechercher") }, singleLine = true, shape = MaterialTheme.shapes.medium,
                            isError = preview.patternError != null,
                            supportingText = preview.patternError?.let { e -> { Text(e) } },
                        )
                        OutlinedTextField(
                            rules.replace, { rules = rules.copy(replace = it) }, Modifier.weight(1f),
                            label = { Text("Remplacer par") }, singleLine = true, shape = MaterialTheme.shapes.medium,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(rules.regex, { rules = rules.copy(regex = !rules.regex); haptics.tick() }, label = { Text("Expression régulière") })
                        FilterChip(
                            rules.caseSensitive, { rules = rules.copy(caseSensitive = !rules.caseSensitive); haptics.tick() },
                            label = { Text("Respecter la casse") },
                        )
                    }
                }
            }

            item(key = "case") {
                RuleCard("Casse") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(CaseChange.NONE to "Aa", CaseChange.LOWER to "abc", CaseChange.UPPER to "ABC", CaseChange.TITLE to "Titre")
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
                RuleCard("Conserver l'extension", checked = rules.keepExtension, onChecked = { rules = rules.copy(keepExtension = it); haptics.tick() }) {}
            }

            item(key = "previewTitle") {
                Text("Aperçu", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
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
