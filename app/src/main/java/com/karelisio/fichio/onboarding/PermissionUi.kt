package com.karelisio.fichio.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karelisio.fichio.container
import com.karelisio.fichio.ui.components.bouncy

/** Grant state of every applicable permission, re-checked on each ON_RESUME and Shevery change. */
@Composable
fun rememberPermissionSnapshot(): State<Map<PermissionId, Boolean>> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(PermissionsCatalog.snapshot(context)) }
    val shizuku by context.container.shizuku.state.collectAsStateWithLifecycle()
    LaunchedEffect(shizuku) { state.value = PermissionsCatalog.snapshot(context) }
    LifecycleResumeEffect(Unit) {
        state.value = PermissionsCatalog.snapshot(context)
        onPauseOrDispose { }
    }
    return state
}

/** Returns a launcher that asks for [PermissionSpec] the right way and then calls [onChanged]. */
@Composable
fun rememberPermissionRequester(onChanged: () -> Unit): (PermissionSpec) -> Unit {
    val context = LocalContext.current
    val activity = context.findActivity()
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onChanged()
        // Permanently denied: the dialog no longer shows, so open the app's settings instead.
        if (!granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, android.Manifest.permission.POST_NOTIFICATIONS)
        ) {
            PermissionsCatalog.openSettings(context, PermissionId.NOTIFICATIONS)
        }
    }
    return remember(context) {
        { spec ->
            when (spec.kind) {
                PermissionKind.RUNTIME -> spec.runtimePermission?.let { runtime.launch(it) }
                PermissionKind.SHIZUKU -> { PermissionsCatalog.requestShizuku(context); onChanged() }
                PermissionKind.SETTINGS, PermissionKind.MANUAL -> PermissionsCatalog.openSettings(context, spec.id)
            }
        }
    }
}

/** Pill that turns green with a check once granted, orange otherwise. */
@Composable
fun PermissionStatusPill(granted: Boolean, modifier: Modifier = Modifier, optional: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val ok = Color(0xFF2E9E5B)
    val warn = Color(0xFFE08A1E)
    val bg by animateColorAsState(if (granted) ok.copy(alpha = 0.16f) else if (optional) cs.surfaceContainerHighest else warn.copy(alpha = 0.16f), label = "pillBg")
    val fg by animateColorAsState(if (granted) ok else if (optional) cs.onSurfaceVariant else warn, label = "pillFg")
    val dot by animateDpAsState(if (granted) 14.dp else 8.dp, bouncy(), label = "dot")
    Surface(color = bg, shape = CircleShape, modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                if (granted) AnimatedCheck(true, fg, Modifier.size(dot), strokeWidth = 2.5.dp)
                else Box(Modifier.size(dot).clip(CircleShape).background(fg))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                if (granted) "Accordée" else if (optional) "Facultative" else "À corriger",
                style = MaterialTheme.typography.labelMedium, color = fg,
            )
        }
    }
    Spacer(Modifier.height(0.dp))
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
