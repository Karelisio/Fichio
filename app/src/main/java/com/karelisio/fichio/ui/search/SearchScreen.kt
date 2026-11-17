package com.karelisio.fichio.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.karelisio.fichio.data.db.SearchIndexEntity
import com.karelisio.fichio.domain.file.FileCategory
import com.karelisio.fichio.domain.file.formatBytes
import com.karelisio.fichio.domain.file.formatDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onBack: () -> Unit, onOpenParent: (String) -> Unit) {
    val viewModel: SearchViewModel = viewModel()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val typeFilter by viewModel.typeFilter.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Retour")
                        }
                    },
                    title = {
                        OutlinedTextField(
                            value = query,
                            onValueChange = viewModel::setQuery,
                            singleLine = true,
                            placeholder = { Text("Rechercher des fichiers…") },
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) }
                        )
                    }
                )
                FilterRow(typeFilter, viewModel::setTypeFilter)
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                query.isBlank() -> Hint("Tapez pour rechercher dans tout le stockage.")
                searching -> Hint("Recherche…")
                results.isEmpty() -> Hint("Aucun résultat.")
                else -> LazyColumn {
                    items(results, key = { it.path }) { entry ->
                        SearchRow(entry, onClick = { onOpenParent(entry.parent) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(selected: FileCategory?, onSelect: (FileCategory?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("Tous") }
        )
        FileCategory.entries.filter { it != FileCategory.OTHER }.forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(if (selected == category) null else category) },
                label = { Text(categoryLabel(category)) }
            )
        }
    }
}

@Composable
private fun SearchRow(entry: SearchIndexEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                entry.parent,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatBytes(entry.sizeBytes.toLongOrNull() ?: 0L),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                formatDate(entry.modifiedAt.toLongOrNull() ?: 0L),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun categoryLabel(category: FileCategory): String = when (category) {
    FileCategory.IMAGE -> "Images"
    FileCategory.VIDEO -> "Vidéos"
    FileCategory.AUDIO -> "Audio"
    FileCategory.DOCUMENT -> "Documents"
    FileCategory.APK -> "APK"
    FileCategory.ARCHIVE -> "Archives"
    FileCategory.OTHER -> "Autres"
}
