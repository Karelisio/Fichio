package com.karelisio.fichio.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karelisio.fichio.container
import com.karelisio.fichio.shizuku.ShizukuState
import com.karelisio.fichio.ui.components.bouncy
import com.karelisio.fichio.ui.components.pressScale
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

private sealed interface OnbPage {
    data class Intro(val index: Int) : OnbPage
    data class Permission(val spec: PermissionSpec) : OnbPage
    data object Ready : OnbPage
}

private data class IntroText(val title: String, val body: String)

private val intros = listOf(
    IntroText("Bienvenue dans Fichio", "Un gestionnaire de fichiers rapide, expressif et 100 % local. Sans compte, sans pub, sans pistage."),
    IntroText("Tout à portée de main", "Copiez, déplacez, compressez et prévisualisez vos fichiers avec des onglets, un double volet et la corbeille."),
    IntroText("Gardez l'espace sous contrôle", "Repérez les gros fichiers, les doublons et les dossiers oubliés en un coup d'œil."),
)

/** Animated intro + one page per permission. */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    val pages: List<OnbPage> = remember {
        intros.indices.map { OnbPage.Intro(it) } +
            PermissionsCatalog.applicable().map { OnbPage.Permission(it) } +
            OnbPage.Ready
    }
    val pager = rememberPagerState { pages.size }
    val snapshot by rememberPermissionSnapshot()
    var refresh by remember { mutableStateOf(0) }
    val granted = remember(snapshot, refresh) { PermissionsCatalog.snapshot(context) }

    val currentPage = pages[pager.currentPage]
    val blocked = currentPage is OnbPage.Permission && currentPage.spec.required && granted[currentPage.spec.id] != true

    BackHandler(enabled = pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }

    fun next() {
        haptics.click()
        scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(pages.lastIndex)) }
    }

    fun finish() {
        haptics.confirm()
        scope.launch {
            app.settings.setOnboardingDone(true)
            runCatching { app.indexer.requestFullScan() }
            onFinished()
        }
    }

    val cs = MaterialTheme.colorScheme
    val bgTarget = when (val p = currentPage) {
        is OnbPage.Intro -> listOf(cs.primaryContainer, cs.tertiaryContainer, cs.secondaryContainer)[p.index]
        is OnbPage.Permission -> if (granted[p.spec.id] == true) cs.secondaryContainer else cs.surfaceContainerHigh
        OnbPage.Ready -> cs.primaryContainer
    }
    val bg by animateColorAsState(bgTarget.copy(alpha = 0.45f), label = "bg")

    Box(Modifier.fillMaxSize().background(cs.surface).background(bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Skip button (intro only)
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(currentPage is OnbPage.Intro, enter = fadeIn(), exit = fadeOut()) {
                    TextButton(onClick = { scope.launch { pager.animateScrollToPage(intros.size) } }) { Text("Passer") }
                }
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                userScrollEnabled = !blocked,
                beyondViewportPageCount = 1,
            ) { index ->
                val offset = pager.offsetOf(index)
                when (val p = pages[index]) {
                    is OnbPage.Intro -> IntroPage(p.index, offset, active = pager.currentPage == index)
                    is OnbPage.Permission -> PermissionPage(
                        p.spec, offset, granted[p.spec.id] == true,
                        onChanged = { refresh++ },
                        onGrantedAdvance = { if (pager.currentPage == index) next() },
                    )
                    OnbPage.Ready -> ReadyPage(offset, active = pager.currentPage == index)
                }
            }
            BottomBar(pager, pages.size, blocked, isLast = currentPage == OnbPage.Ready, onNext = ::next, onFinish = ::finish)
        }
    }
}

private fun PagerState.offsetOf(page: Int): Float = (currentPage - page) + currentPageOffsetFraction

