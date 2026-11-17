package com.karelisio.fichio.ui.browser

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.karelisio.fichio.ui.components.PlaceholderScreen

@Composable
fun BrowserScreen(nav: NavHostController, initialPath: String?, newTab: Boolean) = PlaceholderScreen("Fichiers") { nav.popBackStack() }
