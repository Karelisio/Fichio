package com.karelisio.fichio.search

import androidx.compose.runtime.Composable
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.ui.components.PlaceholderScreen

/** [scopePath] = current folder, or null for "everywhere". CONTRACT STUB. */
@Composable
fun SearchScreen(scopePath: String?, onOpen: (FileEntry) -> Unit, onBack: () -> Unit) = PlaceholderScreen("Recherche", onBack)
