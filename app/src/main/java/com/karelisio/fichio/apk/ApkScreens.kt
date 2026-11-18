package com.karelisio.fichio.apk

import com.karelisio.fichio.ui.components.selectionSemantics
import androidx.compose.foundation.layout.heightIn
import com.karelisio.fichio.core.util.Strings
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.karelisio.fichio.R
import android.content.ClipData
import android.content.ClipboardManager
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.karelisio.fichio.archive.JobCompletionEffect
import com.karelisio.fichio.archive.ArchivePaths
import com.karelisio.fichio.archive.asProgressCallbacks
import com.karelisio.fichio.archive.launchTask
import com.karelisio.fichio.container
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.ops.ConflictChoice
import com.karelisio.fichio.ops.JobKind
import com.karelisio.fichio.ops.JobState
import com.karelisio.fichio.ui.components.AnimatedCounter
import com.karelisio.fichio.ui.components.BackButton
import com.karelisio.fichio.ui.components.EmptyState
import com.karelisio.fichio.ui.components.SectionHeader
import com.karelisio.fichio.ui.components.bouncy
import com.karelisio.fichio.ui.components.cascadeIn
import com.karelisio.fichio.ui.components.pressScale
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/* ======================================================================================
 * APK details
 * ==================================================================================== */

@Composable
fun ApkInfoScreen(path: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    var reloadKey by remember { mutableIntStateOf(0) }
    var askUnknownSources by remember { mutableStateOf(false) }
    var pendingInstall by remember { mutableStateOf(false) }

    val result by produceState<Result<ApkDetails>?>(null, path, reloadKey) {
        value = runCatching { ApkInspector.inspect(context, path) }
    }
    val details = result?.getOrNull()
    val installState by ApkInstaller.state.collectAsState()
    val ownState = installState.takeIf { details != null && it.packageName() in setOf(null, details.packageName) }

    // A finished install from an earlier screen should not leak in here.
    LaunchedEffect(Unit) {
        val s = ApkInstaller.state.value
        if (s is InstallState.Success || s is InstallState.Failure) ApkInstaller.reset()
    }
    LaunchedEffect(ownState) {
        when (ownState) {
            is InstallState.Success -> { haptics.confirm(); reloadKey++ }
            is InstallState.Failure -> haptics.reject()
            else -> Unit
        }
    }

    fun startInstall(d: ApkDetails) {
        if (!ApkInstaller.canInstall(context)) {
            askUnknownSources = true
            return
        }
        haptics.click()
        val sources = d.installSources()
        val id = context.launchTask(JobKind.INSTALL, Strings.get(R.string.apk_installing_title, d.label), listOf(d.path), null) {
            ApkInstaller.installSources(context, sources, d.packageName, onProgress = { done, total, name -> progress(done, total, name) }, checkpoint = { checkpoint() })
        }
        // Operations module unavailable: install directly, the screen still shows progress.
        if (id == null) context.container.appScope.launch { runCatching { ApkInstaller.installSources(context, sources, d.packageName) } }
    }

    // Back from the "unknown sources" settings: continue if now allowed.
    LifecycleResumeEffect(pendingInstall) {
        if (pendingInstall && ApkInstaller.canInstall(context)) {
            pendingInstall = false
            details?.let(::startInstall)
        }
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.apk_package_title)) }, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (details != null) InstallBar(details, ownState, onInstall = { startInstall(details) }, onOpen = {
                ApkInstaller.launchIntent(context, details.packageName)?.let { runCatching { context.startActivity(it) } }
            })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                result == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingIndicator(Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.apk_parsing), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                details == null -> EmptyState(
                    Icons.Rounded.ErrorOutline, stringResource(R.string.apk_unreadable), Modifier.align(Alignment.Center),
                    message = result?.exceptionOrNull()?.message ?: stringResource(R.string.apk_not_valid),
                )
                else -> ApkDetailsContent(details)
            }
        }
    }

    if (askUnknownSources) {
        AlertDialog(
            onDismissRequest = { askUnknownSources = false },
            icon = { Icon(Icons.Rounded.Shield, null) },
            title = { Text(stringResource(R.string.apk_allow_install)) },
            text = { Text(stringResource(R.string.apk_allow_install_body)) },
            confirmButton = {
                Button(onClick = {
                    askUnknownSources = false
                    pendingInstall = true
                    ApkInstaller.openUnknownSourcesSettings(context)
                }) { Text(stringResource(R.string.perm_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { askUnknownSources = false }) { Text(stringResource(R.string.update_later)) } },
        )
    }
}

private fun InstallState.packageName(): String? = when (this) {
    InstallState.Idle -> null
    is InstallState.Writing -> packageName
    is InstallState.WaitingUser -> packageName
    is InstallState.Success -> packageName
    is InstallState.Failure -> packageName
}

@Composable
private fun InstallBar(details: ApkDetails, state: InstallState?, onInstall: () -> Unit, onOpen: () -> Unit) {
    val relation = details.installed.relation
    val blocked = details.installed.signatureMatches == false || !details.compatible
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            AnimatedContent(state, contentKey = { it?.javaClass }, label = "installState") { s ->
                when (s) {
                    is InstallState.Writing -> Column {
                        Text(stringResource(R.string.apk_preparing), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        val p by animateFloatAsState(s.progress, label = "p")
                        LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                    }
                    is InstallState.WaitingUser -> Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.apk_waiting_confirm))
                    }
                    is InstallState.Failure -> Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp))
                        Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    is InstallState.Success -> Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.apk_installed), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                    }
                    else -> Spacer(Modifier.height(0.dp))
                }
            }
            val busy = state is InstallState.Writing || state is InstallState.WaitingUser
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state is InstallState.Success || (relation != InstallRelation.NOT_INSTALLED && !busy)) {
                    OutlinedButton(onClick = onOpen, modifier = Modifier.heightIn(min = 56.dp), shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.common_open))
                    }
                }
                if (state !is InstallState.Success) {
                    Button(
                        onClick = onInstall,
                        enabled = !busy && !blocked,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    ) {
                        Icon(if (relation == InstallRelation.UPDATE) Icons.Rounded.SystemUpdate else Icons.Rounded.InstallMobile, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (relation) {
                                InstallRelation.NOT_INSTALLED -> stringResource(R.string.apk_install)
                                InstallRelation.UPDATE -> stringResource(R.string.update_update)
                                InstallRelation.SAME_VERSION -> stringResource(R.string.apk_reinstall)
                                InstallRelation.DOWNGRADE -> stringResource(R.string.apk_downgrade)
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApkDetailsContent(d: ApkDetails) {
    val icon: ImageBitmap? = remember(d.icon) { runCatching { d.icon?.toBitmap(256, 256)?.asImageBitmap() }.getOrNull() }
    var permissionsOpen by rememberSaveable { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "hero") {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BreathingIcon(icon, 136.dp)
                Spacer(Modifier.height(16.dp))
                Text(d.label, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(d.packageName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoPill("v${d.versionName ?: "?"} (${d.versionCode})", Icons.Rounded.Verified, 0)
                    InfoPill(stringResource(R.string.apk_android_min_pill, ApkFormat.androidVersion(d.minSdk)), Icons.Rounded.Android, 1)
                    InfoPill(stringResource(R.string.apk_target_api, d.targetSdk), null, 2)
                    InfoPill(Format.size(d.fileSize), null, 3)
                    if (d.isBundle) InfoPill("${d.splits.size} APK", Icons.Rounded.Extension, 4)
                }
            }
        }
        item(key = "state") { InstalledStateCard(d, Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) }
        item(key = "compat") { CompatibilityCard(d, Modifier.padding(horizontal = 16.dp)) }
        item(key = "sig") { SignatureCard(d, Modifier.padding(16.dp)) }

        item(key = "permHeader") {
            val rotation by animateFloatAsState(if (permissionsOpen) 180f else 0f, bouncy(), label = "chev")
            Surface(
                onClick = { permissionsOpen = !permissionsOpen },
                shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.apk_permissions_count, d.permissions.size), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (d.dangerousCount > 0) pluralStringResource(R.plurals.apk_sensitive_permissions, d.dangerousCount, d.dangerousCount) else stringResource(R.string.apk_no_sensitive),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (d.dangerousCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Rounded.ExpandMore, if (permissionsOpen) stringResource(R.string.apk_collapse) else stringResource(R.string.apk_expand), Modifier.graphicsLayer { rotationZ = rotation })
                }
            }
        }
        if (permissionsOpen) {
            itemsIndexed(d.permissions, key = { _, p -> "perm:" + p.name }) { i, p ->
                PermissionRow(p, Modifier.animateItem().cascadeIn(i, "perm"))
            }
            if (d.permissions.isEmpty()) item(key = "noperm") {
                Text(stringResource(R.string.apk_no_permissions), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (d.isBundle) {
            item(key = "splitsHeader") { SectionHeader(stringResource(R.string.apk_package_content)) }
            itemsIndexed(d.splits, key = { _, s -> "split:" + s.entryName }) { i, s ->
                SplitRow(s, s in d.selectedSplits, Modifier.cascadeIn(i, "split"))
            }
        }
    }
}

/** App icon on an expressive shape that "breathes" in when the screen appears. */
@Composable
private fun BreathingIcon(icon: ImageBitmap?, size: Dp) {
    val reduce = LocalReduceMotion.current
    val enter = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduce) return@LaunchedEffect
        enter.animateTo(1.08f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow))
        enter.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow))
    }
    val spin = if (reduce) null else rememberInfiniteTransition(label = "halo")
    val haloRotation = spin?.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "rot")
    Box(Modifier.size(size + 28.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size + 28.dp)
                .graphicsLayer {
                    val e = enter.value
                    scaleX = 0.5f + 0.5f * e; scaleY = scaleX
                    rotationZ = (haloRotation?.value ?: 0f) + (1f - e) * 90f
                    alpha = e.coerceIn(0f, 1f)
                }
                .clip(MaterialShapes.Cookie12Sided.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer),
        )
        Box(
            Modifier.size(size * 0.72f).graphicsLayer {
                val e = enter.value
                scaleX = 0.3f + 0.7f * e; scaleY = scaleX
            },
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) Image(icon, null, Modifier.fillMaxSize())
            else Icon(Icons.Rounded.Android, null, Modifier.fillMaxSize(0.7f), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun InfoPill(text: String, icon: ImageVector?, index: Int) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.cascadeIn(index, "pills")) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun InstalledStateCard(d: ApkDetails, modifier: Modifier = Modifier) {
    val st = d.installed
    val (icon, title, subtitle, warn) = when {
        st.signatureMatches == false -> Quad(Icons.Rounded.Warning, stringResource(R.string.apk_sig_different), Strings.get(R.string.apk_sig_different_body, st.versionName), true)
        st.relation == InstallRelation.NOT_INSTALLED -> Quad(Icons.Rounded.InstallMobile, Strings.get(R.string.apk_not_installed), Strings.get(R.string.apk_not_installed_body), false)
        st.relation == InstallRelation.UPDATE -> Quad(Icons.Rounded.SystemUpdate, Strings.get(R.string.apk_update_available), Strings.get(R.string.apk_update_body, st.versionName, st.versionCode, d.versionName, d.versionCode), false)
        st.relation == InstallRelation.DOWNGRADE -> Quad(Icons.Rounded.Warning, Strings.get(R.string.apk_downgrade_title), Strings.get(R.string.apk_downgrade_body, st.versionName), true)
        else -> Quad(Icons.Rounded.CheckCircle, stringResource(R.string.apk_already_installed), Strings.get(R.string.apk_same_version, st.versionName), false)
    }
    val container = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val content = if (warn) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = container) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(MaterialShapes.Cookie6Sided.toShape()).background(content.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = content)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = content)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = content)
            }
        }
    }
}

