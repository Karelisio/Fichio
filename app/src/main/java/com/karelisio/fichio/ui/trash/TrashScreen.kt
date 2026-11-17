package com.karelisio.fichio.ui.trash

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.karelisio.fichio.data.db.TrashItemEntity
import com.karelisio.fichio.domain.file.formatBytes
import com.karelisio.fichio.domain.file.formatDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val viewModel: TrashViewModel = viewModel()
    val items by viewModel.items.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Retour")
                    }
                },
                title = { Text("Corbeille") },
                actions = {
                    if (items.isNotEmpty()) {
                        IconButton(onClick = viewModel::restoreAll) {
                            Icon(Icons.Outlined.Restore, contentDescription = "Tout restaurer")
                        }
                        IconButton(onClick = viewModel::emptyTrash) {
                            Icon(Icons.Outlined.DeleteForever, contentDescription = "Vider la corbeille")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.height(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "La corbeille est vide",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(items, key = { it.id }) { item ->
                    TrashRow(
                        item = item,
                        onRestore = { viewModel.restore(item) },
                        onDelete = { viewModel.delete(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrashRow(item: TrashItemEntity, onRestore: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRestore)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatBytes(item.sizeBytes)} · ${formatDate(item.deletedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                item.originalPath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        IconButton(onClick = onRestore) {
            Icon(Icons.Outlined.Restore, contentDescription = "Restaurer", tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.DeleteForever, contentDescription = "Supprimer définitivement", tint = MaterialTheme.colorScheme.error)
        }
    }
}
