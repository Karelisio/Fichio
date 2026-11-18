package com.karelisio.fichio.ops

import com.karelisio.fichio.core.util.Strings
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.karelisio.fichio.R
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import com.karelisio.fichio.container
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.ops.engine.SizeStats
import com.karelisio.fichio.ui.components.AnimatedCounter
import com.karelisio.fichio.ui.components.FileThumbnail
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import com.karelisio.fichio.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

private fun FileType.label(): String = when (this) {
    FileType.FOLDER -> Strings.get(R.string.type_folder)
    FileType.IMAGE -> Strings.get(R.string.type_image)
    FileType.VIDEO -> Strings.get(R.string.type_video)
    FileType.AUDIO -> Strings.get(R.string.type_audio)
    FileType.PDF -> Strings.get(R.string.type_pdf)
    FileType.DOCUMENT -> Strings.get(R.string.type_document)
    FileType.SPREADSHEET -> Strings.get(R.string.type_spreadsheet)
    FileType.PRESENTATION -> Strings.get(R.string.type_presentation)
    FileType.TEXT -> Strings.get(R.string.type_text)
    FileType.CODE -> Strings.get(R.string.type_code)
    FileType.ARCHIVE -> Strings.get(R.string.type_archive)
    FileType.APK -> Strings.get(R.string.type_apk)
    FileType.EBOOK -> Strings.get(R.string.type_ebook)
    FileType.FONT -> Strings.get(R.string.type_font)
    FileType.OTHER -> Strings.get(R.string.type_other)
}

/** Size, dates, path, item count; MD5 / SHA-256 on demand. */
@Composable
fun PropertiesSheet(entries: List<FileEntry>, onDismiss: () -> Unit) {
    if (entries.isEmpty()) return
    val context = LocalContext.current
    val ops = rememberOperations()
    val haptics = rememberHaptics()
    val single = entries.singleOrNull()

    val stats by produceState(SizeStats(), entries) {
        ops.engine.measure(entries.map { it.path }).collect { value = it }
    }
    // Creation / access dates when available (local java.nio only).
    val extraDates by produceState<Pair<Long, Long>?>(null, single) {
        val s = single ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (context.container.fileSystems.backendFor(s.path).id != "local") null
                else Files.readAttributes(Paths.get(s.path), BasicFileAttributes::class.java)
                    .let { it.creationTime().toMillis() to it.lastAccessTime().toMillis() }
            }.getOrNull()
        }
    }

    fun copy(label: String, text: String) {
        context.getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText(label, text))
        haptics.confirm()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (single != null) FileThumbnail(single, size = 64.dp)
                else FileThumbnail(entries.first(), size = 64.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        single?.name ?: Format.items(entries.size),
                        style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        single?.let { if (it.isDirectory) stringResource(R.string.type_folder) else stringResource(R.string.props_type_mime, it.type.label(), it.mimeType) }
                            ?: stringResource(R.string.props_multiple),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Big animated size.
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val (num, unit) = Format.sizeParts(stats.bytes)
                        Row(verticalAlignment = Alignment.Bottom) {
                            AnimatedCounter(num, style = MaterialTheme.typography.displaySmall)
                            Spacer(Modifier.width(6.dp))
                            Text(unit, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        val showCounts = entries.size > 1 || single?.isDirectory == true
                        if (showCounts) {
                            val files = stats.files
                            val folders = (stats.folders - entries.count { it.isDirectory }).coerceAtLeast(0)
                            AnimatedCounter(
                                pluralStringResource(R.plurals.common_files_count, files, files) + " · " + pluralStringResource(R.plurals.common_folders_count, folders, folders),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    AnimatedContent(stats.complete, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "measuring") { done ->
                        if (!done) ContainedLoadingIndicator(Modifier.size(44.dp)) else Spacer(Modifier.size(44.dp))
                    }
                }
            }

            val path = single?.path ?: entries.first().parentPath
            InfoRow(if (single != null) Strings.get(R.string.props_location) else Strings.get(R.string.type_folder), path, onCopy = { copy(Strings.get(R.string.props_path), path) })
            single?.let { s ->
                InfoRow(stringResource(R.string.props_modified), Format.date(s.lastModified))
                extraDates?.let { (created, accessed) ->
                    if (created > 0) InfoRow(stringResource(R.string.props_created), Format.date(created))
                    if (accessed > 0) InfoRow(stringResource(R.string.props_accessed), Format.date(accessed))
                }
                if (!s.isDirectory) HashSection(s, onCopy = { label, v -> copy(label, v) })
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, onCopy: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
        }
        if (onCopy != null) {
            IconButton(onClick = onCopy, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.common_copy)) }
        }
    }
}

@Composable
private fun HashSection(entry: FileEntry, onCopy: (String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduce = LocalReduceMotion.current
    var hashes by remember(entry.path) { mutableStateOf<Map<HashAlgorithm, String>?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var running by remember { mutableStateOf<CoroutineJob?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.props_hashes), style = MaterialTheme.typography.titleSmall)
            when {
                hashes != null -> hashes!!.forEach { (algo, value) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(algo.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            SelectionContainer { Text(value, style = MonoStyle) }
                        }
                        IconButton(onClick = { onCopy(algo.label, value) }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.ContentCopy, stringResource(R.string.props_copy_hash, algo.label))
                        }
                    }
                }
                running != null -> {
                    val animated by animateFloatAsState(progress, label = "hash")
                    LinearWavyProgressIndicator(progress = { animated }, Modifier.fillMaxWidth(), amplitude = { if (reduce) 0f else 1f })
                    Text(stringResource(R.string.props_computing, (progress * 100).toInt()), style = MaterialTheme.typography.labelMedium)
                }
                else -> {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    FilledTonalButton(
                        onClick = {
                            error = null
                            running = scope.launch {
                                runCatching {
                                    val b = context.container.fileSystems.backendFor(entry.path)
                                    Hashing.compute(b, entry.path) { progress = it }
                                }.onSuccess { hashes = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }
                                running = null
                            }
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Rounded.Fingerprint, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.props_compute))
                    }
                }
            }
            Spacer(Modifier.height(0.dp))
        }
    }
}
