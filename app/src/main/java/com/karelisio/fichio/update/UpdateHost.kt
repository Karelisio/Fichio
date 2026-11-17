package com.karelisio.fichio.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.onboarding.MorphingBadge
import com.karelisio.fichio.ui.components.AnimatedCounter
import com.karelisio.fichio.ui.components.bouncy
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.theme.LocalReduceMotion

/** Global host: checks GitHub releases/latest (≤ every 6 h) and shows the update sheet. */
@Composable
fun UpdateHost() {
    val context = LocalContext.current
    val state by UpdateManager.state.collectAsStateWithLifecycle()
    val visible by UpdateManager.sheetVisible.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { UpdateManager.checkNow(context, force = false) }

    if (!visible) return
    val current = state
    // Nothing to show while merely checking / up to date: settings displays those inline.
    if (current is UpdateState.Idle || current is UpdateState.Checking || current is UpdateState.UpToDate ||
        (current is UpdateState.Error && current.update == null)
    ) {
        if (current !is UpdateState.Checking) UpdateManager.dismiss()
        return
    }
    val busy = current is UpdateState.Downloading || current is UpdateState.Installing
    // The sheet state keeps its first lambda: read the latest value through an updated state.
    val busyNow by rememberUpdatedState(busy)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busyNow })
    ModalBottomSheet(
        onDismissRequest = { if (!busy) UpdateManager.dismiss() },
        sheetState = sheetState,
    ) {
        UpdateSheetContent(current)
    }
}

@Composable
private fun UpdateSheetContent(state: UpdateState) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val update = when (state) {
        is UpdateState.Available -> state.update
        is UpdateState.Downloading -> state.update
        is UpdateState.Installing -> state.update
        is UpdateState.Error -> state.update
        else -> null
    } ?: return
    val cs = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MorphingBadge(
                listOf(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny, MaterialShapes.Cookie6Sided),
                if (state is UpdateState.Error) cs.errorContainer else cs.primaryContainer,
                Modifier.size(64.dp),
            ) {
                Icon(
                    if (state is UpdateState.Error) Icons.Rounded.ErrorOutline else Icons.Rounded.SystemUpdate, null,
                    tint = if (state is UpdateState.Error) cs.onErrorContainer else cs.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Nouvelle version", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                Text("Fichio ${update.version}", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Installée : ${UpdateManager.currentVersion}" + if (update.apkSize > 0) " · ${Format.size(update.apkSize)}" else "",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Surface(color = cs.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                if (update.changelog.isBlank()) {
                    Text("Aucune note de version.", color = cs.onSurfaceVariant)
                } else {
                    MarkdownText(update.changelog)
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        AnimatedContent(
            targetState = state::class,
            transitionSpec = { (fadeIn() + scaleIn(bouncy(), initialScale = 0.92f)) togetherWith fadeOut() },
            label = "updateActions",
        ) { kind ->
            when (kind) {
                UpdateState.Downloading::class -> DownloadProgress(state as? UpdateState.Downloading)
                UpdateState.Installing::class -> Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Installation en cours…", style = MaterialTheme.typography.titleMedium)
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state is UpdateState.Error) {
                        Text(state.message, color = cs.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        onClick = { haptics.confirm(); UpdateManager.downloadAndInstall(context, update) },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Rounded.SystemUpdate, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state is UpdateState.Error) "Réessayer" else "Mettre à jour")
                    }
                    if (!SelfInstaller.canInstall(context)) {
                        Text(
                            "Fichio doit être autorisé à installer des applis : le réglage s'ouvrira.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { haptics.click(); UpdateManager.dismiss() }, modifier = Modifier.weight(1f), shapes = ButtonDefaults.shapes()) {
                            Text("Plus tard")
                        }
                        OutlinedButton(onClick = { haptics.click(); UpdateManager.skip(context, update.version) }, modifier = Modifier.weight(1f), shapes = ButtonDefaults.shapes()) {
                            Text("Ignorer cette version")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(state: UpdateState.Downloading?) {
    state ?: return
    val reduce = LocalReduceMotion.current
    val fraction by animateFloatAsState(state.fraction, if (reduce) androidx.compose.animation.core.snap() else bouncy(), label = "dl")
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedCounter("${(state.fraction * 100).toInt()}", style = MaterialTheme.typography.displaySmall.copy(color = cs.primary))
            Text(" %", style = MaterialTheme.typography.titleLarge, color = cs.primary, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { UpdateManager.cancelDownload() }) { Text("Annuler") }
        }
        Spacer(Modifier.height(8.dp))
        LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(14.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            buildString {
                append(Format.size(state.read))
                if (state.total > 0) append(" sur ${Format.size(state.total)}")
                if (state.bytesPerSecond > 0) append(" · ${Format.size(state.bytesPerSecond)}/s")
            },
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
    }
}

/** Renders the small Markdown subset used in release notes. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { SimpleMarkdown.parse(markdown) }
    val cs = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> Text(
                    rich(b.text),
                    style = if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    color = if (b.level <= 2) cs.onSurface else cs.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is MdBlock.Bullet -> Row(Modifier.padding(start = (b.depth * 14).dp)) {
                    Text("•", color = cs.primary, modifier = Modifier.width(16.dp))
                    Text(rich(b.text), style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Paragraph -> Text(rich(b.text), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun rich(text: String): AnnotatedString = buildAnnotatedString {
    SimpleMarkdown.boldRuns(text.replace("`", "")).forEach { (s, bold) ->
        if (bold) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s) } else append(s)
    }
}
