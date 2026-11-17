package com.karelisio.fichio.archive

import androidx.compose.runtime.Composable
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.ui.components.PlaceholderScreen

/* CONTRACT STUBS — archives module. */

/** Browse an archive like a folder; extract all or a selection. */
@Composable
fun ArchiveScreen(path: String, onBack: () -> Unit, onOpenFolder: (String) -> Unit) = PlaceholderScreen("Archive", onBack)

/** Create a zip (optional AES password) or 7z from [entries] into [destinationDir]. */
@Composable
fun CompressSheet(entries: List<FileEntry>, destinationDir: String, onDismiss: () -> Unit) {}

/** Extract [archivePath] (all entries) to a chosen folder. */
@Composable
fun ExtractSheet(archivePath: String, onDismiss: () -> Unit) {}
