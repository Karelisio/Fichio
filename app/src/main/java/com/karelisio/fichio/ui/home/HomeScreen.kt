package com.karelisio.fichio.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karelisio.fichio.domain.file.FileCategory
import com.karelisio.fichio.domain.file.FileItem
import com.karelisio.fichio.domain.file.StorageStats
import com.karelisio.fichio.domain.file.formatBytes
import com.karelisio.fichio.ui.components.FileThumbnail
import com.karelisio.fichio.ui.components.SpaceGauge
import com.karelisio.fichio.ui.components.icon
import com.karelisio.fichio.ui.components.tint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenFolder: (String) -> Unit,
    onOpenCategory: (FileCategory) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenAndroidData: (String) -> Unit,
    onOpenSearch: () -> Unit
) {
    val stats by viewModel.storageStats.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val recents by viewModel.recentFiles.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fichio", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Outlined.Search, contentDescription = "Rechercher")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { GaugeSection(stats, categories) }
            item { QuickAccessSection(onOpenFolder, onOpenAndroidData, onOpenTrash) }
            item { CategorySection(categories, onOpenCategory) }
            if (recents.isNotEmpty()) {
                item { RecentSection(recents, onOpenFolder) }
            }
            if (favorites.isNotEmpty()) {
                item { FavoritesSection(favorites, onOpenFolder) }
            }
        }
    }
}

@Composable
private fun GaugeSection(stats: StorageStats?, categories: List<com.karelisio.fichio.domain.file.CategoryUsage>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            SpaceGauge(stats = stats, categories = categories, diameter = 200.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (stats != null) formatBytes(stats.freeBytes) else "—",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "libre",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (stats != null) {
                    Text(
                        text = "${formatBytes(stats.usedBytes)} utilisé sur ${formatBytes(stats.totalBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickAccessSection(
    onOpenFolder: (String) -> Unit,
    onOpenAndroidData: (String) -> Unit,
    onOpenTrash: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle("Accès rapide")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                QuickAccessTile(Icons.Outlined.SdCard, "Stockage interne", "Stockage") {
                    onOpenFolder("/storage/emulated/0")
                }
            }
            item {
                QuickAccessTile(Icons.Outlined.Download, "Téléchargements", "Download") {
                    onOpenFolder("/storage/emulated/0/Download")
                }
            }
            item {
                QuickAccessTile(Icons.Outlined.Folder, "Android/data", "Android") {
                    onOpenAndroidData("/storage/emulated/0/Android/data")
                }
            }
            item {
                QuickAccessTile(Icons.Outlined.Folder, "Android/obb", "Android") {
                    onOpenAndroidData("/storage/emulated/0/Android/obb")
                }
            }
            item {
                QuickAccessTile(Icons.Outlined.Delete, "Corbeille", "Fichio") {
                    onOpenTrash()
                }
            }
        }
    }
}

@Composable
private fun QuickAccessTile(
    icon: ImageVector,
    label: String,
    sublabel: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CategorySection(
    categories: List<com.karelisio.fichio.domain.file.CategoryUsage>,
    onOpenCategory: (FileCategory) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle("Catégories")
        val known = FileCategory.entries.filter { it != FileCategory.OTHER }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            known.forEach { category ->
                val usage = categories.firstOrNull { it.category == category }
                CategoryTile(
                    category = category,
                    count = usage?.count ?: 0,
                    bytes = usage?.bytes ?: 0L,
                    onClick = { onOpenCategory(category) }
                )
            }
        }
    }
}

@Composable
private fun CategoryTile(
    category: FileCategory,
    count: Int,
    bytes: Long,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(88.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = com.karelisio.fichio.ui.theme.CategoryTileShape,
            color = category.tint().copy(alpha = 0.14f),
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(category.icon(), contentDescription = null, tint = category.tint())
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(categoryLabel(category), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$count · ${formatBytes(bytes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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

@Composable
private fun RecentSection(recents: List<FileItem>, onOpenFolder: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle("Fichiers récents")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(recents) { item ->
                RecentCard(item, onClick = { onOpenFolder(item.path) })
            }
        }
    }
}

@Composable
private fun RecentCard(item: FileItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
    ) {
        FileThumbnail(
            file = item.file,
            isDirectory = item.isDirectory,
            extension = item.extension,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            cornerRadius = 16.dp
        )
        Spacer(Modifier.height(6.dp))
        Text(item.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FavoritesSection(
    favorites: List<com.karelisio.fichio.data.db.FavoriteEntity>,
    onOpenFolder: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle("Favoris")
        favorites.forEach { fav ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenFolder(fav.path) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(fav.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fav.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Outlined.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}
