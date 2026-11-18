package com.karelisio.fichio.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karelisio.fichio.container
import com.karelisio.fichio.core.model.SortBy
import com.karelisio.fichio.core.model.ViewMode
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.data.prefs.FichioSettings
import com.karelisio.fichio.data.prefs.SettingsRepository
import com.karelisio.fichio.data.prefs.ThemeMode
import com.karelisio.fichio.onboarding.PermissionsCatalog
import com.karelisio.fichio.onboarding.rememberPermissionSnapshot
import com.karelisio.fichio.ui.components.BackButton
import com.karelisio.fichio.ui.components.ListContentPadding
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.update.UpdateChecker
import com.karelisio.fichio.update.UpdateManager
import com.karelisio.fichio.update.UpdateState
import kotlinx.coroutines.launch

private const val POLARO_PACKAGE = "com.karelisio.polaro"

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenPermissions: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val repo = app.settings
    val s by repo.settings.collectAsStateWithLifecycle(FichioSettings())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val updateState by UpdateManager.state.collectAsStateWithLifecycle()
    val perms by rememberPermissionSnapshot()
    val missing = perms.count { !it.value }
    val polaroInstalled = remember {
        runCatching { context.packageManager.getPackageInfo(POLARO_PACKAGE, 0); true }.getOrDefault(false)
    }
    var portDialog by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun set(block: suspend SettingsRepository.() -> Unit) { scope.launch { repo.block() } }

    val groups: List<Pair<String, List<SettingRow>>> = listOf(
        "Apparence" to listOf(
            customRow(Icons.Rounded.DarkMode, "Thème") {
                ChoiceButtons(
                    options = listOf(ThemeMode.SYSTEM to "Système", ThemeMode.LIGHT to "Clair", ThemeMode.DARK to "Sombre"),
                    selected = s.themeMode,
                ) { set { setThemeMode(it) } }
            },
            switchRow(Icons.Rounded.Palette, "Couleurs dynamiques", "Teintes issues de votre fond d'écran", s.dynamicColor) { set { setDynamicColor(it) } },
            switchRow(Icons.Rounded.Animation, "Réduire les animations", "Transitions plus sobres, sans rebonds", s.reduceMotion) { set { setReduceMotion(it) } },
            switchRow(Icons.Rounded.Vibration, "Retours haptiques", "Petites vibrations lors des interactions", s.haptics) { set { setHaptics(it) } },
        ),
        "Navigation" to listOf(
            customRow(Icons.Rounded.GridView, "Affichage par défaut") {
                ChoiceButtons(
                    options = listOf(ViewMode.LIST to "Liste", ViewMode.GRID to "Grille", ViewMode.LARGE to "Vignettes"),
                    selected = s.defaultViewMode,
                ) { set { setDefaultViewMode(it) } }
            },
            customRow(Icons.AutoMirrored.Rounded.Sort, "Tri par défaut", if (s.defaultSort.ascending) "Ordre croissant" else "Ordre décroissant") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceButtons(
                        options = listOf(SortBy.NAME to "Nom", SortBy.DATE to "Date", SortBy.SIZE to "Taille", SortBy.TYPE to "Type"),
                        selected = s.defaultSort.by,
                    ) { set { setDefaultSort(s.defaultSort.copy(by = it)) } }
                    ChoiceButtons(
                        options = listOf(true to "Croissant", false to "Décroissant"),
                        selected = s.defaultSort.ascending,
                    ) { set { setDefaultSort(s.defaultSort.copy(ascending = it)) } }
                }
            },
            switchRow(Icons.Rounded.CreateNewFolder, "Dossiers en premier", null, s.defaultSort.foldersFirst) {
                set { setDefaultSort(s.defaultSort.copy(foldersFirst = it)) }
            },
            switchRow(Icons.Rounded.Visibility, "Fichiers cachés", "Afficher les éléments commençant par un point", s.showHidden) { set { setShowHidden(it) } },
            switchRow(Icons.Rounded.ViewColumn, "Double volet en paysage", "Deux dossiers côte à côte", s.dualPaneInLandscape) { set { setDualPaneInLandscape(it) } },
        ),
        "Fichiers" to listOf(
            switchRow(Icons.Rounded.Warning, "Confirmer la suppression", null, s.confirmDelete) { set { setConfirmDelete(it) } },
            customRow(Icons.Rounded.Layers, "Opérations simultanées", "Copies et déplacements exécutés en même temps ; les suivants attendent") {
                ChoiceButtons(
                    options = (1..SettingsRepository.MAX_CONCURRENT_OPERATIONS).map { it to "$it" },
                    selected = s.concurrentOperations,
                ) { set { setConcurrentOperations(it) } }
            },
            switchRow(Icons.Rounded.Delete, "Corbeille", "Les fichiers supprimés y restent avant d'être effacés", s.trashEnabled) { set { setTrashEnabled(it) } },
            intSliderRow(
                Icons.Rounded.DeleteSweep, "Vider la corbeille après", 1..90, s.trashRetentionDays.coerceIn(1, 90),
                enabled = s.trashEnabled, label = { Format.count(it, "jour") },
            ) { v -> set { setTrashRetentionDays(v) } },
            switchRow(
                Icons.Rounded.Image, "Ouvrir les images dans Polaro",
                if (polaroInstalled) "Utiliser la galerie Polaro pour les photos" else "Polaro n'est pas installée",
                s.openImagesInPolaro && polaroInstalled, enabled = polaroInstalled,
            ) { set { setOpenImagesInPolaro(it) } },
        ),
        "Recherche" to listOf(
            switchRow(Icons.Rounded.ManageSearch, "Index de recherche", "Recherche instantanée dans tout le stockage", s.searchIndexEnabled) { set { setSearchIndexEnabled(it) } },
            clickRow(
                Icons.Rounded.Refresh, "Reconstruire l'index",
                if (s.lastIndexRun > 0) "Dernier index : ${Format.relative(s.lastIndexRun)}" else "Jamais indexé",
                enabled = s.searchIndexEnabled,
            ) {
                runCatching { app.indexer.requestFullScan() }
                scope.launch { snackbar.showSnackbar("Indexation lancée en arrière-plan") }
            },
        ),
        "Analyse" to listOf(
            stepSliderRow(
                Icons.Rounded.Straighten, "Seuil des gros fichiers", listOf(1, 5, 10, 25, 50, 100, 250, 500, 1000, 2000),
                s.largeFileThresholdMb, label = { if (it >= 1000) "${it / 1000} Go" else "$it Mo" },
            ) { v -> set { setLargeFileThresholdMb(v) } },
            stepSliderRow(
                Icons.Rounded.History, "Fichiers anciens après", listOf(7, 30, 90, 180, 365, 730, 1095),
                s.oldFileThresholdDays, label = { if (it >= 365) Format.count(it / 365, "an") else "$it j" },
            ) { v -> set { setOldFileThresholdDays(v) } },
        ),
        "Transfert local" to listOf(
            clickRow(Icons.Rounded.Router, "Port du serveur", "Le serveur écoute sur ce port du Wi-Fi local",
                trailing = { Text("${s.transferPort}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) },
            ) { portDialog = true },
            stepSliderRow(
                Icons.Rounded.Timer, "Arrêt après inactivité", listOf(1, 5, 10, 15, 30, 60, 120),
                s.transferIdleMinutes, label = { if (it >= 60) "${it / 60} h" else "$it min" },
            ) { v -> set { setTransferIdleMinutes(v) } },
        ),
        "Mises à jour" to listOf(
            switchRow(Icons.Rounded.Update, "Vérification automatique", "Au plus toutes les 6 h, via GitHub", s.autoCheckUpdates) { set { setAutoCheckUpdates(it) } },
            clickRow(
                Icons.Rounded.SystemUpdate, "Vérifier maintenant", updateSubtitle(updateState, s.lastUpdateCheck),
                trailing = { UpdateTrailing(updateState) },
            ) {
                if (updateState is UpdateState.Available) UpdateManager.showSheet() else UpdateManager.checkNow(context, force = true)
            },
            infoRow(Icons.Rounded.Info, "Version actuelle", UpdateManager.currentVersion),
        ),
        "Autorisations" to listOf(
            clickRow(
                Icons.Rounded.Security, "Autorisations",
                if (missing == 0) "Tout est en ordre" else Format.count(missing, "autorisation") + " à vérifier",
                trailing = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) },
                onClick = onOpenPermissions,
            ),
        ),
        "À propos" to listOf(
            infoRow(Icons.Rounded.AutoAwesome, "Fichio ${UpdateManager.currentVersion}", "Gestionnaire de fichiers Material You"),
            infoRow(Icons.Rounded.CloudOff, "100 % local", "Sans compte, sans analytics, sans serveur. Seule la vérification des mises à jour contacte GitHub."),
            infoRow(Icons.Rounded.Gavel, "Licence", "Projet privé — tous droits réservés"),
            clickRow(Icons.Rounded.Code, "Code source et versions", UpdateChecker.repoUrl.removePrefix("https://")) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.repoUrl))) }
            },
        ),
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("Réglages") },
                subtitle = { Text("Fichio ${UpdateManager.currentVersion}") },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + ListContentPadding.calculateBottomPadding()),
        ) {
            itemsIndexed(groups, key = { _, g -> g.first }) { i, (title, rows) -> SettingsGroup(title, i, rows) }
        }
    }

    if (portDialog) PortDialog(s.transferPort, onDismiss = { portDialog = false }) { p ->
        portDialog = false
        set { setTransferPort(p) }
    }
}

