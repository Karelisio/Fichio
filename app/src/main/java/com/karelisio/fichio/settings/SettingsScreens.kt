package com.karelisio.fichio.settings

import androidx.compose.runtime.Composable
import com.karelisio.fichio.ui.components.PlaceholderScreen

/* CONTRACT STUBS — settings module. */

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenPermissions: () -> Unit) = PlaceholderScreen("Réglages", onBack)

@Composable
fun PermissionsScreen(onBack: () -> Unit) = PlaceholderScreen("Autorisations", onBack)
