package com.karelisio.fichio.ops

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.karelisio.fichio.R
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karelisio.fichio.container
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.ui.components.EmptyState
import com.karelisio.fichio.ui.components.FileThumbnail
import com.karelisio.fichio.ui.components.bouncy
import com.karelisio.fichio.ui.components.popOn
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.theme.LocalReduceMotion

@Composable
internal fun rememberOperations(): OperationManager {
    val context = LocalContext.current
    return remember(context) { context.container.operations }
}

// ===================================================================== Host + conflict dialog

/** Global host (placed once at the app root): conflict dialog for running jobs. */
@Composable
fun OperationsHost() {
    val ops = rememberOperations()
    val request by ops.conflict.collectAsStateWithLifecycle()
    request?.let { r ->
        ConflictDialog(
            request = r,
            onChoice = { choice, all -> ops.resolveConflict(choice, all) },
            onCancelJob = { ops.cancel(r.jobId) },
        )
    }
}

@Composable
private fun ConflictDialog(
    request: ConflictRequest,
    onChoice: (ConflictChoice, Boolean) -> Unit,
    onCancelJob: () -> Unit,
) {
    val haptics = rememberHaptics()
    val reduce = LocalReduceMotion.current
    var applyToAll by rememberSaveable(request.jobId) { mutableStateOf(false) }
    LaunchedEffect(request) { haptics.reject() }

    fun answer(c: ConflictChoice) {
        haptics.confirm()
        onChoice(c, applyToAll)
    }

    BasicAlertDialog(
        onDismissRequest = { answer(ConflictChoice.SKIP) },
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().heightIn(max = 640.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Slowly spinning cookie badge.
                val spin = if (reduce) 0f else rememberInfiniteTransition(label = "spin").animateFloat(
                    0f, 360f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "spin",
                ).value
                Box(
                    Modifier.size(64.dp).popOn(request).graphicsLayer { rotationZ = spin }
                        .clip(MaterialShapes.Cookie12Sided.toShape())
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.SwapHoriz, null,
                        Modifier.size(30.dp).graphicsLayer { rotationZ = -spin },
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.ops_conflict_exists, request.existing.name),
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.ops_conflict_in, request.existing.parentPath.substringAfterLast('/')),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConflictSide(stringResource(R.string.ops_conflict_new), request.source, request.existing, Modifier.weight(1f))
                    ConflictSide(stringResource(R.string.ops_conflict_existing), request.existing, request.source, Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .toggleable(applyToAll, role = Role.Checkbox) { applyToAll = it; haptics.tick() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = applyToAll, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Text(
                        if (request.remainingHint > 0) stringResource(R.string.ops_apply_all_count, request.remainingHint) else stringResource(R.string.ops_apply_all),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { answer(ConflictChoice.REPLACE) },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text(stringResource(R.string.ops_replace)) }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { answer(ConflictChoice.KEEP_BOTH) },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text(stringResource(R.string.ops_keep_both)) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { answer(ConflictChoice.SKIP) },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text(stringResource(R.string.ops_skip)) }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { haptics.click(); onCancelJob() }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.ops_cancel_operation), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ConflictSide(label: String, entry: FileEntry, other: FileEntry, modifier: Modifier = Modifier) {
    val newer = entry.lastModified > other.lastModified
    val bigger = !entry.isDirectory && entry.size > other.size
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            FileThumbnail(entry, size = 72.dp, shape = MaterialTheme.shapes.medium)
            Spacer(Modifier.height(8.dp))
            Text(
                if (entry.isDirectory) stringResource(R.string.common_folder) else Format.size(entry.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (bigger) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                Format.date(entry.lastModified),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            if (newer || bigger) {
                Spacer(Modifier.height(6.dp))
                Text(
                    listOfNotNull(if (newer) stringResource(R.string.ops_newer) else null, if (bigger) stringResource(R.string.ops_bigger) else null).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

// ===================================================================== Indicator pill

private fun aggregateFraction(active: List<Job>): Float? {
    if (active.isEmpty() || active.all { it.progress.indeterminate }) return null
    val total = active.sumOf { it.progress.totalBytes }
    return if (total > 0) (active.sumOf { it.progress.doneBytes }.toFloat() / total).coerceIn(0f, 1f)
    else active.map { it.progress.fraction }.average().toFloat()
}

/** Compact animated pill showing running jobs; hidden when idle. */
@Composable
fun OperationsIndicator(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ops = rememberOperations()
    val jobs by ops.jobs.collectAsStateWithLifecycle()
    val active = remember(jobs) { jobs.filterNot { it.state.isFinished } }
    val finishedCount = remember(jobs) { jobs.count { it.state.isFinished } }
    // Keep showing the last content while the exit animation plays.
    val lastActive = remember { arrayOf(active) }
    if (active.isNotEmpty()) lastActive[0] = active
    val shown = lastActive[0]
    val reduce = LocalReduceMotion.current
    val haptics = rememberHaptics()

    AnimatedVisibility(
        visible = active.isNotEmpty(),
        enter = if (reduce) fadeIn() else scaleIn(bouncy(), initialScale = 0.4f) + fadeIn(),
        exit = if (reduce) fadeOut() else scaleOut(targetScale = 0.6f) + fadeOut(),
        modifier = modifier,
    ) {
        val fraction = aggregateFraction(shown)
        val animated by animateFloatAsState(fraction ?: 0f, if (reduce) snap() else bouncy(), label = "pill")
        val paused = shown.all { it.state == JobState.PAUSED }
        val waiting = shown.any { it.state == JobState.WAITING_CONFLICT }
        Surface(
            onClick = { haptics.click(); onClick() },
            shape = CircleShape,
            color = if (waiting) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (waiting) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 6.dp,
            modifier = Modifier.popOn(finishedCount, 0.12f),
        ) {
            Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    if (fraction == null) CircularWavyProgressIndicator(Modifier.size(34.dp))
                    else CircularWavyProgressIndicator(
                        progress = { animated },
                        modifier = Modifier.size(34.dp),
                        amplitude = { if (paused || reduce) 0f else 1f },
                    )
                    Icon(
                        if (paused) Icons.Rounded.Pause else shown.first().kind.icon(), null,
                        Modifier.size(15.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                AnimatedContent(
                    targetState = when {
                        waiting -> stringResource(R.string.ops_state_conflict)
                        paused -> stringResource(R.string.ops_state_paused)
                        fraction == null -> stringResource(R.string.ops_state_running)
                        else -> "${(fraction * 100).toInt()} %"
                    },
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "pillText",
                ) { Text(it, style = MaterialTheme.typography.labelLarge) }
                if (shown.size > 1) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedContent(shown.size, label = "count") {
                            Text("$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
    }
}

// ===================================================================== Queue sheet

/** Bottom sheet listing the queue with pause / resume / cancel. */
@Composable
fun OperationsSheet(onDismiss: () -> Unit) {
    val ops = rememberOperations()
    val jobs by ops.jobs.collectAsStateWithLifecycle()
    val ordered = remember(jobs) {
        jobs.filterNot { it.state.isFinished } + jobs.filter { it.state.isFinished }.sortedByDescending { it.id }
    }
    val haptics = rememberHaptics()
    val running = jobs.count { !it.state.isFinished }
    val finished = jobs.size - running

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ops_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(
                    listOfNotNull(
                        if (running > 0) stringResource(R.string.ops_running_count, running) else null,
                        if (finished > 0) pluralStringResource(R.plurals.ops_finished_count, finished, finished) else null,
                    ).joinToString(" · ").ifEmpty { stringResource(R.string.ops_queue_empty) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(finished > 0, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                TextButton(onClick = { haptics.click(); ops.clearFinished() }, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.DoneAll, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ops_clear_finished))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (ordered.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.DoneAll,
                title = stringResource(R.string.ops_none),
                message = stringResource(R.string.ops_none_desc),
                modifier = Modifier.navigationBarsPadding(),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.navigationBarsPadding(),
            ) {
                items(ordered, key = { it.id }) { job ->
                    JobCard(
                        job,
                        onPause = { haptics.click(); ops.pause(job.id) },
                        onResume = { haptics.click(); ops.resume(job.id) },
                        onCancel = { haptics.reject(); ops.cancel(job.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun JobCard(job: Job, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (job.state) {
        JobState.FAILED -> scheme.errorContainer to scheme.onErrorContainer
        JobState.DONE -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        JobState.CANCELLED -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        JobState.WAITING_CONFLICT -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = scheme.surfaceContainerHigh) {
        Column(Modifier.animateContentSize(if (reduce) snap() else bouncy()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).popOn(job.state.isFinished).clip(job.kind.polygon().toShape()).background(container),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(job.state, transitionSpec = { scaleIn(bouncy()) togetherWith scaleOut() }, label = "icon") { s ->
                        Icon(
                            when (s) {
                                JobState.DONE -> Icons.Rounded.Check
                                JobState.FAILED -> Icons.Rounded.ErrorOutline
                                JobState.CANCELLED -> Icons.Rounded.Close
                                else -> job.kind.icon()
                            },
                            null, Modifier.size(22.dp), tint = content,
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(job.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = when {
                        job.state == JobState.FAILED -> job.error ?: stringResource(R.string.ops_state_failed)
                        job.state.isFinished -> buildString {
                            append(job.state.label())
                            if (job.skipped > 0) append(" · " + pluralStringResource(R.plurals.ops_skipped_count, job.skipped, job.skipped))
                            if (job.failed > 0) append(" · " + pluralStringResource(R.plurals.ops_failed_count, job.failed, job.failed))
                            job.error?.let { append(" · $it") }
                        }
                        job.state == JobState.RUNNING && job.progress.currentName.isNotEmpty() -> job.progress.currentName
                        else -> job.state.label() + (job.destination?.let { " · " + stringResource(R.string.ops_to_folder, it.substringAfterLast('/')) } ?: "")
                    }
                    Text(
                        sub, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (job.state == JobState.FAILED) scheme.error else scheme.onSurfaceVariant,
                    )
                }
                if (!job.state.isFinished) {
                    if (job.state == JobState.RUNNING || job.state == JobState.PAUSED) {
                        FilledTonalIconToggleButton(
                            checked = job.state == JobState.PAUSED,
                            onCheckedChange = { if (it) onPause() else onResume() },
                            shapes = IconButtonDefaults.toggleableShapes(),
                        ) {
                            Icon(
                                if (job.state == JobState.PAUSED) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                if (job.state == JobState.PAUSED) stringResource(R.string.ops_resume) else stringResource(R.string.ops_pause),
                            )
                        }
                    }
                    IconButton(onClick = onCancel, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.common_cancel))
                    }
                }
            }
            AnimatedVisibility(!job.state.isFinished, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    val p = job.progress
                    if (p.indeterminate || job.state == JobState.QUEUED) {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        val animated by animateFloatAsState(p.fraction, if (reduce) snap() else bouncy(), label = "job")
                        LinearWavyProgressIndicator(
                            progress = { animated },
                            modifier = Modifier.fillMaxWidth(),
                            amplitude = { if (job.state == JobState.RUNNING && !reduce) 1f else 0f },
                        )
                    }
                    val stats = listOfNotNull(
                        p.amountText().ifEmpty { null },
                        p.speedText().takeIf { job.state == JobState.RUNNING },
                        p.etaText().takeIf { job.state == JobState.RUNNING },
                    ).joinToString(" · ")
                    if (stats.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(stats, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
