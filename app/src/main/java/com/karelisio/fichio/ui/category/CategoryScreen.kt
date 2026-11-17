package com.karelisio.fichio.ui.category

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.karelisio.fichio.core.model.FileCategory
import com.karelisio.fichio.ui.components.PlaceholderScreen

@Composable
fun CategoryScreen(nav: NavHostController, category: FileCategory) = PlaceholderScreen(category.label) { nav.popBackStack() }
