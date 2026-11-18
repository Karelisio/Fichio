package com.karelisio.fichio.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.karelisio.fichio.R

/**
 * Accessibility of a selectable list item: announces "sélectionné" / "non sélectionné" and
 * exposes the long-press gesture (select) as a named action, so that TalkBack and Switch
 * Access users can enter the multi-selection without the gesture.
 */
@Composable
fun Modifier.selectionSemantics(
    selected: Boolean,
    onToggleSelection: () -> Unit,
    extraActions: List<CustomAccessibilityAction> = emptyList(),
): Modifier {
    val stateLabel = stringResource(if (selected) R.string.a11y_selected else R.string.a11y_not_selected)
    val actionLabel = stringResource(if (selected) R.string.a11y_deselect else R.string.common_select)
    return this.semantics {
        this.selected = selected
        stateDescription = stateLabel
        customActions = listOf(CustomAccessibilityAction(actionLabel) { onToggleSelection(); true }) + extraActions
    }
}
