package com.karelisio.fichio.apk

import androidx.compose.runtime.Composable
import com.karelisio.fichio.ui.components.PlaceholderScreen

/* CONTRACT STUBS — APK module. */

@Composable
fun ApkInfoScreen(path: String, onBack: () -> Unit) = PlaceholderScreen("APK", onBack)

/** Installed apps list → extract APK (splits bundled as .apks). */
@Composable
fun InstalledAppsScreen(onBack: () -> Unit, onOpenFolder: (String) -> Unit) = PlaceholderScreen("Applications", onBack)
