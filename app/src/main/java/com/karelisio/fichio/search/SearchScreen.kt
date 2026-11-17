@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package com.karelisio.fichio.search

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.data.db.IndexedFile
import com.karelisio.fichio.ui.components.AnimatedCounter
import com.karelisio.fichio.ui.components.BackButton
import com.karelisio.fichio.ui.components.EmptyState
import com.karelisio.fichio.ui.components.FileIcon
import com.karelisio.fichio.ui.components.FileThumbnail
import com.karelisio.fichio.ui.components.SectionHeader
import com.karelisio.fichio.ui.components.bouncy
import com.karelisio.fichio.ui.components.cascadeIn
import com.karelisio.fichio.ui.components.icon
import com.karelisio.fichio.ui.components.pressScale
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import com.karelisio.fichio.ui.theme.toneOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

// ---------------------------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------------------------

@Immutable
data class SearchUiState(
    val query: SearchQuery = SearchQuery(),
    val items: List<IndexedFile> = emptyList(),
    val scanning: Boolean = false,
    val fromIndex: Boolean = true,
    /** Increments with every new result set, used to replay the cascade. */
    val generation: Int = 0,
)

enum class SizeFilter(val label: String, val min: Long?, val max: Long?) {
    TINY("< 1 Mo", null, MB),
    SMALL("1 – 10 Mo", MB, 10 * MB),
    MEDIUM("10 – 100 Mo", 10 * MB, 100 * MB),
    LARGE("100 Mo – 1 Go", 100 * MB, GB),
    HUGE("> 1 Go", GB, null),
}

enum class DateFilter(val label: String) {
    TODAY("Aujourd'hui"), WEEK("7 derniers jours"), MONTH("30 derniers jours"), YEAR("Cette année"), OLDER("Plus d'un an");

