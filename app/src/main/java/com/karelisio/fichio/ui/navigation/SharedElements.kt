package com.karelisio.fichio.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.toIntRect
import androidx.compose.animation.core.VisibilityThreshold
import com.karelisio.fichio.ui.theme.LocalReduceMotion

val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Expressive spring for shared bounds (thumbnail → preview, category → list). */
val FichioBoundsTransform = BoundsTransform { _, _ ->
    spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = Rect.VisibilityThreshold)
}

/**
 * Shared element between navigation destinations. No-op when there is no shared scope (tests,
 * previews) or when animations are reduced.
 */
fun Modifier.sharedElementKey(key: String, bounds: Boolean = false): Modifier = composed {
    val shared = LocalSharedScope.current
    val anim = LocalNavAnimatedScope.current
    if (shared == null || anim == null || LocalReduceMotion.current) return@composed this
    with(shared) {
        if (bounds) this@composed.sharedBounds(
            rememberSharedContentState(key), anim,
            boundsTransform = FichioBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
        else this@composed.sharedElement(rememberSharedContentState(key), anim, boundsTransform = FichioBoundsTransform)
    }
}

/** Request coming from ACTION_GET_CONTENT / ACTION_OPEN_DOCUMENT: Fichio acts as a picker. */
@Immutable
data class PickerRequest(val mimeTypes: List<String>, val allowMultiple: Boolean)

val LocalPickerRequest = compositionLocalOf<PickerRequest?> { null }

/** Called with the chosen file paths when Fichio is used as a picker. */
val LocalPickerResult = compositionLocalOf<(List<String>) -> Unit> { {} }
