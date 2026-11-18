@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.karelisio.fichio.ui.browser

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditOff
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.SafAccess
import com.karelisio.fichio.core.fs.VolumeWriteAccess
import com.karelisio.fichio.ui.components.rememberHaptics
import kotlinx.coroutines.launch

/**
 * Shown above the listing of a removable volume (SD card, USB key) that Android keeps read-only
 * for java.io: explains why and opens the system picker on the volume root, so that writes go
 * through the granted SAF tree from then on.
 */
@Composable
internal fun RemovableAccessBanner(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val saf = context.container.saf
    val volumes by context.container.volumes.volumes.collectAsStateWithLifecycle()
    val volume = remember(path, volumes) { saf.removableVolumeOf(path) } ?: return
    val access by saf.access.collectAsStateWithLifecycle()
    val state = access[volume.path] ?: VolumeWriteAccess.UNKNOWN
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var message by remember(volume.path) { mutableStateOf<String?>(null) }

    LaunchedEffect(volume.path) { saf.check(volume) }
    // Permission revoked in the system settings meanwhile: re-check on return.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { saf.check(volume) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = when (saf.onTreePicked(volume, uri)) {
                SafAccess.GrantResult.OK -> { haptics.confirm(); null }
                SafAccess.GrantResult.NOT_ROOT -> { haptics.confirm(); "Accès accordé à ce dossier seulement. Choisissez la racine de « ${volume.label} » pour tout le volume." }
                SafAccess.GrantResult.WRONG_VOLUME -> { haptics.reject(); "Ce dossier n'est pas sur « ${volume.label} ». Sélectionnez la racine du volume." }
                SafAccess.GrantResult.FAILED -> { haptics.reject(); "Android a refusé l'accès. Réessayez." }
            }
        }
    }

    AnimatedVisibility(
        visible = state == VolumeWriteAccess.READ_ONLY || message != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(MaterialShapes.Cookie7Sided.toShape())
                            .background(MaterialTheme.colorScheme.tertiary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.EditOff, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onTertiary)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (state == VolumeWriteAccess.READ_ONLY) "« ${volume.label} » est en lecture seule" else volume.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Text(
                            message ?: "Android limite l'écriture hors du stockage interne. Autorisez Fichio sur ce volume pour copier, renommer ou supprimer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                if (state == VolumeWriteAccess.READ_ONLY || message != null) {
                    Spacer(Modifier.size(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = {
                                haptics.click()
                                message = null
                                runCatching { picker.launch(saf.openTreeIntent(volume)) }
                                    .onFailure { haptics.reject(); message = "Le sélecteur de dossiers du système est indisponible." }
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Icon(Icons.Rounded.LockOpen, null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("Autoriser l'écriture")
                        }
                    }
                }
            }
        }
    }
}