    /** (after, before) bounds in millis. */
    fun bounds(now: Long = System.currentTimeMillis()): Pair<Long?, Long?> {
        val day = 24 * 3600_000L
        return when (this) {
            TODAY -> Calendar.getInstance().apply {
                timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis to null
            WEEK -> now - 7 * day to null
            MONTH -> now - 30 * day to null
            YEAR -> Calendar.getInstance().apply {
                timeInMillis = now; set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }.timeInMillis to null
            OLDER -> null to now - 365 * day
        }
    }
}

private const val MB = 1024L * 1024
private const val GB = 1024L * MB

fun FileType.label(): String = when (this) {
    FileType.FOLDER -> "Dossiers"
    FileType.IMAGE -> "Images"
    FileType.VIDEO -> "Vidéos"
    FileType.AUDIO -> "Audio"
    FileType.PDF -> "PDF"
    FileType.DOCUMENT -> "Documents"
    FileType.SPREADSHEET -> "Tableurs"
    FileType.PRESENTATION -> "Présentations"
    FileType.TEXT -> "Texte"
    FileType.CODE -> "Code"
    FileType.ARCHIVE -> "Archives"
    FileType.APK -> "APK"
    FileType.EBOOK -> "Livres"
    FileType.FONT -> "Polices"
    FileType.OTHER -> "Autres"
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(context: Context, initialScope: String?) : ViewModel() {
    private val repo = SearchRepository(context)
    private val history = SearchHistory(context)

    private val _query = MutableStateFlow(SearchQuery(scopePath = initialScope))
    val query: StateFlow<SearchQuery> = _query.asStateFlow()

    private val _history = MutableStateFlow(history.load())
    val recent: StateFlow<List<String>> = _history.asStateFlow()

    var size by mutableStateOf<SizeFilter?>(null); private set
    var date by mutableStateOf<DateFilter?>(null); private set
    var extensionSuggestions by mutableStateOf<List<String>>(emptyList()); private set

    private var gen = 0

    val state: StateFlow<SearchUiState> = _query
        .debounce { if (it.text.isBlank()) 0L else 120L }
        .distinctUntilChanged()
        .flatMapLatest { q -> repo.results(q).map { q to it } }
        .map { (q, out) -> SearchUiState(q, out.items, out.scanning, out.fromIndex, ++gen) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState(_query.value))

    val indexCount: StateFlow<Int> = repo.indexCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1)

    init {
        viewModelScope.launch { extensionSuggestions = repo.topExtensions() }
    }

    fun setText(text: String) = _query.update { it.copy(text = text) }
    fun setScope(path: String?) = _query.update { it.copy(scopePath = path) }
    fun toggleType(t: FileType) = _query.update { q -> q.copy(types = if (t in q.types) q.types - t else q.types + t) }
    fun clearTypes() = _query.update { it.copy(types = emptySet()) }
    fun toggleFolders() = _query.update { it.copy(includeFolders = !it.includeFolders) }
    fun toggleExtension(e: String) = _query.update { q ->
        val ext = e.trim().trimStart('.').lowercase()
        if (ext.isEmpty()) q else q.copy(extensions = if (ext in q.extensions) q.extensions - ext else q.extensions + ext)
    }
    fun clearExtensions() = _query.update { it.copy(extensions = emptySet()) }

    fun selectSize(f: SizeFilter?) {
        size = f
        _query.update { it.copy(minSize = f?.min, maxSize = f?.max) }
    }

    fun selectDate(f: DateFilter?) {
        date = f
        val (after, before) = f?.bounds() ?: (null to null)
        _query.update { it.copy(modifiedAfter = after, modifiedBefore = before) }
    }

    fun clearFilters() {
        size = null; date = null
        _query.update { SearchQuery(text = it.text, scopePath = it.scopePath) }
    }

    fun saveTerm(term: String) { _history.value = history.add(term) }
    fun forget(term: String) { _history.value = history.remove(term) }
    fun clearHistory() { history.clear(); _history.value = emptyList() }
}

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

/** [scopePath] = current folder, or null for "everywhere". */
@Composable
fun SearchScreen(scopePath: String?, onOpen: (FileEntry) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val vm: SearchViewModel = viewModel(key = "search:$scopePath") { SearchViewModel(context, scopePath) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val indexCount by vm.indexCount.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val textState = rememberTextFieldState(query.text)
    val listState = rememberLazyListState()

    LaunchedEffect(textState) {
        snapshotFlow { textState.text.toString() }.collect { vm.setText(it) }
    }
    // Scroll back to the top when a new query starts.
    LaunchedEffect(ui.query) { if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0) }
    // Hide the keyboard as soon as the user starts scrolling the results.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { if (it) keyboard?.hide() }
    }

