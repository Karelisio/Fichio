package com.karelisio.fichio.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.karelisio.fichio.ui.components.PlaceholderScreen

/* CONTRACT STUBS — previews module. */

/** Image (pager + zoom), video/audio (Media3), PDF, text/code. */
@Composable
fun PreviewScreen(path: String, onBack: () -> Unit, onEdit: (String) -> Unit, onShowInFolder: (String) -> Unit) =
    PlaceholderScreen("Aperçu", onBack)

/** Preview of a content:// Uri received through ACTION_VIEW. */
@Composable
fun UriPreviewScreen(uri: String, mimeType: String?, onBack: () -> Unit) = PlaceholderScreen("Aperçu", onBack)

@Composable
fun EditorScreen(path: String, onBack: () -> Unit) = PlaceholderScreen("Éditeur", onBack)

/** Mini player shown above the bottom of the app while audio plays. */
@Composable
fun MiniPlayerBar(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {}
