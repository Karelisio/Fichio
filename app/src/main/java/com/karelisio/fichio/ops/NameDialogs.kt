package com.karelisio.fichio.ops

import androidx.compose.ui.res.stringResource
import com.karelisio.fichio.R
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.ops.engine.Names
import com.karelisio.fichio.ui.components.popOn
import com.karelisio.fichio.ui.components.rememberHaptics
import kotlinx.coroutines.launch

/** Selects the base name (without extension) so typing replaces just that part. */
private fun initialValue(name: String, isDirectory: Boolean): TextFieldValue {
    val base = Names.split(name, isDirectory).first
    return TextFieldValue(name, TextRange(0, base.length))
}

@Composable
fun RenameDialog(entry: FileEntry, onDismiss: () -> Unit, onRenamed: (String) -> Unit = {}) {
    val ops = rememberOperations()
    NameDialog(
        title = stringResource(R.string.common_rename),
        icon = Icons.Rounded.DriveFileRenameOutline,
        polygon = MaterialShapes.Cookie9Sided,
        initial = initialValue(entry.name, entry.isDirectory),
        confirmLabel = stringResource(R.string.common_rename),
        unchanged = { it == entry.name },
        onDismiss = onDismiss,
        submit = { name -> ops.rename(entry.path, name) },
        onDone = onRenamed,
    )
}

@Composable
fun NewItemDialog(parent: String, folder: Boolean, onDismiss: () -> Unit, onCreated: (String) -> Unit = {}) {
    val ops = rememberOperations()
    var isFolder by remember { mutableStateOf(folder) }
    val defaultName = if (isFolder) stringResource(R.string.browser_new_folder) else stringResource(R.string.ops_new_file_name)
    NameDialog(
        title = if (isFolder) stringResource(R.string.browser_new_folder) else stringResource(R.string.browser_new_file),
        icon = if (isFolder) Icons.Rounded.CreateNewFolder else Icons.Rounded.NoteAdd,
        polygon = if (isFolder) MaterialShapes.Clover4Leaf else MaterialShapes.Cookie6Sided,
        initial = initialValue(defaultName, isFolder),
        resetKey = isFolder,
        confirmLabel = stringResource(R.string.browser_create),
        unchanged = { false },
        onDismiss = onDismiss,
        submit = { name -> if (isFolder) ops.createFolder(parent, name) else ops.createFile(parent, name) },
        onDone = onCreated,
        header = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleButton(
                    checked = isFolder,
                    onCheckedChange = { isFolder = true },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.common_folder)) }
                ToggleButton(
                    checked = !isFolder,
                    onCheckedChange = { isFolder = false },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.common_file)) }
            }
            Spacer(Modifier.height(16.dp))
        },
    )
}

@Composable
private fun NameDialog(
    title: String,
    icon: ImageVector,
    polygon: RoundedPolygon,
    initial: TextFieldValue,
    confirmLabel: String,
    unchanged: (String) -> Boolean,
    onDismiss: () -> Unit,
    submit: suspend (String) -> Result<String>,
    onDone: (String) -> Unit,
    resetKey: Any? = Unit,
    header: @Composable () -> Unit = {},
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var value by remember(resetKey) { mutableStateOf(initial) }
    var serverError by remember(resetKey) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var shake by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(resetKey) { runCatching { focus.requestFocus() } }

    val name = value.text.trim()
    val localError = if (value.text.isEmpty()) null else Names.validate(name)
    val error = localError ?: serverError
    val canSubmit = !busy && name.isNotEmpty() && localError == null && !unchanged(name)

    fun confirm() {
        if (!canSubmit) { haptics.reject(); shake++; return }
        busy = true
        scope.launch {
            submit(name)
                .onSuccess { haptics.confirm(); onDone(it); onDismiss() }
                .onFailure { haptics.reject(); serverError = it.message; shake++ }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                Modifier.size(56.dp).popOn(shake).clip(polygon.toShape()).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(icon, transitionSpec = { (scaleIn() + fadeIn()) togetherWith fadeOut() }, label = "icon") {
                    Icon(it, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        },
        title = { Text(title) },
        text = {
            Column {
                header()
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; serverError = null },
                    singleLine = true,
                    isError = error != null,
                    label = { Text(stringResource(R.string.common_name)) },
                    supportingText = { Text(error ?: " ") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = {
            Button(onClick = { confirm() }, enabled = canSubmit, shapes = ButtonDefaults.shapes()) {
                if (busy) {
                    LoadingIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
