package com.karelisio.fichio.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.karelisio.fichio.analysis.AnalysisScreen
import com.karelisio.fichio.apk.ApkInfoScreen
import com.karelisio.fichio.apk.InstalledAppsScreen
import com.karelisio.fichio.archive.ArchiveScreen
import com.karelisio.fichio.container
import com.karelisio.fichio.core.model.FileCategory
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.onboarding.OnboardingScreen
import com.karelisio.fichio.preview.EditorScreen
import com.karelisio.fichio.preview.PreviewScreen
import com.karelisio.fichio.preview.UriPreviewScreen
import com.karelisio.fichio.search.SearchScreen
import com.karelisio.fichio.settings.PermissionsScreen
import com.karelisio.fichio.settings.SettingsScreen
import com.karelisio.fichio.transfer.TransferScreen
import com.karelisio.fichio.trash.TrashScreen
import com.karelisio.fichio.ui.browser.BrowserScreen
import com.karelisio.fichio.search.SmartFolder
import com.karelisio.fichio.ui.category.CategoryScreen
import com.karelisio.fichio.ui.category.SmartFolderScreen
import com.karelisio.fichio.ui.category.TagScreen
import com.karelisio.fichio.ui.home.HomeScreen
import com.karelisio.fichio.ui.theme.LocalReduceMotion

private val enterSpring = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.9f, stiffness = 380f)

@Composable
fun FichioNavHost(nav: NavHostController, start: Route) {
    val reduce = LocalReduceMotion.current
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedScope provides this) {
            NavHost(
                navController = nav,
                startDestination = start,
                enterTransition = { if (reduce) fadeIn() else slideInHorizontally(enterSpring) { it / 4 } + fadeIn() },
                exitTransition = { if (reduce) fadeOut() else scaleOut(targetScale = 0.94f) + fadeOut() },
                popEnterTransition = { if (reduce) fadeIn() else scaleIn(initialScale = 0.94f) + fadeIn() },
                popExitTransition = { if (reduce) fadeOut() else slideOutHorizontally(enterSpring) { it / 4 } + fadeOut() },
            ) {
                graph(nav)
            }
        }
    }
}

/** Opens an entry at the right place (folder, preview, archive, APK, external app). */
fun NavHostController.openEntry(context: android.content.Context, entry: FileEntry) {
    val route = Opener.internalRoute(entry)
    if (entry.type != com.karelisio.fichio.core.model.FileType.FOLDER) context.container.recents.record(entry.path)
    if (route != null) navigate(route) else Opener.openExternally(context, entry.path)
}

private fun NavGraphBuilder.graph(nav: NavHostController) {
    val back: () -> Unit = { nav.popBackStack() }

    composable<Route.Onboarding> {
        Scoped {
            OnboardingScreen(onFinished = {
                nav.navigate(Route.Home) { popUpTo(Route.Onboarding) { inclusive = true } }
            })
        }
    }
    composable<Route.Home> {
        Scoped { HomeScreen(nav) }
    }
    composable<Route.Browser> { e ->
        val r = e.toRoute<Route.Browser>()
        Scoped { BrowserScreen(nav, r.path, r.newTab) }
    }
    composable<Route.Category> { e ->
        val r = e.toRoute<Route.Category>()
        Scoped { CategoryScreen(nav, FileCategory.valueOf(r.category)) }
    }
    composable<Route.Smart> { e ->
        val r = e.toRoute<Route.Smart>()
        val kind = runCatching { SmartFolder.valueOf(r.kind) }.getOrDefault(SmartFolder.SCREENSHOTS)
        Scoped { SmartFolderScreen(nav, kind) }
    }
    composable<Route.Tagged> { e ->
        val r = e.toRoute<Route.Tagged>()
        Scoped { TagScreen(nav, r.tagId) }
    }
    composable<Route.Search> { e ->
        val r = e.toRoute<Route.Search>()
        val ctx = LocalContext.current
        Scoped { SearchScreen(r.scopePath, onOpen = { nav.openEntry(ctx, it) }, onBack = back) }
    }
    composable<Route.Trash> { Scoped { TrashScreen(back) } }
    composable<Route.Preview> { e ->
        val r = e.toRoute<Route.Preview>()
        Scoped {
            PreviewScreen(
                r.path, onBack = back,
                onEdit = { nav.navigate(Route.Editor(it)) },
                onShowInFolder = { nav.navigate(Route.Browser(it)) },
            )
        }
    }
    composable<Route.UriPreview> { e ->
        val r = e.toRoute<Route.UriPreview>()
        Scoped { UriPreviewScreen(r.uri, r.mimeType, back) }
    }
    composable<Route.Editor> { e -> Scoped { EditorScreen(e.toRoute<Route.Editor>().path, back) } }
    composable<Route.Archive> { e ->
        Scoped { ArchiveScreen(e.toRoute<Route.Archive>().path, back, onOpenFolder = { nav.navigate(Route.Browser(it)) }) }
    }
    composable<Route.ApkInfo> { e -> Scoped { ApkInfoScreen(e.toRoute<Route.ApkInfo>().path, back) } }
    composable<Route.InstalledApps> { Scoped { InstalledAppsScreen(back, onOpenFolder = { nav.navigate(Route.Browser(it)) }) } }
    composable<Route.Analysis> { Scoped { AnalysisScreen(back, onOpenFolder = { nav.navigate(Route.Browser(it)) }) } }
    composable<Route.Transfer> { Scoped { TransferScreen(back) } }
    composable<Route.Settings> { Scoped { SettingsScreen(back, onOpenPermissions = { nav.navigate(Route.Permissions) }) } }
    composable<Route.Permissions> { Scoped { PermissionsScreen(back) } }
}

/** Exposes the destination's AnimatedContentScope for shared elements. */
@Composable
private fun androidx.compose.animation.AnimatedContentScope.Scoped(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNavAnimatedScope provides this, content = content)
}
