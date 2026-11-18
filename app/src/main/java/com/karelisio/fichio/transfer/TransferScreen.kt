package com.karelisio.fichio.transfer

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.karelisio.fichio.R
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karelisio.fichio.container
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.data.db.TransferLogEntity
import com.karelisio.fichio.shizuku.MorphingBadge
import com.karelisio.fichio.ui.components.AnimatedCounter
import com.karelisio.fichio.ui.components.BackButton
import com.karelisio.fichio.ui.components.EmptyState
import com.karelisio.fichio.ui.components.SectionHeader
import com.karelisio.fichio.ui.components.bouncy
import com.karelisio.fichio.ui.components.cascadeIn
import com.karelisio.fichio.ui.components.popOn
import com.karelisio.fichio.ui.components.pressScale
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.theme.GaugeNumberStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local Wi-Fi transfer server: QR code, one-time code, log. */
@Composable
fun TransferScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val state by TransferController.state.collectAsStateWithLifecycle()
    val lastActivity by TransferController.lastActivity.collectAsStateWithLifecycle()
    val log by container.database.transferLog().observe().collectAsStateWithLifecycle(emptyList())
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()

    // Wi-Fi availability, refreshed every few seconds while stopped.
    var wifiIp by remember { mutableStateOf<String?>(null) }
    var wifiChecked by remember { mutableStateOf(false) }
    LaunchedEffect(state is TransferState.Active) {
        while (true) {
            wifiIp = withContext(Dispatchers.IO) { NetUtils.findWifiAddress(context)?.hostAddress }
            wifiChecked = true
            delay(3_000)
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state is TransferState.Active) {
        while (state is TransferState.Active) {
            now = System.currentTimeMillis(); delay(1_000)
        }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        TransferController.start(context)
    }
    val toggle: () -> Unit = {
        when (state) {
            is TransferState.Active, TransferState.Starting -> {
                haptics.click(); TransferController.stop(context)
            }
            else -> {
                haptics.confirm()
                val needs = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (needs) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else TransferController.start(context)
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.channel_transfer)) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp),
        ) {
            item(key = "power") { PowerToggle(state, onClick = toggle) }

            item(key = "status") {
                Column {
                    val s = state
                    AnimatedVisibility(
                        s !is TransferState.Active && wifiChecked && wifiIp == null,
                        enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(),
                    ) {
                        NoticeCard(
                            Icons.Rounded.WifiOff,
                            stringResource(R.string.transfer_no_wifi),
                            stringResource(R.string.transfer_no_wifi_body),
                            MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    AnimatedVisibility(s is TransferState.Error, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        NoticeCard(
                            Icons.Rounded.Block, stringResource(R.string.transfer_impossible), (s as? TransferState.Error)?.message.orEmpty(),
                            MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    val reason = (s as? TransferState.Stopped)?.reason
                    AnimatedVisibility(reason != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        NoticeCard(
                            Icons.Rounded.Info, stringResource(R.string.transfer_stopped), reason.orEmpty(),
                            MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            item(key = "active") {
                val s = state as? TransferState.Active
                AnimatedVisibility(
                    s != null,
                    enter = expandVertically(bouncy()) + fadeIn() + slideInVertically { it / 4 },
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    var last by remember { mutableStateOf(s) }
                    if (s != null) last = s
                    last?.let { a ->
                        ActivePanel(
                            a,
                            remainingMs = (a.idleTimeoutMs - (now - lastActivity)).coerceIn(0, a.idleTimeoutMs),
                            onNewCode = { haptics.click(); TransferController.regenerateCode() },
                        )
                    }
                }
            }

            item(key = "info") {
                NoticeCard(
                    Icons.Rounded.Info,
                    stringResource(R.string.transfer_how),
                    stringResource(R.string.transfer_how_body),
                    MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item(key = "logHeader") {
                SectionHeader(stringResource(R.string.transfer_log), trailing = {
                    AnimatedVisibility(log.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                        TextButton(onClick = {
                            haptics.click()
                            scope.launch(Dispatchers.IO) { runCatching { container.database.transferLog().clear() } }
                        }) {
                            Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.search_clear))
                        }
                    }
                })
            }
            if (log.isEmpty()) {
                item(key = "logEmpty") {
                    EmptyState(Icons.Rounded.Devices, stringResource(R.string.transfer_log_empty), message = stringResource(R.string.transfer_log_empty_body))
                }
            }
            itemsIndexed(log, key = { _, e -> e.id }) { i, e ->
                LogRow(e, now, Modifier.animateItem().cascadeIn(i, key = e.id))
            }
        }
    }
}

/** Big expressive power button whose silhouette morphs between stopped and active. */
@Composable
private fun PowerToggle(state: TransferState, onClick: () -> Unit) {
    val active = state is TransferState.Active
    val starting = state == TransferState.Starting
    val container by animateColorAsState(
        when {
            active -> MaterialTheme.colorScheme.primary
            starting -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        label = "power",
    )
    val content = when {
        active -> MaterialTheme.colorScheme.onPrimary
        starting -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.primary
    }
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MorphingBadge(
            target = when {
                active -> MaterialShapes.SoftBurst
                starting -> MaterialShapes.Cookie12Sided
                else -> MaterialShapes.Cookie9Sided
            },
            color = container,
            rotate = active || starting,
            modifier = Modifier
                .size(176.dp)
                .popOn(active)
                .pressScale(interaction, 0.9f)
                .toggleable(value = active, interactionSource = interaction, indication = null, role = Role.Switch) { onClick() },
        ) {
            AnimatedContent(
                targetState = when { starting -> 1; active -> 2; else -> 0 },
                transitionSpec = { (scaleIn(bouncy()) + fadeIn()) togetherWith fadeOut() },
                label = "powerIcon",
            ) { s ->
                when (s) {
                    1 -> LoadingIndicator(Modifier.size(72.dp), color = content)
                    2 -> Icon(Icons.Rounded.WifiTethering, stringResource(R.string.audio_stop), Modifier.size(72.dp), tint = content)
                    else -> Icon(Icons.Rounded.PowerSettingsNew, stringResource(R.string.transfer_start), Modifier.size(72.dp), tint = content)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        AnimatedContent(targetState = Pair(active, starting), label = "powerLabel") { (a, st) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when { st -> stringResource(R.string.transfer_starting); a -> stringResource(R.string.transfer_active); else -> stringResource(R.string.transfer_start_transfer) },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    when { st -> stringResource(R.string.transfer_opening_server); a -> stringResource(R.string.transfer_tap_to_stop); else -> stringResource(R.string.transfer_share_with_pc) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ActivePanel(a: TransferState.Active, remainingMs: Long, onNewCode: () -> Unit) {
    val qr = remember(a.qrUrl) { QrCode.bitmap(a.qrUrl)?.asImageBitmap() }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // QR code: always dark on white so that every scanner reads it.
            val usedAlpha by animateFloatAsState(if (a.code == null) 0.25f else 1f, label = "qrAlpha")
            Box(
                Modifier.size(224.dp).clip(MaterialTheme.shapes.large).background(Color.White).padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (qr != null) {
                    Image(
                        qr, stringResource(R.string.transfer_qr),
                        Modifier.fillMaxSize(),
                        filterQuality = FilterQuality.None,
                        alpha = usedAlpha,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Link, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                SelectionContainer {
                    Text(
                        a.url.removePrefix("http://"),
                        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(stringResource(R.string.transfer_in_browser), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 18.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.transfer_one_time_code), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    AnimatedContent(a.code, label = "code") { code ->
                        if (code != null) {
                            AnimatedCounter(
                                code.chunked(3).joinToString(" "),
                                style = GaugeNumberStyle.copy(fontSize = 56.sp, letterSpacing = 4.sp, color = MaterialTheme.colorScheme.onPrimaryContainer),
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    stringResource(R.string.transfer_code_used),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                                FilledTonalButton(onClick = onNewCode, shapes = ButtonDefaults.shapes()) {
                                    Icon(Icons.Rounded.Refresh, null, Modifier.size(ButtonDefaults.IconSize))
                                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                    Text(stringResource(R.string.transfer_new_code))
                                }
                            }
                        }
                    }
                    if (a.sessions > 0) {
                        Text(
                            pluralStringResource(R.plurals.transfer_devices, a.sessions, a.sessions),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            val frac by animateFloatAsState(remainingMs.toFloat() / a.idleTimeoutMs.coerceAtLeast(1), label = "idle")
            Text(
                stringResource(R.string.transfer_auto_stop_in, Format.duration(remainingMs)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LinearWavyProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun NoticeCard(icon: ImageVector, title: String, message: String, container: Color, content: Color) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        contentColor = content,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun LogRow(e: TransferLogEntity, now: Long, modifier: Modifier = Modifier) {
    val (icon, label) = when (e.action) {
        TransferActions.DOWNLOAD -> Icons.Rounded.CloudDownload to stringResource(R.string.transfer_downloaded)
        TransferActions.UPLOAD -> Icons.Rounded.CloudUpload to stringResource(R.string.transfer_received)
        TransferActions.MKDIR -> Icons.Rounded.CreateNewFolder to stringResource(R.string.transfer_folder_created)
        TransferActions.CONNECT -> Icons.Rounded.Devices to stringResource(R.string.transfer_connection)
        else -> Icons.Rounded.Block to stringResource(R.string.transfer_refused)
    }
    val tone = if (e.action == TransferActions.DENIED) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val onTone = if (e.action == TransferActions.DENIED) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(Modifier.size(40.dp).clip(MaterialShapes.Cookie6Sided.toShape()).background(tone), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = onTone)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (e.action == TransferActions.CONNECT) label else "$label · ${e.path.substringAfterLast('/').ifEmpty { e.path }}",
                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(e.client)
                    if (e.size > 0) append(" · ").append(Format.size(e.size))
                    append(" · ").append(Format.relative(e.time, now))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
