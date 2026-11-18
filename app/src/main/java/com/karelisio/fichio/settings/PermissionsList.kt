package com.karelisio.fichio.settings

import androidx.compose.ui.res.stringResource
import com.karelisio.fichio.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karelisio.fichio.container
import com.karelisio.fichio.onboarding.AnimatedCheck
import com.karelisio.fichio.onboarding.MorphingBadge
import com.karelisio.fichio.onboarding.PermissionKind
import com.karelisio.fichio.onboarding.PermissionStatusPill
import com.karelisio.fichio.onboarding.PermissionsCatalog
import com.karelisio.fichio.onboarding.rememberPermissionRequester
import com.karelisio.fichio.onboarding.rememberPermissionSnapshot
import com.karelisio.fichio.ui.components.BackButton
import com.karelisio.fichio.ui.components.ListContentPadding
import com.karelisio.fichio.ui.components.cascadeIn
import com.karelisio.fichio.ui.components.rememberHaptics

/** Every permission with an animated status pill and a stringResource(R.string.perm_fix) action; re-checked on resume. */
@Composable
internal fun PermissionsList(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val snapshot by rememberPermissionSnapshot()
    var refresh by remember { mutableIntStateOf(0) }
    val granted = remember(snapshot, refresh) { PermissionsCatalog.snapshot(context) }
    val request = rememberPermissionRequester { refresh++ }
    val shizuku by context.container.shizuku.state.collectAsStateWithLifecycle()
    val specs = remember { PermissionsCatalog.applicable() }
    val missingRequired = specs.count { it.required && granted[it.id] != true }
    val okCount = specs.count { granted[it.id] == true }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.perm_title)) },
                subtitle = { Text(stringResource(R.string.perm_granted_count, okCount, specs.size)) },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + ListContentPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            item {
                SummaryCard(allGood = okCount == specs.size, missingRequired = missingRequired)
                Spacer(Modifier.height(12.dp))
            }
            itemsIndexed(specs, key = { _, s -> s.id.name }) { i, spec ->
                val ok = granted[spec.id] == true
                val cs = MaterialTheme.colorScheme
                val badge by animateColorAsState(if (ok) cs.primary else cs.secondaryContainer, label = "badge")
                SegmentedListItem(
                    shapes = ListItemDefaults.segmentedShapes(i, specs.size),
                    modifier = Modifier.padding(horizontal = 16.dp).cascadeIn(i),
                    leadingContent = {
                        Box(Modifier.size(44.dp).clip(spec.shape.toShape()).background(badge), contentAlignment = Alignment.Center) {
                            if (ok) AnimatedCheck(true, cs.onPrimary, Modifier.size(24.dp), strokeWidth = 3.dp)
                            else Icon(spec.icon, null, tint = cs.onSecondaryContainer)
                        }
                    },
                    overlineContent = { PermissionStatusPill(ok, optional = !spec.required) },
                    supportingContent = {
                        Column {
                            Text(
                                if (spec.kind == PermissionKind.SHIZUKU) PermissionsCatalog.shizukuStatusText(shizuku) else spec.description,
                            )
                            if (spec.kind == PermissionKind.MANUAL) {
                                spec.steps.forEachIndexed { n, step -> Text("${n + 1}. $step", style = MaterialTheme.typography.bodySmall) }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(ok, onCheckedChange = {
                                        haptics.tick()
                                        PermissionsCatalog.setAutostartConfirmed(context, it)
                                        refresh++
                                    })
                                    Text(stringResource(R.string.perm_done), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            AnimatedVisibility(!ok || spec.kind == PermissionKind.MANUAL, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                                FilledTonalButton(
                                    onClick = { haptics.click(); request(spec) },
                                    shapes = ButtonDefaults.shapes(),
                                    modifier = Modifier.padding(top = 8.dp),
                                ) {
                                    Text(
                                        when (spec.kind) {
                                            PermissionKind.SHIZUKU -> PermissionsCatalog.shizukuActionText(shizuku)
                                            PermissionKind.MANUAL -> stringResource(R.string.perm_open_settings)
                                            else -> stringResource(R.string.perm_fix)
                                        }
                                    )
                                }
                            }
                        }
                    },
                ) { Text(spec.title) }
            }
        }
    }
}

@Composable
private fun SummaryCard(allGood: Boolean, missingRequired: Int) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(MaterialTheme.shapes.extraLarge)
            .background(if (allGood) cs.primaryContainer else if (missingRequired > 0) cs.errorContainer else cs.tertiaryContainer)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MorphingBadge(
            listOf(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny, MaterialShapes.Flower),
            if (allGood) cs.primary else if (missingRequired > 0) cs.error else cs.tertiary,
            Modifier.size(56.dp),
        ) {
            AnimatedCheck(allGood, cs.onPrimary, Modifier.size(30.dp), strokeWidth = 4.dp)
            if (!allGood) Text("!", style = MaterialTheme.typography.titleLarge, color = cs.onError)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                when {
                    allGood -> stringResource(R.string.perm_all_good)
                    missingRequired > 0 -> stringResource(R.string.perm_files_missing)
                    else -> stringResource(R.string.perm_some_optional)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                when {
                    allGood -> stringResource(R.string.perm_all_good_body)
                    missingRequired > 0 -> stringResource(R.string.perm_files_missing_body)
                    else -> stringResource(R.string.perm_optional_body)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
