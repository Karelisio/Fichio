package com.karelisio.fichio.ops

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.karelisio.fichio.core.model.FileEntry

/* CONTRACT STUBS — UI of the operations module. */

/** Global host (placed once at the app root): conflict dialog for running jobs. */
@Composable
fun OperationsHost() {}

/** Compact animated pill showing running jobs; hidden when idle. */
@Composable
fun OperationsIndicator(onClick: () -> Unit, modifier: Modifier = Modifier) {}

/** Bottom sheet listing the queue with pause / resume / cancel. */
@Composable
fun OperationsSheet(onDismiss: () -> Unit) {}

@Composable
fun RenameDialog(entry: FileEntry, onDismiss: () -> Unit, onRenamed: (String) -> Unit = {}) {}

@Composable
fun NewItemDialog(parent: String, folder: Boolean, onDismiss: () -> Unit, onCreated: (String) -> Unit = {}) {}

@Composable
fun BatchRenameSheet(entries: List<FileEntry>, onDismiss: () -> Unit) {}

/** Size, dates, path, item count; MD5 / SHA-256 on demand. */
@Composable
fun PropertiesSheet(entries: List<FileEntry>, onDismiss: () -> Unit) {}