    val open: (IndexedFile) -> Unit = { f ->
        haptics.click()
        if (query.text.isNotBlank()) vm.saveTerm(query.text)
        onOpen(f.toEntry())
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding(),
    ) {
        SearchField(
            state = textState,
            scoped = query.scopePath != null,
            onBack = onBack,
            onSubmit = {
                if (query.text.isNotBlank()) vm.saveTerm(query.text)
                focus.clearFocus()
            },
        )
        FilterRow(vm, query, initialScope = scopePath)
        StatusLine(ui, query, indexCount)

        Box(Modifier.fillMaxSize().imePadding()) {
            when {
                !query.isActive -> HistoryPane(
                    recent = recent,
                    onPick = { term -> textState.setTextAndPlaceCursorAtEnd(term); haptics.tick() },
                    onForget = vm::forget,
                    onClear = vm::clearHistory,
                )
                ui.items.isEmpty() && !ui.scanning && ui.query == query -> NoResults(
                    scoped = query.scopePath != null,
                    onSearchEverywhere = { vm.setScope(null) },
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    itemsIndexed(ui.items, key = { _, f -> f.path }) { i, f ->
                        ResultRow(
                            file = f,
                            terms = highlightTerms(ui.query.text),
                            onClick = { open(f) },
                            modifier = Modifier.animateItem().cascadeIn(i, key = ui.generation to f.path, enabled = i < 16),
                        )
                    }
                    item(key = "__footer") {
                        if (ui.items.size >= SearchSqlBuilder.LIMIT) {
                            Text(
                                "Seuls les ${SearchSqlBuilder.LIMIT} premiers résultats sont affichés. Affinez la recherche.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                            )
                        }
                        Spacer(Modifier.navigationBarsPadding())
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(state: TextFieldState, scoped: Boolean, onBack: () -> Unit, onSubmit: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainerHigh,
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(60.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            BackButton(onBack)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    state = state,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = cs.onSurface, fontWeight = FontWeight.Normal),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    onKeyboardAction = { onSubmit() },
                    decorator = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (state.text.isEmpty()) {
                                Text(
                                    if (scoped) "Rechercher dans ce dossier" else "Rechercher des fichiers",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                                    color = cs.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            AnimatedContent(
                targetState = state.text.isNotEmpty(),
                transitionSpec = { (scaleIn(bouncy()) + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                label = "clear",
            ) { has ->
                if (has) {
                    IconButton(onClick = { state.clearText() }) { Icon(Icons.Rounded.Close, "Effacer") }
                } else {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(vm: SearchViewModel, query: SearchQuery, initialScope: String?) {
    val haptics = rememberHaptics()
    var typeSheet by remember { mutableStateOf(false) }
    var extSheet by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }
    var dateMenu by remember { mutableStateOf(false) }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (initialScope != null) {
            item(key = "scope") {
                val here = query.scopePath != null
                FilterChip(
                    selected = here,
                    onClick = { haptics.tick(); vm.setScope(if (here) null else initialScope) },
                    label = { Text(if (here) "Ce dossier" else "Partout") },
                    leadingIcon = {
                        AnimatedContent(here, label = "scopeIcon") { h ->
                            Icon(if (h) Icons.Rounded.FolderOpen else Icons.Rounded.Public, null, Modifier.size(18.dp))
                        }
                    },
                )
            }
        }
        item(key = "type") {
            val sel = query.types
            FilterChip(
                selected = sel.isNotEmpty(),
                onClick = { haptics.tick(); typeSheet = true },
                label = {
                    Text(
                        when (sel.size) {
                            0 -> "Type"
                            1 -> sel.first().label()
                            else -> "${sel.first().label()} +${sel.size - 1}"
                        },
                    )
                },
                leadingIcon = {
                    if (sel.size == 1) FileIcon(sel.first(), size = 20.dp)
                    else Icon(Icons.Rounded.Category, null, Modifier.size(18.dp))
                },
                trailingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp)) },
            )
        }
        item(key = "size") {
            Box {
                FilterChip(
                    selected = vm.size != null,
                    onClick = { haptics.tick(); sizeMenu = true },
                    label = { Text(vm.size?.label ?: "Taille") },
                    leadingIcon = { Icon(Icons.Rounded.Straighten, null, Modifier.size(18.dp)) },
                    trailingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                    SizeFilter.entries.forEach { f ->
                        DropdownMenuItem(
                            text = { Text(f.label) },
                            onClick = { vm.selectSize(if (vm.size == f) null else f); sizeMenu = false },
                            trailingIcon = { if (vm.size == f) Icon(Icons.Rounded.Check, null) },
                        )
                    }
                    if (vm.size != null) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Toutes les tailles") }, onClick = { vm.selectSize(null); sizeMenu = false })
                    }
                }
            }
        }
        item(key = "date") {
            Box {
                FilterChip(
                    selected = vm.date != null,
                    onClick = { haptics.tick(); dateMenu = true },
                    label = { Text(vm.date?.label ?: "Date") },
                    leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(18.dp)) },
                    trailingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = dateMenu, onDismissRequest = { dateMenu = false }) {
                    DateFilter.entries.forEach { f ->
                        DropdownMenuItem(
                            text = { Text(f.label) },
                            onClick = { vm.selectDate(if (vm.date == f) null else f); dateMenu = false },
                            trailingIcon = { if (vm.date == f) Icon(Icons.Rounded.Check, null) },
                        )
                    }
                    if (vm.date != null) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Toutes les dates") }, onClick = { vm.selectDate(null); dateMenu = false })
                    }
                }
            }
        }
        item(key = "ext") {
            val ext = query.extensions
            FilterChip(
                selected = ext.isNotEmpty(),
                onClick = { haptics.tick(); extSheet = true },
                label = { Text(if (ext.isEmpty()) "Extension" else ext.sorted().joinToString(", ") { ".$it" }, maxLines = 1) },
                leadingIcon = { Icon(Icons.Rounded.Tag, null, Modifier.size(18.dp)) },
                trailingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp)) },
            )
        }
        item(key = "folders") {
            FilterChip(
                selected = !query.includeFolders,
                onClick = { haptics.tick(); vm.toggleFolders() },
                label = { Text("Fichiers seulement") },
                leadingIcon = {
                    if (!query.includeFolders) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                    else Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp))
                },
            )
        }
        if (query.hasFilters) {
            item(key = "reset") {
                InputChip(
                    selected = false,
                    onClick = { haptics.confirm(); vm.clearFilters() },
                    label = { Text("Réinitialiser") },
                    trailingIcon = { Icon(Icons.Rounded.Close, null, Modifier.size(18.dp)) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    if (typeSheet) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { typeSheet = false }, sheetState = sheet) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Types de fichiers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (query.types.isNotEmpty()) TextButton(onClick = vm::clearTypes) { Text("Tout effacer") }
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FILTER_TYPES.forEach { t ->
                        val selected = t in query.types
                        val tone = toneOf(t)
                        FilterChip(
                            selected = selected,
                            onClick = { haptics.tick(); vm.toggleType(t) },
                            label = { Text(t.label()) },
                            leadingIcon = { FileIcon(t, size = 22.dp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = tone.container,
                                selectedLabelColor = tone.content,
                            ),
                            modifier = Modifier.height(40.dp),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = { typeSheet = false }, modifier = Modifier.fillMaxWidth()) { Text("Afficher les résultats") }
            }
        }
    }

    if (extSheet) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var input by rememberSaveable { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { extSheet = false }, sheetState = sheet) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Extensions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (query.extensions.isNotEmpty()) TextButton(onClick = vm::clearExtensions) { Text("Tout effacer") }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.replace(" ", "") },
                    label = { Text("Ajouter une extension (ex. : pdf)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                        if (input.isNotBlank()) { vm.toggleExtension(input); input = "" }
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                val all = (query.extensions + vm.extensionSuggestions).distinct()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    all.forEach { e ->
                        val t = com.karelisio.fichio.core.model.FileTypes.typeOf(e)
                        FilterChip(
                            selected = e in query.extensions,
                            onClick = { haptics.tick(); vm.toggleExtension(e) },
                            label = { Text(".$e") },
                            leadingIcon = { Icon(t.icon(), null, Modifier.size(18.dp), tint = toneOf(t).content) },
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = { extSheet = false }, modifier = Modifier.fillMaxWidth()) { Text("Afficher les résultats") }
            }
        }
    }
}

private val FILTER_TYPES = listOf(
    FileType.IMAGE, FileType.VIDEO, FileType.AUDIO, FileType.PDF, FileType.DOCUMENT, FileType.SPREADSHEET,
    FileType.PRESENTATION, FileType.TEXT, FileType.CODE, FileType.ARCHIVE, FileType.APK, FileType.EBOOK,
    FileType.FONT, FileType.FOLDER,
)

@Composable
private fun StatusLine(ui: SearchUiState, query: SearchQuery, indexCount: Int) {
    AnimatedVisibility(
        visible = query.isActive,
        enter = expandVertically(bouncy()) + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val n = ui.items.size
            AnimatedCounter(
                text = if (n >= SearchSqlBuilder.LIMIT) "${SearchSqlBuilder.LIMIT}+" else n.toString(),
                style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (n <= 1) "résultat" else "résultats",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(ui.scanning, enter = scaleIn(bouncy()) + fadeIn(), exit = scaleOut() + fadeOut()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recherche directe…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    LoadingIndicator(Modifier.size(28.dp))
                }
            }
            if (!ui.scanning && !ui.fromIndex) {
                Icon(Icons.Rounded.TravelExplore, "Recherche sans index", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            } else if (!ui.scanning && indexCount == 0) {
                Text("Indexation en cours…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ResultRow(file: IndexedFile, terms: List<String>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val entry = remember(file) { file.toEntry() }
    val cs = MaterialTheme.colorScheme
    val highlight = SpanStyle(background = cs.primaryContainer, color = cs.onPrimaryContainer, fontWeight = FontWeight.Bold)
    val name = remember(file.name, terms, highlight) { highlightName(file.name, terms, highlight) }
    Row(
        modifier
            .fillMaxWidth()
            .pressScale(interaction, 0.97f)
            .clip(MaterialTheme.shapes.large)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumbnail(entry, size = 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(
                displayParent(file.parent),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (!file.isDirectory) {
                Text(Format.size(file.size), style = MaterialTheme.typography.labelMedium, color = cs.onSurface)
            }
            Text(Format.relative(file.modified), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

private fun displayParent(parent: String): String {
    val primary = runCatching { StoragePaths.primary }.getOrDefault("")
    return when {
        primary.isNotEmpty() && parent == primary -> "Stockage interne"
        primary.isNotEmpty() && parent.startsWith("$primary/") -> "Interne › " + parent.removePrefix("$primary/").replace("/", " › ")
        else -> parent
    }
}

/** Terms to highlight: the whole text plus its tokens (accent/case insensitive). */
fun highlightTerms(text: String): List<String> {
    val t = SearchSqlBuilder.normalize(text.trim())
    if (t.isEmpty()) return emptyList()
    return (listOf(t) + SearchSqlBuilder.tokenize(t).map { SearchSqlBuilder.normalize(it) }).distinct().sortedByDescending { it.length }
}

/** Builds the name with every match of [terms] styled, matching accent-insensitively. */
fun highlightName(name: String, terms: List<String>, style: SpanStyle): AnnotatedString {
    if (terms.isEmpty()) return AnnotatedString(name)
    // Normalised text + index map back to the original characters.
    val norm = StringBuilder()
    val map = ArrayList<Int>()
    name.forEachIndexed { i, c ->
        val n = SearchSqlBuilder.normalize(c.toString())
        n.forEach { ch -> norm.append(ch); map += i }
    }
    val marked = BooleanArray(name.length)
    val ns = norm.toString()
    for (term in terms) {
        var from = 0
        while (true) {
            val idx = ns.indexOf(term, from)
            if (idx < 0) break
            for (k in idx until idx + term.length) map.getOrNull(k)?.let { marked[it] = true }
            from = idx + term.length.coerceAtLeast(1)
        }
    }
    return buildAnnotatedString {
        append(name)
        var i = 0
        while (i < name.length) {
            if (marked[i]) {
                var j = i
                while (j < name.length && marked[j]) j++
                addStyle(style, i, j)
                i = j
            } else i++
        }
    }
}

@Composable
private fun HistoryPane(recent: List<String>, onPick: (String) -> Unit, onForget: (String) -> Unit, onClear: () -> Unit) {
    if (recent.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.TravelExplore,
            title = "Trouvez n'importe quel fichier",
            message = "Tapez un nom, ou utilisez les filtres pour lister par type, taille ou date.",
            modifier = Modifier.padding(top = 48.dp),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "__h") {
            SectionHeader("Recherches récentes", trailing = { TextButton(onClick = onClear) { Text("Effacer") } })
        }
        itemsIndexed(recent, key = { _, t -> t }) { i, term ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .cascadeIn(i, key = term)
                    .clickable { onPick(term) }
                    .padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text(term, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onPick(term) }) { Icon(Icons.Rounded.NorthWest, "Utiliser", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { onForget(term) }) { Icon(Icons.Rounded.Close, "Retirer", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun NoResults(scoped: Boolean, onSearchEverywhere: () -> Unit) {
    EmptyState(
        icon = Icons.Rounded.SearchOff,
        title = "Aucun résultat",
        message = if (scoped) "Rien dans ce dossier. Essayez de chercher partout ou de retirer des filtres."
        else "Vérifiez l'orthographe ou retirez des filtres.",
        actionLabel = if (scoped) "Chercher partout" else null,
        onAction = if (scoped) onSearchEverywhere else null,
        modifier = Modifier.padding(top = 48.dp),
    )
}