/** Parallax container: illustration moves slower than the text, both fade/scale with distance. */
@Composable
private fun PageScaffold(
    offset: Float,
    illustration: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.graphicsLayer {
                if (!reduce) {
                    translationX = offset * size.width * 0.45f
                    val s = 1f - 0.2f * offset.absoluteValue.coerceIn(0f, 1f)
                    scaleX = s; scaleY = s
                    rotationZ = offset * -12f
                }
                alpha = 1f - 0.6f * offset.absoluteValue.coerceIn(0f, 1f)
            },
            contentAlignment = Alignment.Center,
        ) { illustration() }
        Spacer(Modifier.height(28.dp))
        Column(
            Modifier.graphicsLayer {
                if (!reduce) translationX = offset * size.width * -0.15f
                alpha = 1f - offset.absoluteValue.coerceIn(0f, 1f)
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@Composable
private fun IntroPage(index: Int, offset: Float, active: Boolean) {
    val t = intros[index]
    PageScaffold(offset, illustration = {
        when (index) {
            0 -> WelcomeIllustration()
            1 -> FolderOpenIllustration()
            else -> GaugeIllustration(active = active)
        }
    }) {
        Text(t.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(t.body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionPage(
    spec: PermissionSpec,
    offset: Float,
    granted: Boolean,
    onChanged: () -> Unit,
    onGrantedAdvance: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val cs = MaterialTheme.colorScheme
    val request = rememberPermissionRequester(onChanged)
    val shizuku by context.container.shizuku.state.collectAsStateWithLifecycle()

    // Celebrate the grant, then move on by itself.
    var wasGranted by remember { mutableStateOf(granted) }
    LaunchedEffect(granted) {
        if (granted && !wasGranted) {
            haptics.confirm()
            delay(900)
            onGrantedAdvance()
        }
        wasGranted = granted
    }

    PageScaffold(offset, illustration = {
        Box(contentAlignment = Alignment.Center) {
            MorphingBadge(
                listOf(spec.shape, MaterialShapes.Cookie12Sided, spec.shape, MaterialShapes.Circle),
                if (granted) cs.primary else cs.primaryContainer,
                Modifier.size(180.dp),
                periodMs = 3000,
            ) {
                AnimatedContent(
                    granted,
                    transitionSpec = { (scaleIn(bouncy()) + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                    label = "permIcon",
                ) { ok ->
                    if (ok) AnimatedCheck(true, cs.onPrimary, Modifier.size(84.dp), strokeWidth = 10.dp)
                    else Icon(spec.icon, null, Modifier.size(76.dp), tint = cs.onPrimaryContainer)
                }
            }
        }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (spec.required) {
                Surface(color = cs.errorContainer, shape = CircleShape) {
                    Text("Obligatoire", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = cs.onErrorContainer)
                }
                Spacer(Modifier.width(8.dp))
            }
            PermissionStatusPill(granted, optional = !spec.required)
        }
        Spacer(Modifier.height(12.dp))
        Text(spec.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(spec.description, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = cs.onSurfaceVariant)

        if (spec.kind == PermissionKind.SHIZUKU) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (shizuku == ShizukuState.CONNECTING) {
                    LoadingIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(8.dp))
                }
                Text(PermissionsCatalog.shizukuStatusText(shizuku), style = MaterialTheme.typography.labelLarge, color = cs.primary)
            }
        }
        if (spec.steps.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Surface(color = cs.surfaceContainerHighest, shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    spec.steps.forEachIndexed { i, s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(26.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = cs.onPrimary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(s, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        AnimatedVisibility(!granted || spec.kind == PermissionKind.MANUAL, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val interaction = remember { MutableInteractionSource() }
                Button(
                    onClick = { haptics.click(); request(spec) },
                    modifier = Modifier.height(56.dp).pressScale(interaction),
                    interactionSource = interaction,
                    shapes = ButtonDefaults.shapes(),
                    enabled = spec.kind != PermissionKind.SHIZUKU || shizuku != ShizukuState.CONNECTING,
                ) {
                    Icon(if (spec.kind == PermissionKind.MANUAL) Icons.Rounded.OpenInNew else spec.icon, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (spec.kind) {
                            PermissionKind.SHIZUKU -> PermissionsCatalog.shizukuActionText(shizuku)
                            PermissionKind.MANUAL -> "Ouvrir les réglages"
                            else -> "Autoriser"
                        }
                    )
                }
                if (spec.kind == PermissionKind.MANUAL) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(CircleShape).padding(end = 12.dp),
                    ) {
                        Checkbox(checked = granted, onCheckedChange = {
                            haptics.tick()
                            PermissionsCatalog.setAutostartConfirmed(context, it)
                            onChanged()
                        })
                        Text("C'est fait", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyPage(offset: Float, active: Boolean) {
    PageScaffold(offset, illustration = { ReadyIllustration(active = active) }) {
        Text("Tout est prêt", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Fichio va indexer vos fichiers en arrière-plan pour une recherche instantanée. Vous pourrez revoir les autorisations dans les réglages.",
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BottomBar(pager: PagerState, count: Int, blocked: Boolean, isLast: Boolean, onNext: () -> Unit, onFinish: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PageIndicator(pager, count, Modifier.weight(1f))
        val interaction = remember { MutableInteractionSource() }
        AnimatedContent(isLast, label = "cta", transitionSpec = { (scaleIn(bouncy()) + fadeIn()) togetherWith (scaleOut() + fadeOut()) }) { last ->
            if (last) {
                Button(onClick = onFinish, modifier = Modifier.height(56.dp).pressScale(interaction), interactionSource = interaction, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(8.dp)); Text("Commencer")
                }
            } else {
                FilledTonalButton(
                    onClick = onNext, enabled = !blocked, modifier = Modifier.height(56.dp).pressScale(interaction),
                    interactionSource = interaction, shapes = ButtonDefaults.shapes(),
                ) {
                    Text(if (blocked) "Autorisation requise" else "Suivant")
                    if (!blocked) { Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) }
                }
            }
        }
    }
}

/** Expressive indicator: the current dot stretches into a pill and its neighbours swell with the drag. */
@Composable
private fun PageIndicator(pager: PagerState, count: Int, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val distance = (pager.currentPage - i + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
            val selected = 1f - distance
            val width by animateDpAsState((8 + 18 * selected).dp, bouncy(), label = "w$i")
            val alpha by animateFloatAsState(0.35f + 0.65f * selected, label = "a$i")
            Box(
                Modifier.height(8.dp).width(width).clip(CircleShape)
                    .background(if (i < pager.currentPage) cs.primary.copy(alpha = 0.6f) else cs.primary.copy(alpha = alpha))
            )
        }
    }
}