private data class Quad(val icon: ImageVector, val title: String, val subtitle: String, val warn: Boolean)

@Composable
private fun CompatibilityCard(d: ApkDetails, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.apk_compatibility), style = MaterialTheme.typography.titleMedium)
            CompatRow(
                d.sdkCompatible, 0,
                stringResource(R.string.apk_android_min, ApkFormat.androidVersion(d.minSdk), d.minSdk),
                stringResource(R.string.apk_device_android, ApkFormat.androidVersion(d.deviceSdk), d.deviceSdk),
            )
            CompatRow(
                d.abiCompatible, 1,
                if (d.nativeAbis.isEmpty()) stringResource(R.string.apk_no_native) else stringResource(R.string.apk_architectures, d.nativeAbis.joinToString(", ")),
                stringResource(R.string.apk_device_abis, d.deviceAbis.joinToString(", ")),
                icon = Icons.Rounded.Memory,
            )
            if (d.isBundle) {
                CompatRow(
                    d.selectedSplits.any { it.kind == SplitKind.BASE }, 2,
                    stringResource(R.string.apk_splits_selected, d.selectedSplits.size, d.splits.size),
                    stringResource(R.string.apk_splits_reason),
                    icon = Icons.Rounded.Extension,
                )
            }
        }
    }
}

