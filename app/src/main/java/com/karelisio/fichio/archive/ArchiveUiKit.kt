package com.karelisio.fichio.archive

import androidx.compose.ui.res.stringResource
import com.karelisio.fichio.R
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.ops.Job
import com.karelisio.fichio.ops.JobKind
import com.karelisio.fichio.ops.TaskScope
import com.karelisio.fichio.ui.components.FileIcon
import com.karelisio.fichio.ui.components.cascadeIn
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/* Small UI building blocks shared by the archive and APK screens. */

/** "photos.tar.gz" → "photos". */
fun archiveBaseName(name: String): String {
    val lower = name.lowercase()
    val multi = listOf(".tar.gz", ".tar.bz2", ".tar.xz").firstOrNull { lower.endsWith(it) }
    val base = if (multi != null) name.dropLast(multi.length) else name.substringBeforeLast('.', name)
    return base.ifBlank { name }
}

/**
 * Starts a long task through the operations module. Returns null if it could not be queued
 * (the caller shows a message).
 */
fun android.content.Context.launchTask(
    kind: JobKind,
    title: String,
    sources: List<String>,
    destination: String?,
    block: suspend TaskScope.() -> Unit,
): Long? = runCatching { container.operations.runTask(kind, title, sources, destination, block) }.getOrNull()

/** Runs [onFinished] once when the job [jobId] reaches a final state. */
@Composable
fun JobCompletionEffect(jobId: Long?, onFinished: suspend (Job) -> Unit) {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onFinished)
    LaunchedEffect(jobId) {
        if (jobId == null) return@LaunchedEffect
        val job = runCatching {
            context.container.operations.jobs
                .map { list -> list.firstOrNull { it.id == jobId } }
                .first { it != null && it.state.isFinished }
        }.getOrNull() ?: return@LaunchedEffect
        callback(job)
    }
}

/** Masked password field with a show/hide toggle. */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        leadingIcon = { Icon(Icons.Rounded.Lock, null) },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                AnimatedContent(visible, transitionSpec = { (scaleIn() + fadeIn()) togetherWith fadeOut() }, label = "eye") { v ->
                    Icon(
                        if (v) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (v) stringResource(R.string.archive_hide_password) else stringResource(R.string.archive_show_password),
                    )
                }
            }
        },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }, onNext = { onDone() }),
        supportingText = supportingText?.let { { Text(it) } },
        modifier = modifier.fillMaxWidth(),
    )
}

/** Badge with an expressive shape that pops and turns in when it appears. */
@Composable
fun ShapeBadge(
    polygon: androidx.graphics.shapes.RoundedPolygon,
    container: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 64.dp,
    content: @Composable () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val anim = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow)) }
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                val p = anim.value
                scaleX = 0.6f + 0.4f * p; scaleY = scaleX
                rotationZ = (1f - p) * -40f
            }
            .clip(polygon.toShape())
            .background(container),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Password prompt for an encrypted archive. */
@Composable
fun PasswordDialog(
    archiveName: String,
    wrong: Boolean,
    busy: Boolean,
    onSubmit: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val haptics = rememberHaptics()
    LaunchedEffect(wrong) { if (wrong) haptics.reject() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            ShapeBadge(MaterialShapes.Cookie7Sided, MaterialTheme.colorScheme.tertiaryContainer, size = 56.dp) {
                Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        },
        title = { Text(stringResource(R.string.archive_protected)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.archive_enter_password_of, archiveName), style = MaterialTheme.typography.bodyMedium)
                PasswordField(
                    value = text,
                    onValueChange = { text = it },
                    label = stringResource(R.string.archive_password),
                    isError = wrong,
                    supportingText = if (wrong) stringResource(R.string.archive_err_wrong_password) else null,
                    onDone = { if (text.isNotEmpty() && !busy) onSubmit(text.toCharArray()) },
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(text.toCharArray()) }, enabled = text.isNotEmpty() && !busy) {
                if (busy) LoadingIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text(stringResource(R.string.archive_unlock))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * Compact folder picker: volumes at the top level, then sub-folders (Android/data and obb
 * excluded, they cannot be written with java.io).
 */
@Composable
fun FolderPickerDialog(initial: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val volumes by context.container.volumes.volumes.let { flow ->
        produceState(flow.value) { flow.collect { value = it } }
    }
    var current by rememberSaveable { mutableStateOf(initial?.takeIf { File(it).isDirectory }) }
    val children by produceState(emptyList<File>(), current) {
        value = withContext(Dispatchers.IO) {
            current?.let { dir ->
                File(dir).listFiles { f -> f.isDirectory && !f.name.startsWith(".") && !StoragePaths.isRestricted(f.path) }
                    ?.sortedBy { it.name.lowercase() }
            }.orEmpty()
        }
    }
    val haptics = rememberHaptics()
    val volumeRoot = volumes.firstOrNull { v -> current != null && (current == v.path) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current != null) {
                    IconButton(onClick = {
                        haptics.tick()
                        current = if (volumeRoot != null || volumes.any { it.path == current }) null
                        else File(current!!).parent
                    }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.archive_parent_folder)) }
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    current?.let { c -> volumes.firstOrNull { it.path == c }?.label ?: File(c).name } ?: stringResource(R.string.archive_choose_folder),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp).fillMaxWidth()) {
                if (current == null) {
                    itemsIndexed(volumes, key = { _, v -> v.path }) { i, v ->
                        PickerRow(
                            label = v.label,
                            icon = {
                                Icon(
                                    when { v.isUsb -> Icons.Rounded.Usb; v.isRemovable -> Icons.Rounded.SdCard; else -> Icons.Rounded.Smartphone },
                                    null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            modifier = Modifier.cascadeIn(i, "vol"),
                        ) { haptics.tick(); current = v.path }
                    }
                } else {
                    if (children.isEmpty()) item {
                        Text(
                            stringResource(R.string.archive_no_subfolders), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                        )
                    }
                    itemsIndexed(children, key = { _, f -> f.path }) { i, f ->
                        PickerRow(
                            label = f.name,
                            icon = { FileIcon(FileType.FOLDER, name = f.name, size = 36.dp) },
                            modifier = Modifier.cascadeIn(i, current),
                        ) { haptics.tick(); current = f.path }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { current?.let { haptics.confirm(); onPick(it) } }, enabled = current != null) { Text(stringResource(R.string.archive_choose_here)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun PickerRow(label: String, icon: @Composable () -> Unit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        icon()
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Pretty path for UI: "Stockage interne › Download › Photos". */
@Composable
fun displayPath(path: String): String {
    val context = LocalContext.current
    val volumes = context.container.volumes.volumes.value
    return StoragePaths.segments(path, volumes).joinToString(" › ") { it.second }
}
