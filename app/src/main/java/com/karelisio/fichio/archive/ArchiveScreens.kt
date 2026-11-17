package com.karelisio.fichio.archive

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.ops.ConflictChoice
import com.karelisio.fichio.ops.JobKind
import com.karelisio.fichio.ops.JobState
import com.karelisio.fichio.ui.components.AnimatedCounter
import com.karelisio.fichio.ui.components.BackButton
import com.karelisio.fichio.ui.components.EmptyState
import com.karelisio.fichio.ui.components.FileIcon
import com.karelisio.fichio.ui.components.bouncy
import com.karelisio.fichio.ui.components.cascadeIn
import com.karelisio.fichio.ui.components.pressScale
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import com.karelisio.fichio.ui.theme.toneOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/* ======================================================================================
 * Archive browser
 * ==================================================================================== */

/** Browse an archive like a folder; extract all or a selection. */
@Composable
fun ArchiveScreen(path: String, onBack: () -> Unit, onOpenFolder: (String) -> Unit) {
    val context = LocalContext.current
    val vm: ArchiveViewModel = viewModel(key = "archive:$path") { ArchiveViewModel(context.applicationContext, path) }
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val selectionMode = vm.selection.isNotEmpty()

    var extractSelection by remember { mutableStateOf<List<String>?>(null) }
    var showExtract by remember { mutableStateOf(false) }
    var jobId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(vm.dir) { if (listState.firstVisibleItemIndex > 1) listState.scrollToItem(1) }

    JobCompletionEffect(jobId) { job ->
        when (job.state) {
            JobState.DONE -> {
                haptics.confirm()
                val dest = job.destination
                val r = snackbar.showSnackbar(
                    "Extraction terminée", actionLabel = if (dest != null) "Ouvrir le dossier" else null,
                    duration = SnackbarDuration.Long,
                )
                if (r == SnackbarResult.ActionPerformed && dest != null) onOpenFolder(dest)
            }
            JobState.FAILED -> snackbar.showSnackbar(job.error ?: "Échec de l'extraction")
            else -> Unit
        }
    }

    BackHandler(enabled = selectionMode || vm.dir.isNotEmpty()) {
        haptics.tick()
        if (selectionMode) vm.clearSelection() else vm.up()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AnimatedContent(selectionMode, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "bar") { sel ->
                if (sel) {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AnimatedCounter(vm.selection.size.toString(), style = MaterialTheme.typography.titleLarge)
                                Text(if (vm.selection.size > 1) " sélectionnés" else " sélectionné", style = MaterialTheme.typography.titleLarge)
                            }
                        },
                        navigationIcon = { IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Rounded.Close, "Annuler la sélection") } },
                        actions = { IconButton(onClick = { haptics.tick(); vm.selectAll() }) { Icon(Icons.Rounded.SelectAll, "Tout sélectionner") } },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    )
                } else {
                    TopAppBar(
                        title = { Text(vm.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = { BackButton(onBack) },
                        scrollBehavior = scrollBehavior,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            AnimatedVisibility(
                vm.load == ArchiveViewModel.Load.Ready && vm.tree?.items?.isNotEmpty() == true,
                enter = scaleIn(bouncy()) + fadeIn(), exit = scaleOut() + fadeOut(),
            ) {
                val interaction = remember { MutableInteractionSource() }
                ExtendedFloatingActionButton(
                    onClick = {
                        haptics.click()
                        vm.withPassword {
                            extractSelection = vm.selection.takeIf { it.isNotEmpty() }?.toList()
                            showExtract = true
                        }
                    },
                    icon = { Icon(Icons.Rounded.Unarchive, null) },
                    text = { Text(if (selectionMode) "Extraire (${vm.selection.size})" else "Tout extraire") },
                    expanded = !listState.isScrollInProgress || selectionMode,
                    interactionSource = interaction,
                    modifier = Modifier.pressScale(interaction),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val load = vm.load) {
                ArchiveViewModel.Load.Loading -> Column(
                    Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    LoadingIndicator(Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Lecture de l'archive…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ArchiveViewModel.Load.Locked -> EmptyState(
                    Icons.Rounded.Lock, "Archive protégée", Modifier.align(Alignment.Center),
                    message = "Le contenu de cette archive est chiffré.",
                    actionLabel = "Saisir le mot de passe", onAction = { vm.askPassword() },
                )
                is ArchiveViewModel.Load.Failed -> EmptyState(
                    Icons.Rounded.ErrorOutline, "Impossible d'ouvrir l'archive", Modifier.align(Alignment.Center),
                    message = load.message,
                )
                ArchiveViewModel.Load.Ready -> ArchiveContent(vm, listState, onToggle = {
                    haptics.longPress(); vm.toggle(it)
                }, onOpen = { item ->
                    if (selectionMode) { haptics.tick(); vm.toggle(item) }
                    else if (item.isDirectory) { haptics.tick(); vm.navigate(item.path) }
                    else vm.openItem(item)
                })
            }

            // Opening a file: small floating card with progress.
            AnimatedVisibility(
                vm.opening != null,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                enter = slideInVertically(bouncy()) { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            ) {
                Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, shadowElevation = 6.dp) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ContainedLoadingIndicator(Modifier.size(40.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.width(220.dp)) {
                            Text("Ouverture de ${vm.opening.orEmpty()}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
                            LinearWavyProgressIndicator(progress = { vm.openingProgress }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }

    vm.prompt?.let { p ->
        PasswordDialog(vm.file.name, wrong = p.wrong, busy = p.busy, onSubmit = vm::submitPassword, onDismiss = vm::dismissPassword)
    }

    if (showExtract) {
        ExtractOptionsSheet(
            archivePath = path,
            selection = extractSelection,
            stripPrefix = if (extractSelection != null) vm.dir else "",
            knownPassword = vm.password,
            knownEncrypted = vm.encrypted,
            onDismiss = { showExtract = false },
            onLaunched = { id ->
                showExtract = false
                vm.clearSelection()
                jobId = id
                if (id == null) scope.launch { snackbar.showSnackbar("Impossible de lancer l'extraction") }
            },
        )
    }
}

@Composable
private fun ArchiveContent(
    vm: ArchiveViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onToggle: (ArchiveItem) -> Unit,
    onOpen: (ArchiveItem) -> Unit,
) {
    val tree = vm.tree ?: return
    val children = remember(tree, vm.dir) { tree.children(vm.dir) }
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 120.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "header") {
            ArchiveHeader(vm.file, vm.format, tree, vm.encrypted, Modifier.padding(16.dp))
        }
        item(key = "crumbs") { Breadcrumbs(vm.file.name, vm.dir, onNavigate = vm::navigate) }
        if (children.isEmpty()) {
            item(key = "empty") {
                EmptyState(Icons.Rounded.FolderOpen, "Dossier vide", Modifier.padding(top = 24.dp))
            }
        }
        itemsIndexed(children, key = { _, it -> it.path }) { index, item ->
            ArchiveRow(
                item = item,
                childCount = if (item.isDirectory) tree.children(item.path).size else 0,
                selected = item.path in vm.selection,
                selectionMode = vm.selection.isNotEmpty(),
                onClick = { onOpen(item) },
                onLongClick = { onToggle(item) },
                modifier = Modifier.animateItem().cascadeIn(index, vm.dir),
            )
        }
    }
}

@Composable
private fun ArchiveHeader(file: File, format: ArchiveFormat?, tree: ArchiveTree, encrypted: Boolean, modifier: Modifier = Modifier) {
    val total = tree.totalSize
    val packed = if (tree.compressedSize > 0) tree.compressedSize else file.length()
    val ratio = if (total > 0) (packed.toFloat() / total).coerceIn(0f, 1f) else 1f
    val reduce = LocalReduceMotion.current
    val bar = remember { Animatable(if (reduce) ratio else 0f) }
    LaunchedEffect(ratio) { bar.animateTo(ratio, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessVeryLow)) }
    val tone = toneOf(com.karelisio.fichio.core.model.FileType.ARCHIVE)

    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeBadge(MaterialShapes.Gem, tone.container, size = 72.dp) {
                    Icon(Icons.Rounded.Archive, null, Modifier.size(34.dp), tint = tone.content)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill(format?.label ?: "Archive")
                        if (encrypted) Pill("Chiffrée", Icons.Rounded.Lock, MaterialTheme.colorScheme.tertiaryContainer)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                buildString {
                    append(Format.count(tree.fileCount, "fichier"))
                    if (tree.folderCount > 0) append(" · ").append(Format.count(tree.folderCount, "dossier"))
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Taille réelle", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AnimatedCounter(Format.size(total), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
                }
                Column(Modifier.weight(1f)) {
                    Text("Compressée", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AnimatedCounter(Format.size(packed), style = MaterialTheme.typography.headlineSmall)
                }
                if (total > 0 && packed < total) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        Text(
                            "−${((1f - ratio) * 100).roundToInt()} %",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // Compressed share of the real size.
            Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Box(
                    Modifier.fillMaxWidth(bar.value.coerceAtLeast(0.02f)).height(10.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun Pill(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    Surface(shape = CircleShape, color = color) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Breadcrumbs(rootName: String, dir: String, onNavigate: (String) -> Unit) {
    val segments = remember(dir) {
        buildList {
            add("" to rootName)
            if (dir.isNotEmpty()) {
                var acc = ""
                dir.split('/').forEach { s -> acc = if (acc.isEmpty()) s else "$acc/$s"; add(acc to s) }
            }
        }
    }
    val state = rememberLazyListState()
    LaunchedEffect(segments.size) { state.animateScrollToItem((segments.size - 1).coerceAtLeast(0)) }
    val haptics = rememberHaptics()
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        itemsIndexed(segments, key = { _, s -> s.first.ifEmpty { "/" } }) { i, (path, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.animateItem()) {
                if (i > 0) Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val current = i == segments.lastIndex
                val bg by animateColorAsState(
                    if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, label = "crumb",
                )
                Surface(
                    onClick = { if (!current) { haptics.tick(); onNavigate(path) } },
                    shape = CircleShape, color = bg,
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (i == 0) { Icon(Icons.Rounded.Archive, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
                        Text(
                            label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.then(if (i == 0) Modifier.width(140.dp) else Modifier),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveRow(
    item: ArchiveItem,
    childCount: Int,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, label = "rowBg",
    )
    val corner by animateDpAsState(if (selected) 28.dp else 16.dp, bouncy(), label = "rowCorner")
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .pressScale(interaction, 0.97f)
            .clip(RoundedCornerShape(corner))
            .background(bg)
            .combinedClickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(selected, transitionSpec = { scaleIn(bouncy()) + fadeIn() togetherWith scaleOut() + fadeOut() }, label = "sel") { s ->
                if (s) {
                    Box(
                        Modifier.size(44.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary) }
                } else FileIcon(item.type, name = item.name, size = 44.dp)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(
                when {
                    item.isDirectory -> Format.items(childCount)
                    else -> listOfNotNull(
                        item.size.takeIf { it >= 0 }?.let(Format::size),
                        item.lastModified.takeIf { it > 0 }?.let(Format::shortDate),
                    ).joinToString(" · ").ifEmpty { "Taille inconnue" }
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (item.isUnsafe) Icon(Icons.Rounded.Warning, "Chemin dangereux, ne sera pas extrait", tint = MaterialTheme.colorScheme.error)
        else if (item.isEncrypted) Icon(Icons.Rounded.Lock, "Chiffré", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


/* ======================================================================================
 * Extraction sheet
 * ==================================================================================== */

private enum class Destination(val label: String) { SUBFOLDER("Dans un nouveau dossier"), SAME("Dans le même dossier"), CUSTOM("Choisir un dossier…") }

private enum class ConflictPolicy(val label: String, val choice: ConflictChoice?) {
    ASK("Demander", null), REPLACE("Remplacer", ConflictChoice.REPLACE),
    SKIP("Ignorer", ConflictChoice.SKIP), KEEP_BOTH("Les deux", ConflictChoice.KEEP_BOTH),
}

/** Extract [archivePath] (all entries) to a chosen folder. */
@Composable
fun ExtractSheet(archivePath: String, onDismiss: () -> Unit) {
    ExtractOptionsSheet(archivePath, null, "", null, null, onDismiss, onLaunched = { onDismiss() })
}

/**
 * Bottom sheet with destination, password and conflict policy; queues an EXTRACT task.
 * [knownEncrypted] null = the sheet opens the archive to find out.
 */
@Composable
internal fun ExtractOptionsSheet(
    archivePath: String,
    selection: List<String>?,
    stripPrefix: String,
    knownPassword: CharArray?,
    knownEncrypted: Boolean?,
    onDismiss: () -> Unit,
    onLaunched: (Long?) -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val archive = remember(archivePath) { File(archivePath) }
    val parent = archive.parent ?: StoragePaths.primary
    val baseName = remember(archivePath) { archiveBaseName(archive.name) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var destination by rememberSaveable { mutableStateOf(Destination.SUBFOLDER) }
    var subfolder by rememberSaveable { mutableStateOf(baseName) }
    var customDir by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var policy by rememberSaveable { mutableStateOf(ConflictPolicy.ASK) }
    var passwordText by remember { mutableStateOf("") }
    var wrongPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Encryption: known from the browser, or probed here.
    val encrypted by produceState(knownEncrypted, archivePath) {
        if (knownEncrypted == null) value = withContext(Dispatchers.IO) {
            try { Archives.open(archive).use { it.isEncrypted } } catch (_: PasswordRequiredException) { true } catch (_: Throwable) { false }
        }
    }
    val needsPassword = encrypted == true && knownPassword == null

    val destPath = when (destination) {
        Destination.SUBFOLDER -> StoragePaths.join(parent, subfolder.trim().ifEmpty { baseName })
        Destination.SAME -> parent
        Destination.CUSTOM -> customDir
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeBadge(MaterialShapes.Gem, MaterialTheme.colorScheme.primaryContainer, size = 52.dp) {
                    Icon(Icons.Rounded.Unarchive, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        if (selection == null) "Tout extraire" else "Extraire ${Format.items(selection.size)}",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(archive.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Text("Destination", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Destination.entries.forEach { d ->
                OptionCard(
                    selected = destination == d,
                    icon = when (d) { Destination.SUBFOLDER -> Icons.Rounded.CreateNewFolder; Destination.SAME -> Icons.Rounded.Folder; Destination.CUSTOM -> Icons.Rounded.FolderOpen },
                    title = d.label,
                    subtitle = when (d) {
                        Destination.SUBFOLDER -> "${File(parent).name}/${subfolder.ifBlank { baseName }}"
                        Destination.SAME -> File(parent).name
                        Destination.CUSTOM -> customDir?.let { displayPath(it) } ?: "Aucun dossier choisi"
                    },
                ) {
                    haptics.tick()
                    destination = d
                    if (d == Destination.CUSTOM) picking = true
                }
            }
            AnimatedVisibility(destination == Destination.SUBFOLDER, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                OutlinedTextField(
                    value = subfolder, onValueChange = { subfolder = it.replace('/', '_') },
                    label = { Text("Nom du dossier") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }

            Text("Si un fichier existe déjà", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            ConnectedChoice(ConflictPolicy.entries, policy, { it.label }) { haptics.segment(); policy = it }

            AnimatedVisibility(needsPassword, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                PasswordField(
                    value = passwordText,
                    onValueChange = { passwordText = it; wrongPassword = false },
                    label = "Mot de passe de l'archive",
                    isError = wrongPassword,
                    supportingText = if (wrongPassword) "Mot de passe incorrect" else "Cette archive est chiffrée",
                )
            }
            AnimatedVisibility(error != null) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = {
                    val dest = destPath ?: run { picking = true; return@Button }
                    busy = true
                    error = null
                    scope.launch {
                        val pw = knownPassword ?: passwordText.takeIf { it.isNotEmpty() }?.toCharArray()
                        // Check the password now so the error shows in the sheet, not in a notification.
                        if (needsPassword) {
                            val ok = withContext(Dispatchers.IO) {
                                try { Archives.open(archive, pw).use { it.verifyPassword() }; null } catch (t: Throwable) { t }
                            }
                            if (ok != null) {
                                busy = false
                                if (ok is WrongPasswordException || ok is PasswordRequiredException) { wrongPassword = true; haptics.reject() }
                                else error = ok.archiveMessage()
                                return@launch
                            }
                        }
                        haptics.confirm()
                        val title = if (selection == null) "Extraction de ${archive.name}" else "Extraction depuis ${archive.name}"
                        val fixed = policy.choice
                        val id = context.launchTask(JobKind.EXTRACT, title, listOf(archivePath), dest) {
                            val cb = asExtractCallbacks(archivePath).let { base ->
                                if (fixed == null) base else object : ExtractCallbacks by base {
                                    override suspend fun onConflict(item: ArchiveItem, existing: File) = fixed
                                }
                            }
                            try {
                                Archives.open(archive, pw).use { r ->
                                    val res = r.extract(ExtractRequest(File(dest), selection, stripPrefix), cb)
                                    if (res.rejected > 0 && res.extracted == 0) throw ArchiveException("Aucun fichier extrait : chemins dangereux refusés")
                                }
                            } finally {
                                context.container.fileSystems.notifyChanged(dest, File(dest).parent ?: dest)
                            }
                        }
                        busy = false
                        onLaunched(id)
                    }
                },
                enabled = !busy && (!needsPassword || passwordText.isNotEmpty()) && (destination != Destination.CUSTOM || customDir != null),
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (busy) LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                else {
                    Icon(Icons.Rounded.Unarchive, null); Spacer(Modifier.width(8.dp)); Text("Extraire", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (picking) {
        FolderPickerDialog(
            initial = customDir ?: parent,
            onPick = { customDir = it; destination = Destination.CUSTOM; picking = false },
            onDismiss = { picking = false; if (customDir == null) destination = Destination.SUBFOLDER },
        )
    }
}

/** Selectable option card whose corners and colour spring when selected. */
@Composable
private fun OptionCard(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val corner by animateDpAsState(if (selected) 28.dp else 14.dp, bouncy(), label = "optCorner")
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, label = "optBg",
    )
    Surface(
        onClick = onClick, shape = RoundedCornerShape(corner), color = bg, interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().pressScale(interaction, 0.97f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) Text(
                    subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(selected, enter = scaleIn(bouncy()) + fadeIn(), exit = scaleOut() + fadeOut()) {
                Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Expressive connected button group (single choice). */
@Composable
internal fun <T> ConnectedChoice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f),
            ) {
                AnimatedVisibility(option == selected, enter = scaleIn(bouncy()) + fadeIn(), exit = scaleOut() + fadeOut()) {
                    Row { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
                }
                Text(label(option), maxLines = 1, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/* ======================================================================================
 * Compression sheet
 * ==================================================================================== */

/** Create a zip (optional AES password) or 7z from [entries] into [destinationDir]. */
@Composable
fun CompressSheet(entries: List<FileEntry>, destinationDir: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sources = remember(entries) { entries.map { File(it.path) } }
    var name by rememberSaveable { mutableStateOf(ArchiveWriter.suggestedName(sources)) }
    var format by rememberSaveable { mutableStateOf(OutputFormat.ZIP) }
    var level by rememberSaveable { mutableFloatStateOf(5f) }
    var encrypt by rememberSaveable { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var lastTick by remember { mutableIntStateOf(5) }

    // Source size estimate, computed off the main thread.
    val estimate by produceState<Pair<Int, Long>?>(null, sources) {
        value = withContext(Dispatchers.IO) {
            val plan = ArchiveWriter.plan(sources)
            plan.count { !it.isDirectory } to ArchiveWriter.totalSize(plan)
        }
    }

    val cleanName = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").removeSuffix(".${format.extension}")
    val mismatch = encrypt && confirm.isNotEmpty() && password != confirm
    val canEncrypt = format.supportsPassword
    val valid = cleanName.isNotEmpty() && (!encrypt || !canEncrypt || (password.isNotEmpty() && password == confirm))

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeBadge(MaterialShapes.Gem, toneOf(com.karelisio.fichio.core.model.FileType.ARCHIVE).container, size = 52.dp) {
                    Icon(Icons.Rounded.Archive, null, tint = toneOf(com.karelisio.fichio.core.model.FileType.ARCHIVE).content)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Compresser", style = MaterialTheme.typography.titleLarge)
                    AnimatedContent(estimate, label = "estimate") { e ->
                        Text(
                            if (e == null) "Calcul de la taille…" else "${Format.count(e.first, "fichier")} · ${Format.size(e.second)}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Nom de l'archive") }, singleLine = true,
                suffix = { Text(".${format.extension}") },
                isError = cleanName.isEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Format", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            ConnectedChoice(OutputFormat.entries, format, { it.label }) { haptics.segment(); format = it }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Compression", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                AnimatedContent(ArchiveWriter.levelLabel(level.roundToInt()), label = "level") { l ->
                    Text(l, style = MaterialTheme.typography.labelLarge)
                }
            }
            Slider(
                value = level,
                onValueChange = {
                    level = it
                    val r = it.roundToInt()
                    if (r != lastTick) { lastTick = r; haptics.segment() }
                },
                valueRange = ArchiveWriter.MIN_LEVEL.toFloat()..ArchiveWriter.MAX_LEVEL.toFloat(),
                steps = ArchiveWriter.MAX_LEVEL - ArchiveWriter.MIN_LEVEL - 1,
            )
            Row(Modifier.fillMaxWidth()) {
                listOf("Stockage", "Normal", "Ultra").forEachIndexed { i, l ->
                    Text(
                        l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = when (i) { 0 -> androidx.compose.ui.text.style.TextAlign.Start; 1 -> androidx.compose.ui.text.style.TextAlign.Center; else -> androidx.compose.ui.text.style.TextAlign.End },
                    )
                }
            }

            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Protéger par mot de passe", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (canEncrypt) "Chiffrement AES-256" else "Indisponible en 7z (choisissez ZIP)",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = encrypt && canEncrypt, onCheckedChange = { haptics.tick(); encrypt = it }, enabled = canEncrypt)
                    }
                    AnimatedVisibility(encrypt && canEncrypt, enter = expandVertically(bouncy()) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PasswordField(password, { password = it }, "Mot de passe", imeAction = androidx.compose.ui.text.input.ImeAction.Next)
                            PasswordField(
                                confirm, { confirm = it }, "Confirmer le mot de passe",
                                isError = mismatch, supportingText = if (mismatch) "Les mots de passe ne correspondent pas" else null,
                            )
                        }
                    }
                    if (!canEncrypt) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Fichio ne sait pas chiffrer les archives 7z.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text(
                "Destination : ${displayPath(destinationDir)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )

            Button(
                onClick = {
                    haptics.confirm()
                    val fileName = "$cleanName.${format.extension}"
                    val options = CompressOptions(
                        format, level.roundToInt(),
                        if (encrypt && canEncrypt) password.toCharArray() else null,
                    )
                    val paths = entries.map { it.path }
                    val id = context.launchTask(JobKind.COMPRESS, "Compression de $fileName", paths, destinationDir) {
                        var out = File(destinationDir, fileName)
                        if (out.exists()) {
                            val choice = askConflict(
                                FileEntry(out.path, fileName, false, estimate?.second ?: 0, System.currentTimeMillis()),
                                FileEntry(out.path, out.name, out.isDirectory, out.length(), out.lastModified()),
                            )
                            when (choice) {
                                ConflictChoice.SKIP -> return@launchTask
                                ConflictChoice.KEEP_BOTH -> out = ArchivePaths.uniqueFile(out)
                                ConflictChoice.REPLACE -> out.delete()
                            }
                        }
                        try {
                            ArchiveWriter.write(sources, out, options, asProgressCallbacks())
                        } finally {
                            context.container.fileSystems.notifyChanged(destinationDir)
                        }
                    }
                    if (id == null) android.widget.Toast.makeText(context, "Impossible de lancer la compression", android.widget.Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                enabled = valid,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Rounded.Archive, null); Spacer(Modifier.width(8.dp)); Text("Compresser", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