private fun updateSubtitle(state: UpdateState, lastCheck: Long): String = when (state) {
    UpdateState.Checking -> "Recherche en cours…"
    is UpdateState.UpToDate -> "Fichio est à jour"
    is UpdateState.Available -> "Version ${state.update.version} disponible — toucher pour voir"
    is UpdateState.Downloading -> "Téléchargement de la version ${state.update.version}…"
    is UpdateState.Installing -> "Installation…"
    is UpdateState.Error -> state.message
    UpdateState.Idle -> if (lastCheck > 0) "Dernière vérification : ${Format.relative(lastCheck)}" else "Jamais vérifié"
}

@Composable
private fun UpdateTrailing(state: UpdateState) {
    AnimatedContent(
        targetState = state::class,
        transitionSpec = { (scaleIn() + fadeIn()) togetherWith fadeOut() },
        label = "updTrail",
    ) { k ->
        when (k) {
            UpdateState.Checking::class, UpdateState.Downloading::class, UpdateState.Installing::class -> LoadingIndicator(Modifier.size(32.dp))
            UpdateState.Available::class -> Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
            else -> Spacer(Modifier.size(1.dp))
        }
    }
}

@Composable
private fun <T> ChoiceButtons(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val haptics = rememberHaptics()
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { haptics.segment(); onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun PortDialog(current: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in 1024..65535
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Wifi, null) },
        title = { Text("Port du serveur") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { t -> text = t.filter(Char::isDigit).take(5) },
                singleLine = true,
                isError = !valid,
                supportingText = { Text("Entre 1024 et 65535") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(onClick = { value?.let(onConfirm) }, enabled = valid) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    PermissionsList(onBack)
}
