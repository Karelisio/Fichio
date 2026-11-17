package com.karelisio.fichio.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.karelisio.fichio.data.prefs.ThemeMode

/** True when the user disabled animations (system setting or in-app toggle). */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Whether subtle haptic feedback is enabled. */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

object FichioThemeExt {
    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

@Composable
fun fichioColorScheme(dark: Boolean, dynamic: Boolean): ColorScheme {
    val context = LocalContext.current
    return when {
        dynamic -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> SlateDark
        else -> SlateLight
    }
}

@Composable
fun FichioTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    reduceMotion: Boolean = false,
    haptics: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val systemNoAnim = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val noMotion = reduceMotion || systemNoAnim
    MaterialExpressiveTheme(
        colorScheme = fichioColorScheme(dark, dynamicColor),
        typography = FichioTypography,
        shapes = FichioShapes,
        motionScheme = if (noMotion) MotionScheme.standard() else MotionScheme.expressive(),
    ) {
        CompositionLocalProvider(
            LocalReduceMotion provides noMotion,
            LocalHapticsEnabled provides haptics,
            content = content,
        )
    }
}

/** Shorthand. */
val colors @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme
