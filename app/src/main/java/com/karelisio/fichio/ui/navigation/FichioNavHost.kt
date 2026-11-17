package com.karelisio.fichio.ui.navigation

import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.karelisio.fichio.FichioApp
import com.karelisio.fichio.domain.file.FileCategory
import com.karelisio.fichio.ui.browser.BrowserScreen
import com.karelisio.fichio.ui.home.HomeScreen
import com.karelisio.fichio.ui.home.HomeViewModel
import com.karelisio.fichio.ui.search.SearchScreen
import com.karelisio.fichio.ui.trash.TrashScreen

private const val HOME = "home"
private const val BROWSER = "browser/{path}"
private const val TRASH = "trash"
private const val SEARCH = "search"

@Composable
fun FichioNavHost(app: FichioApp, startIntent: Intent?) {
    val navController = rememberNavController()

    LaunchedEffect(Unit) {
        val destination = deepLinkDestination(startIntent)
        if (destination != null) {
            navController.navigate(destination) { launchSingleTop = true }
        }
    }

    NavHost(navController = navController, startDestination = HOME) {
        composable(HOME) {
            val homeViewModel: HomeViewModel = viewModel()
            HomeScreen(
                viewModel = homeViewModel,
                onOpenFolder = { path -> navController.navigate(browserRoute(path)) },
                onOpenCategory = { category ->
                    // Category browsing is wired to the search index in a later step;
                    // for now fall back to the storage root.
                    navController.navigate(browserRoute(Environment.getExternalStorageDirectory().absolutePath))
                },
                onOpenTrash = { navController.navigate(TRASH) },
                onOpenAndroidData = { path -> navController.navigate(browserRoute(path)) },
                onOpenSearch = { navController.navigate(SEARCH) }
            )
        }

        composable(TRASH) {
            TrashScreen(onBack = { navController.popBackStack() })
        }

        composable(SEARCH) {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onOpenParent = { parent -> navController.navigate(browserRoute(parent)) }
            )
        }

        composable(
            route = BROWSER,
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) { backStackEntry ->
            val path = Uri.decode(backStackEntry.arguments?.getString("path") ?: "")
            BrowserScreen(
                path = path,
                onOpenFolder = { child -> navController.navigate(browserRoute(child)) },
                onBack = { navController.popBackStack() },
                onOpenFile = { /* previews come in a later step */ }
            )
        }
    }
}

private fun browserRoute(path: String): String = "browser/${Uri.encode(path)}"

/** Maps a launcher/deep-link intent to a navigation destination route. */
private fun deepLinkDestination(intent: Intent?): String? {
    val uri = intent?.data ?: return null
    if (uri.scheme == "fichio") {
        return when {
            uri.host == "storage" && uri.pathSegments.firstOrNull() == "internal" ->
                browserRoute(Environment.getExternalStorageDirectory().absolutePath)
            uri.host == "storage" && uri.pathSegments.firstOrNull() == "downloads" ->
                browserRoute(Environment.getExternalStorageDirectory().absolutePath + "/Download")
            else -> null
        }
    }
    if (intent.action == Intent.ACTION_VIEW && uri.scheme == "file") {
        val path = uri.path ?: return null
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        return browserRoute(parent)
    }
    return null
}
