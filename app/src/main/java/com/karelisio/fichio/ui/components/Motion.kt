package com.karelisio.fichio.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import com.karelisio.fichio.ui.theme.LocalHapticsEnabled
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Bouncy spring used across the app for micro-interactions. */
fun <T> bouncy() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
fun <T> snappy() = spring<T>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)

/**
 * Cascade entrance: item [index] slides up and fades in with a short stagger. Runs once per
 * [key]; only the first ~14 items are staggered so long lists stay instant.
 */
fun Modifier.cascadeIn(index: Int, key: Any? = Unit, enabled: Boolean = true): Modifier = composed {
    val reduce = LocalReduceMotion.current
    if (reduce || !enabled) return@composed this
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        if (progress.value < 1f) {
            delay((index.coerceAtMost(14) * 22L))
            progress.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 28.dp.toPx()
        val s = 0.94f + 0.06f * p
        scaleX = s; scaleY = s
    }
}

/** Shrinks slightly while pressed and springs back: the standard tap micro-interaction. */
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.94f): Modifier = composed {
    val isPressed by interaction.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    val scale by animateFloatAsState(if (isPressed && !reduce) pressed else 1f, bouncy(), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Springy "pop" every time [trigger] changes (e.g. a folder receiving a file). */
fun Modifier.popOn(trigger: Any?, amount: Float = 0.18f): Modifier = composed {
    val reduce = LocalReduceMotion.current
    val scale = remember { Animatable(1f) }
    var first = remember { booleanArrayOf(true) }
    LaunchedEffect(trigger) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (reduce) return@LaunchedEffect
        launch {
            scale.snapTo(1f + amount)
            scale.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = Spring.StiffnessMedium))
        }
    }
    graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/** Subtle haptics honouring the in-app setting. */
class Haptics(private val view: android.view.View, private val enabled: Boolean) {
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)
    fun click() = perform(HapticFeedbackConstants.CONTEXT_CLICK)
    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)
    fun confirm() = perform(HapticFeedbackConstants.CONFIRM)
    fun reject() = perform(HapticFeedbackConstants.REJECT)
    fun segment() = perform(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
    private fun perform(c: Int) {
        if (enabled) view.performHapticFeedback(c)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return remember(view, enabled) { Haptics(view, enabled) }
}

/** Number that rolls digit by digit when it changes (sizes, counters). */
@Composable
fun AnimatedCounter(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val reduce = LocalReduceMotion.current
    if (reduce) {
        Text(text, modifier, style = style); return
    }
    Row(modifier) {
        text.forEachIndexed { i, c ->
            AnimatedContent(
                targetState = c,
                transitionSpec = {
                    val up = (targetState.digitToIntOrNull() ?: 0) >= (initialState.digitToIntOrNull() ?: 0)
                    (slideInVertically(snappy()) { if (up) it else -it } + fadeIn()) togetherWith
                        (slideOutVertically(snappy()) { if (up) -it else it } + fadeOut())
                },
                label = "digit$i",
            ) { ch -> Text(ch.toString(), style = style) }
        }
    }
}