/** Row whose check / cross pops in with a short stagger. */
@Composable
private fun CompatRow(ok: Boolean, index: Int, title: String, subtitle: String, icon: ImageVector? = null) {
    val reduce = LocalReduceMotion.current
    val pop = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(ok) {
        if (reduce) return@LaunchedEffect
        pop.snapTo(0f)
        delay(180L + index * 140L)
        pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Icon(
                if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel, null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp).graphicsLayer {
                    scaleX = pop.value; scaleY = pop.value
                    rotationZ = (1f - pop.value) * -60f
                },
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SignatureCard(d: ApkDetails, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Verified, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.apk_signature), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                d.certificates.firstOrNull()?.let { cert ->
                    IconButton(onClick = {
                        haptics.confirm()
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("SHA-256", cert.sha256))
                        Toast.makeText(context, Strings.get(R.string.apk_fingerprint_copied), Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.apk_copy_fingerprint)) }
                }
            }
            if (d.certificates.isEmpty()) {
                Text(stringResource(R.string.apk_no_signature), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            d.certificates.forEach { cert ->
                Spacer(Modifier.height(6.dp))
                Text("SHA-256", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ApkFormat.fingerprintLines(cert.sha256).forEach {
                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                cert.subject?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (cert.validUntil > 0) Text(
                    stringResource(R.string.apk_valid_until, Format.shortDate(cert.validUntil)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(p: ApkPermission, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (p.dangerous) Icons.Rounded.Warning else Icons.Rounded.Shield, null,
            tint = if (p.dangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(p.label, style = MaterialTheme.typography.bodyLarge)
            p.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(p.name, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun SplitRow(s: SplitInfo, selected: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RemoveCircleOutline, null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(s.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        if (s.size > 0) Text(Format.size(s.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/* ======================================================================================
 * Installed apps → APK extraction
 * ==================================================================================== */

private val iconCache = LruCache<String, ImageBitmap>(256)

@Composable
private fun AppIcon(packageName: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState(iconCache.get(packageName), packageName) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(144, 144).asImageBitmap()
                    .also { iconCache.put(packageName, it) }
            }.getOrNull()
        }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        AnimatedContent(bitmap, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "icon") { b ->
            if (b != null) Image(b, null, Modifier.size(size))
            else Box(Modifier.size(size).clip(MaterialShapes.Clover4Leaf.toShape()).background(MaterialTheme.colorScheme.secondaryContainer))
        }
    }
}

/** Installed apps list → extract APK (splits bundled as .apks). */
@Composable
fun InstalledAppsScreen(onBack: () -> Unit, onOpenFolder: (String) -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var reload by remember { mutableIntStateOf(0) }
    val apps by produceState<List<InstalledApp>?>(null, reload) { value = AppExporter.loadInstalled(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var jobId by remember { mutableStateOf<Long?>(null) }

    val visible = remember(apps, query, showSystem) {
        val q = query.trim().lowercase()
        apps.orEmpty().filter { (showSystem || !it.isSystem) && (q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.contains(q)) }
    }

    JobCompletionEffect(jobId) { job ->
        when (job.state) {
            JobState.DONE -> {
                haptics.confirm()
                val r = snackbar.showSnackbar(Strings.get(R.string.apk_saved), actionLabel = Strings.get(R.string.common_open_folder), duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed) onOpenFolder(AppExporter.exportDir.path)
            }
            JobState.FAILED -> snackbar.showSnackbar(job.error ?: Strings.get(R.string.apk_extract_failed))
            else -> Unit
        }
    }

    fun export(targets: List<InstalledApp>) {
        if (targets.isEmpty()) return
        haptics.click()
        val dir = AppExporter.exportDir
        val title = targets.singleOrNull()?.let { Strings.get(R.string.apk_extracting_one, it.label) } ?: Strings.get(R.string.apk_extracting_many, targets.size)
        val id = context.launchTask(JobKind.EXPORT, title, targets.map { it.sourceDir }, dir.path) {
            val total = targets.sumOf { it.totalSize }
            var base = 0L
            val cb = asProgressCallbacks()
            try {
                for (app in targets) {
                    checkpoint()
                    AppExporter.export(app, dir, cb, base, total) { existing ->
                        val choice = askConflict(
                            FileEntry(app.sourceDir, app.exportName, false, app.totalSize, app.lastUpdate),
                            FileEntry(existing.path, existing.name, false, existing.length(), existing.lastModified()),
                        )
                        when (choice) {
                            ConflictChoice.REPLACE -> existing
                            ConflictChoice.SKIP -> null
                            ConflictChoice.KEEP_BOTH -> ArchivePaths.uniqueFile(existing)
                        }
                    }
                    base += app.totalSize
                }
            } finally {
                context.container.fileSystems.notifyChanged(dir.path, dir.parent ?: dir.path)
            }
        }
        if (id == null) scope.launch { snackbar.showSnackbar(Strings.get(R.string.apk_extract_start_failed)) }
        else { jobId = id; selection = emptySet() }
    }

    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AnimatedContent(selection.isNotEmpty(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "bar") { sel ->
                if (sel) TopAppBar(
                    title = {
                        Row {
                            AnimatedCounter(selection.size.toString(), style = MaterialTheme.typography.titleLarge)
                            Text(" " + pluralStringResource(R.plurals.apk_selected_suffix, selection.size), style = MaterialTheme.typography.titleLarge)
                        }
                    },
                    navigationIcon = { IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Rounded.Close, stringResource(R.string.browser_cancel_selection)) } },
                    actions = {
                        IconButton(onClick = {
                            haptics.tick()
                            val all = visible.map { it.packageName }.toSet()
                            selection = if (selection.containsAll(all)) emptySet() else all
                        }) { Icon(Icons.Rounded.SelectAll, stringResource(R.string.browser_select_all)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) else TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.home_apps))
                            apps?.let { Text(pluralStringResource(R.plurals.apk_apps_count, visible.size, visible.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    navigationIcon = { BackButton(onBack) },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            AnimatedVisibility(selection.isNotEmpty(), enter = scaleIn(bouncy()) + fadeIn(), exit = scaleOut() + fadeOut()) {
                val interaction = remember { MutableInteractionSource() }
                ExtendedFloatingActionButton(
                    onClick = { export(apps.orEmpty().filter { it.packageName in selection }) },
                    icon = { Icon(Icons.Rounded.Download, null) },
                    text = { Text(stringResource(R.string.apk_extract)) },
                    interactionSource = interaction,
                    modifier = Modifier.pressScale(interaction),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.apk_search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    AnimatedVisibility(query.isNotEmpty(), enter = scaleIn(bouncy()), exit = scaleOut()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.search_clear)) }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = showSystem,
                    onClick = { haptics.tick(); showSystem = !showSystem },
                    label = { Text(stringResource(R.string.apk_system_apps)) },
                    leadingIcon = {
                        AnimatedVisibility(showSystem, enter = scaleIn(bouncy()), exit = scaleOut()) {
                            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(FilterChipDefaults.IconSize))
                        }
                    },
                )
            }
            when {
                apps == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(64.dp)) }
                visible.isEmpty() -> EmptyState(Icons.Rounded.Android, if (query.isEmpty()) stringResource(R.string.apk_no_apps) else stringResource(R.string.search_no_results))
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 120.dp), modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(visible, key = { _, a -> a.packageName }) { i, app ->
                        AppRow(
                            app = app,
                            selected = app.packageName in selection,
                            onClick = {
                                haptics.tick()
                                selection = if (app.packageName in selection) selection - app.packageName else selection + app.packageName
                            },
                            onLongClick = { haptics.longPress(); selection = selection + app.packageName },
                            onExport = { export(listOf(app)) },
                            modifier = Modifier.animateItem().cascadeIn(i, showSystem to query.isEmpty()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, label = "bg")
    val corner by animateDpAsState(if (selected) 28.dp else 16.dp, bouncy(), label = "corner")
    Row(
        modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .pressScale(interaction, 0.97f)
            .clip(RoundedCornerShape(corner))
            .background(bg)
            .combinedClickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onClick, onLongClick = onLongClick)
            .selectionSemantics(selected, onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            AppIcon(app.packageName, 48.dp)
            val badge by animateFloatAsState(if (selected) 1f else 0f, bouncy(), label = "badge")
            if (badge > 0.01f) {
                Box(
                    Modifier.size(20.dp).graphicsLayer { scaleX = badge; scaleY = badge; alpha = badge.coerceIn(0f, 1f) }
                        .clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(
                buildString {
                    app.versionName?.let { append("v").append(it).append(" · ") }
                    append(Format.size(app.totalSize))
                    if (app.hasSplits) append(" · ").append(pluralStringResource(R.plurals.apk_splits_count, app.splitSourceDirs.size, app.splitSourceDirs.size))
                    if (app.isSystem) append(" · ").append(stringResource(R.string.apk_system))
                },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
        IconButton(onClick = onExport) { Icon(Icons.Rounded.Download, stringResource(R.string.apk_extract)) }
    }
}
