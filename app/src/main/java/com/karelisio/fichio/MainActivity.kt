package com.karelisio.fichio

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AnticipateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.karelisio.fichio.data.prefs.FichioSettings
import com.karelisio.fichio.ops.OperationsHost
import com.karelisio.fichio.shortcuts.Shortcuts
import com.karelisio.fichio.ui.navigation.FichioNavHost
import com.karelisio.fichio.ui.navigation.LocalPickerRequest
import com.karelisio.fichio.ui.navigation.LocalPickerResult
import com.karelisio.fichio.ui.navigation.Opener
import com.karelisio.fichio.ui.navigation.PickerRequest
import com.karelisio.fichio.ui.navigation.Route
import com.karelisio.fichio.ui.theme.FichioTheme
import com.karelisio.fichio.update.UpdateHost
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking

open class MainActivity : ComponentActivity() {

    /** Routes requested by new intents (shortcuts, widget, ACTION_VIEW) while running. */
    private val pendingRoutes = MutableSharedFlow<Route>(extraBufferCapacity = 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        var ready = false
        splash.setKeepOnScreenCondition { !ready }
        splash.setOnExitAnimationListener { provider ->
            val icon: View? = runCatching { provider.iconView }.getOrNull()
            if (icon == null) { provider.remove(); return@setOnExitAnimationListener }
            val scaleX = ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 1.25f, 0f)
            val scaleY = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 1.25f, 0f)
            val fade = ObjectAnimator.ofFloat(provider.view, View.ALPHA, 1f, 0f)
            AnimatorSet().apply {
                playTogether(scaleX, scaleY, fade)
                duration = 420
                interpolator = AnticipateInterpolator(1.4f)
                doOnEndCompat { provider.remove() }
                start()
            }
        }

        val app = container
        // First settings read is tiny and needed to pick the start destination.
        val initial = runBlocking { app.settings.current() }
        val picker = pickerRequestOf(intent)
        val startRoute: Route = when {
            !initial.onboardingDone && picker == null -> Route.Onboarding
            else -> routeFor(intent) ?: if (picker != null) Route.Browser() else Route.Home
        }
        ready = true

        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(initial)
            FichioTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                reduceMotion = settings.reduceMotion,
                haptics = settings.haptics,
            ) {
                val nav = rememberNavController()
                LaunchedEffect(Unit) {
                    pendingRoutes.collect { nav.navigate(it) }
                }
                CompositionLocalProvider(
                    LocalPickerRequest provides picker,
                    LocalPickerResult provides { paths -> finishWithPicked(paths) },
                ) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        FichioNavHost(nav, startRoute)
                        OperationsHost()
                        if (picker == null) UpdateHost()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeFor(intent)?.let { pendingRoutes.tryEmit(it) }
    }

    /** Maps launcher shortcuts, widget clicks and ACTION_VIEW to a destination. */
    private fun routeFor(intent: Intent?): Route? {
        intent ?: return null
        intent.getStringExtra(Shortcuts.EXTRA_DESTINATION)?.let { dest ->
            return when (dest) {
                "trash" -> Route.Trash
                "search" -> Route.Search()
                "analysis" -> Route.Analysis
                "transfer" -> Route.Transfer
                "browser" -> Route.Browser(intent.getStringExtra(Shortcuts.EXTRA_PATH))
                else -> null
            }
        }
        intent.getStringExtra(Shortcuts.EXTRA_PATH)?.let { return Route.Browser(it, newTab = true) }
        if (intent.action == Intent.ACTION_VIEW) {
            val data = intent.data ?: return null
            if (data.scheme == "file") data.path?.let { p ->
                return Opener.internalRoute(java.io.File(p).let { f ->
                    com.karelisio.fichio.core.model.FileEntry(p, f.name, f.isDirectory, f.length(), f.lastModified())
                }) ?: Route.UriPreview(data.toString(), intent.type)
            }
            return Route.UriPreview(data.toString(), intent.type)
        }
        return null
    }

    private fun pickerRequestOf(intent: Intent?): PickerRequest? {
        val action = intent?.action ?: return null
        if (action != Intent.ACTION_GET_CONTENT && action != Intent.ACTION_OPEN_DOCUMENT && action != Intent.ACTION_PICK) return null
        val types = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList()
            ?: listOf(intent.type ?: "*/*")
        return PickerRequest(types, intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    private fun finishWithPicked(paths: List<String>) {
        if (paths.isEmpty()) { setResult(Activity.RESULT_CANCELED); finish(); return }
        val uris = paths.map { Opener.uriFor(this, it) }
        val result = Intent().apply {
            data = uris.first()
            clipData = ClipData.newRawUri(null, uris.first()).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}

private fun AnimatorSet.doOnEndCompat(block: () -> Unit) {
    addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = block()
    })
}

@Suppress("unused")
private val defaultSettings = FichioSettings()
