package com.karelisio.fichio.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = SlateBluePrimary,
    onPrimary = SlateBlueOnPrimary,
    primaryContainer = SlateBluePrimaryContainer,
    onPrimaryContainer = SlateBlueOnPrimaryContainer,
    secondary = SlateBlueSecondary,
    onSecondary = SlateBlueOnSecondary,
    secondaryContainer = SlateBlueSecondaryContainer,
    onSecondaryContainer = SlateBlueOnSecondaryContainer,
    tertiary = SlateBlueTertiary,
    onTertiary = SlateBlueOnTertiary,
    tertiaryContainer = SlateBlueTertiaryContainer,
    onTertiaryContainer = SlateBlueOnTertiaryContainer,
    error = SlateBlueError,
    background = SlateBlueBackground,
    onBackground = SlateBlueOnBackground,
    surface = SlateBlueSurface,
    onSurface = SlateBlueOnSurface,
    surfaceVariant = SlateBlueSurfaceVariant,
    onSurfaceVariant = SlateBlueOnSurfaceVariant,
    outline = SlateBlueOutline
)

private val DarkColorScheme = darkColorScheme(
    primary = SlateBlueDarkPrimary,
    onPrimary = SlateBlueDarkOnPrimary,
    primaryContainer = SlateBlueDarkPrimaryContainer,
    onPrimaryContainer = SlateBlueDarkOnPrimaryContainer,
    secondary = SlateBlueDarkSecondary,
    onSecondary = SlateBlueDarkOnSecondary,
    secondaryContainer = SlateBlueDarkSecondaryContainer,
    onSecondaryContainer = SlateBlueDarkOnSecondaryContainer,
    tertiary = SlateBlueDarkTertiary,
    onTertiary = SlateBlueDarkOnTertiary,
    tertiaryContainer = SlateBlueDarkTertiaryContainer,
    onTertiaryContainer = SlateBlueDarkOnTertiaryContainer,
    error = SlateBlueDarkError,
    background = SlateBlueDarkBackground,
    onBackground = SlateBlueDarkOnBackground,
    surface = SlateBlueDarkSurface,
    onSurface = SlateBlueDarkOnSurface,
    surfaceVariant = SlateBlueDarkSurfaceVariant,
    onSurfaceVariant = SlateBlueDarkOnSurfaceVariant,
    outline = SlateBlueDarkOutline
)

@Composable
fun FichioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FichioTypography,
        shapes = FichioShapes,
        content = content
    )
}
