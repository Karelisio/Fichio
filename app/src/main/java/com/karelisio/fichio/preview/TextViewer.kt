@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.karelisio.fichio.preview

import androidx.compose.foundation.layout.heightIn
import com.karelisio.fichio.core.util.Strings
import androidx.compose.ui.res.stringResource
import com.karelisio.fichio.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ColorScheme
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.ui.components.EmptyState
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.theme.MonoStyle
import com.karelisio.fichio.ui.theme.toneFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import java.io.InputStream

@Immutable
private class LoadedText(val doc: TextDocument, val states: IntArray, val lang: Language)

/** Maps highlighter tokens to colours derived from the current scheme. */
@Immutable
class SyntaxPalette(cs: ColorScheme) {
    private val styles: Map<TokenKind, SpanStyle> = mapOf(
        TokenKind.KEYWORD to SpanStyle(color = cs.primary, fontWeight = FontWeight.SemiBold),
        TokenKind.TYPE to SpanStyle(color = cs.tertiary),
        TokenKind.STRING to SpanStyle(color = cs.toneFor(FileType.SPREADSHEET).content),
        TokenKind.COMMENT to SpanStyle(color = cs.onSurfaceVariant.copy(alpha = 0.72f), fontStyle = FontStyle.Italic),
        TokenKind.NUMBER to SpanStyle(color = cs.toneFor(FileType.AUDIO).content),
        TokenKind.TAG to SpanStyle(color = cs.primary, fontWeight = FontWeight.SemiBold),
        TokenKind.ATTRIBUTE to SpanStyle(color = cs.tertiary),
        TokenKind.KEY to SpanStyle(color = cs.secondary, fontWeight = FontWeight.SemiBold),
        TokenKind.ANNOTATION to SpanStyle(color = cs.toneFor(FileType.VIDEO).content),
        TokenKind.PUNCTUATION to SpanStyle(color = cs.onSurfaceVariant),
        TokenKind.COLUMN to SpanStyle(color = cs.tertiary, background = cs.tertiaryContainer.copy(alpha = 0.35f)),
    )

    fun of(kind: TokenKind): SpanStyle = styles.getValue(kind)
}

/**
 * Read-only text/code viewer: streaming load (≤ 5 Mo), line numbers, syntax colouring, in-file
 * search with highlighted matches, soft-wrap toggle.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun TextViewer(
    name: String,
    extension: String,
    open: () -> InputStream,
    modifier: Modifier = Modifier,
    searchOpen: Boolean,
    onCloseSearch: () -> Unit,
    wrap: Boolean,
    onInfo: (lines: Int, truncated: Boolean, charset: String) -> Unit = { _, _, _ -> },
) {
    val loaded by produceState<Result<LoadedText>?>(null, name, open) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val (bytes, truncated) = TextLoader.readBounded(open())
                if (TextLoader.looksBinary(bytes)) error("binary")
                val (text, cs) = TextLoader.decode(bytes, truncated)
                val doc = TextLoader.toDocument(text, truncated, cs)
                val lang = SyntaxHighlighter.languageFor(extension)
                LoadedText(doc, SyntaxHighlighter.startStates(doc.rows, lang), lang)
            }
        }
    }
    LaunchedEffect(loaded) {
        loaded?.getOrNull()?.let { onInfo(it.doc.lineCount, it.doc.truncated, it.doc.charset.displayName()) }
    }
    Box(modifier.fillMaxSize()) {
        when (val l = loaded) {
            null -> LoadingIndicator(Modifier.align(Alignment.Center))
            else -> l.fold(
                onSuccess = { TextBody(it, searchOpen, onCloseSearch, wrap) },
                onFailure = {
                    EmptyState(
                        icon = Icons.Rounded.Search,
                        title = if (it.message == "binary") stringResource(R.string.text_binary) else stringResource(R.string.preview_read_failed),
                        message = if (it.message == "binary") stringResource(R.string.text_not_text, name) else it.message,
                        modifier = Modifier.align(Alignment.Center),
                    )
                },
            )
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun TextBody(t: LoadedText, searchOpen: Boolean, onCloseSearch: () -> Unit, wrap: Boolean) {
    val doc = t.doc
    val cs = MaterialTheme.colorScheme
    val palette = remember(cs) { SyntaxPalette(cs) }
    val list = rememberLazyListState()
    val hScroll = rememberScrollState()
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val charWidth = remember(measurer) { measurer.measure(AnnotatedString("0000000000"), MonoStyle).size.width / 10f }
    val digits = doc.lineCount.toString().length.coerceAtLeast(2)
    val gutter = with(density) { (charWidth * digits).toDp() } + 20.dp
    val contentWidth = with(density) { (charWidth * (doc.longestRow + 2)).toDp() } + gutter + 16.dp

    // Search.
    val queryState = rememberTextFieldState()
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }
    var current by remember { mutableIntStateOf(0) }
    LaunchedEffect(queryState) {
        snapshotFlow { queryState.text.toString() }.debounce(150).collect { q ->
            query = q
            matches = withContext(Dispatchers.Default) { TextLoader.findAll(doc.rows, q) }
            current = 0
        }
    }
    LaunchedEffect(searchOpen) { if (!searchOpen) { query = ""; matches = emptyList() } }
    LaunchedEffect(matches, current) {
        val m = matches.getOrNull(current) ?: return@LaunchedEffect
        list.animateScrollToItem(m.first, scrollOffset = -with(density) { 96.dp.roundToPx() })
        if (!wrap) hScroll.animateScrollTo((m.second * charWidth - with(density) { 48.dp.toPx() }).toInt().coerceAtLeast(0))
    }
    val matchesByRow = remember(matches) { matches.withIndex().groupBy({ it.value.first }, { it.index to it.value.second }) }
    val hitStyle = SpanStyle(background = cs.tertiaryContainer, color = cs.onTertiaryContainer)
    val currentStyle = SpanStyle(background = cs.primary, color = cs.onPrimary, fontWeight = FontWeight.Bold)

    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(searchOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Surface(
                color = cs.surfaceContainerHigh, shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).heightIn(min = 52.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
                    Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        state = queryState,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        onKeyboardAction = { if (matches.isNotEmpty()) current = (current + 1) % matches.size },
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        decorator = { inner ->
                            Box {
                                if (queryState.text.isEmpty()) Text(stringResource(R.string.preview_search_in_file), color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                                inner()
                            }
                        },
                    )
                    Text(
                        when {
                            query.isEmpty() -> ""
                            matches.isEmpty() -> "0"
                            else -> "${current + 1}/${matches.size}${if (matches.size >= 10_000) "+" else ""}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (query.isNotEmpty() && matches.isEmpty()) cs.error else cs.onSurfaceVariant,
                    )
                    IconButton(enabled = matches.isNotEmpty(), onClick = { haptics.tick(); current = (current - 1 + matches.size) % matches.size }) {
                        Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.audio_previous))
                    }
                    IconButton(enabled = matches.isNotEmpty(), onClick = { haptics.tick(); current = (current + 1) % matches.size }) {
                        Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.audio_next))
                    }
                    IconButton(onClick = onCloseSearch) { Icon(Icons.Rounded.Close, stringResource(R.string.text_close_search)) }
                }
            }
        }
        if (doc.truncated) {
            Surface(color = cs.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.text_truncated, Format.size(TextLoader.VIEW_LIMIT)),
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        val listModifier = if (wrap) Modifier.fillMaxSize() else Modifier.fillMaxSize().horizontalScroll(hScroll)
        SelectionContainer(Modifier.weight(1f).imePadding()) {
            LazyColumn(
                state = list,
                modifier = listModifier,
                contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
            ) {
                items(doc.rows.size, key = { it }) { i ->
                    val row = doc.rows[i]
                    val hits = matchesByRow[i]
                    val text = remember(i, row, palette, hits, current) {
                        buildAnnotatedString {
                            append(row)
                            for (s in SyntaxHighlighter.highlightLine(row, t.lang, t.states[i]).spans) {
                                if (s.end > s.start && s.end <= row.length) addStyle(palette.of(s.kind), s.start, s.end)
                            }
                            hits?.forEach { (idx, start) ->
                                val end = (start + query.length).coerceAtMost(row.length)
                                if (end > start) addStyle(if (idx == current) currentStyle else hitStyle, start, end)
                            }
                        }
                    }
                    Row(if (wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = contentWidth)) {
                        val n = doc.lineNumbers[i]
                        Text(
                            if (n > 0) n.toString() else "↪",
                            style = MonoStyle.copy(color = if (hits != null) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.55f)),
                            modifier = Modifier.width(gutter).padding(start = 8.dp, end = 12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            softWrap = false,
                        )
                        Text(
                            text,
                            style = MonoStyle.copy(color = cs.onSurface),
                            softWrap = wrap,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** stringResource(R.string.text_edit) button state: editing needs a small file and a writable backend. */
internal fun editBlockReason(size: Long, restricted: Boolean, backendAvailable: Boolean): String? = when {
    size > TextLoader.EDIT_LIMIT -> Strings.get(R.string.preview_too_big_editor, Format.size(TextLoader.EDIT_LIMIT))
    restricted && !backendAvailable -> Strings.get(R.string.text_needs_shevery)
    else -> null
}

@Composable
internal fun WrapToggle(wrap: Boolean, onToggle: () -> Unit) {
    IconToggleButton(checked = wrap, onCheckedChange = { onToggle() }) {
        Icon(Icons.AutoMirrored.Rounded.WrapText, if (wrap) stringResource(R.string.text_wrap_off) else stringResource(R.string.text_wrap_on),
            tint = if (wrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun EditButton(enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 14.dp)) {
        Icon(Icons.Rounded.Edit, null)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.text_edit))
    }
}
