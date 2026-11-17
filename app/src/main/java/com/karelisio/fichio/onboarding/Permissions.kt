package com.karelisio.fichio.onboarding

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.MaterialShapes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.graphics.shapes.RoundedPolygon
import com.karelisio.fichio.container
import com.karelisio.fichio.shizuku.ShizukuState

/** How a permission is obtained. */
enum class PermissionKind {
    /** A system settings screen ([PermissionSpec.requestIntent]). */
    SETTINGS,
    /** A runtime permission dialog ([PermissionSpec.runtimePermission]). */
    RUNTIME,
    /** Shevery / Shizuku binder permission. */
    SHIZUKU,
    /** Not verifiable: the user confirms it manually. */
    MANUAL,
}

enum class PermissionId { ALL_FILES, NOTIFICATIONS, SHIZUKU, INSTALL_APPS, BATTERY, AUTOSTART }

data class PermissionSpec(
    val id: PermissionId,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val shape: RoundedPolygon,
    val kind: PermissionKind,
    val required: Boolean = false,
    /** Extra step-by-step instructions (MagicOS app launch). */
    val steps: List<String> = emptyList(),
    val runtimePermission: String? = null,
)

/**
 * Single source of truth for the permissions Fichio asks for, shared by the onboarding pager
 * and the "Autorisations" settings screen.
 */
object PermissionsCatalog {
    private const val PREFS = "fichio_permissions"
    private const val KEY_AUTOSTART = "autostart_confirmed"

    val isHonor: Boolean
        get() = Build.MANUFACTURER.lowercase().let { it.contains("honor") || it.contains("huawei") } ||
            Build.BRAND.lowercase().let { it.contains("honor") || it.contains("huawei") }

    val all: List<PermissionSpec> = listOf(
        PermissionSpec(
            PermissionId.ALL_FILES, "Accès à tous les fichiers",
            "Indispensable pour parcourir, copier et organiser vos fichiers sur tout le stockage, les clés USB et cartes SD.",
            Icons.Rounded.FolderSpecial, MaterialShapes.Cookie9Sided, PermissionKind.SETTINGS, required = true,
        ),
        PermissionSpec(
            PermissionId.NOTIFICATIONS, "Notifications",
            "Pour suivre la progression des copies, des archives et du transfert local, même quand Fichio est en arrière-plan.",
            Icons.Rounded.NotificationsActive, MaterialShapes.SoftBurst, PermissionKind.RUNTIME,
            runtimePermission = Manifest.permission.POST_NOTIFICATIONS,
        ),
        PermissionSpec(
            PermissionId.SHIZUKU, "Shevery (facultatif)",
            "Android protège les dossiers Android/data et Android/obb. Avec Shevery (Shizuku), Fichio peut y accéder pour gérer les données des applis.",
            Icons.Rounded.Shield, MaterialShapes.Gem, PermissionKind.SHIZUKU,
        ),
        PermissionSpec(
            PermissionId.INSTALL_APPS, "Installation d'applis",
            "Pour installer les APK que vous ouvrez dans Fichio et appliquer les mises à jour de l'appli.",
            Icons.Rounded.InstallMobile, MaterialShapes.Clover4Leaf, PermissionKind.SETTINGS,
        ),
        PermissionSpec(
            PermissionId.BATTERY, "Optimisation de la batterie",
            "Évite que le système interrompe les longues opérations (grosses copies, transfert, vidage de la corbeille).",
            Icons.Rounded.BatteryChargingFull, MaterialShapes.Pill, PermissionKind.SETTINGS,
        ),
        PermissionSpec(
            PermissionId.AUTOSTART, "Lancement d'applis",
            "Sur MagicOS, le système bloque les tâches en arrière-plan des applis « gérées automatiquement ». À régler à la main.",
            Icons.Rounded.RocketLaunch, MaterialShapes.Sunny, PermissionKind.MANUAL,
            steps = listOf(
                "Trouvez Fichio dans la liste",
                "Désactivez « Gérer automatiquement »",
                "Activez Lancement automatique, Lancement secondaire et Exécution en arrière-plan",
            ),
        ),
    )

    /** Permissions relevant on this device (the MagicOS page only on Honor/Huawei). */
    fun applicable(): List<PermissionSpec> = all.filter { it.id != PermissionId.AUTOSTART || isHonor }

    fun spec(id: PermissionId): PermissionSpec = all.first { it.id == id }

    fun isGranted(context: Context, id: PermissionId): Boolean = when (id) {
        PermissionId.ALL_FILES -> Environment.isExternalStorageManager()
        PermissionId.NOTIFICATIONS -> Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        PermissionId.SHIZUKU -> runCatching { context.container.shizuku.state.value == ShizukuState.READY }.getOrDefault(false)
        PermissionId.INSTALL_APPS -> context.packageManager.canRequestPackageInstalls()
        PermissionId.BATTERY -> context.getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(context.packageName) == true
        PermissionId.AUTOSTART -> prefs(context).getBoolean(KEY_AUTOSTART, false)
    }

    fun isGranted(context: Context, spec: PermissionSpec) = isGranted(context, spec.id)

    /** Snapshot of every applicable permission. */
    fun snapshot(context: Context): Map<PermissionId, Boolean> = applicable().associate { it.id to isGranted(context, it.id) }

    fun setAutostartConfirmed(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOSTART, done).apply()
    }

    /**
     * Ordered candidate intents opening the right system screen for [id] (first resolvable wins).
     * Empty for runtime / Shizuku permissions.
     */
    fun requestIntents(context: Context, id: PermissionId): List<Intent> {
        val pkg = Uri.parse("package:${context.packageName}")
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        return when (id) {
            PermissionId.ALL_FILES -> listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                details,
            )
            PermissionId.NOTIFICATIONS -> listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                details,
            )
            PermissionId.INSTALL_APPS -> listOf(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg), details)
            @Suppress("BatteryLife")
            PermissionId.BATTERY -> listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            PermissionId.AUTOSTART -> listOf(
                component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                details,
            )
            PermissionId.SHIZUKU -> emptyList()
        }
    }

    /** First request intent that resolves on this device. */
    fun requestIntent(context: Context, id: PermissionId): Intent? {
        val pm = context.packageManager
        return requestIntents(context, id).firstOrNull { it.resolveActivity(pm) != null }
    }

    /** Launches the best system screen for [id]; tries every candidate until one starts. */
    fun openSettings(context: Context, id: PermissionId): Boolean {
        for (intent in requestIntents(context, id)) {
            val ok = runCatching {
                context.startActivity(Intent(intent).apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            }.isSuccess
            if (ok) return true
        }
        return false
    }

    /** Shizuku action depending on its current state. */
    fun requestShizuku(context: Context) {
        val sh = context.container.shizuku
        when (sh.state.value) {
            ShizukuState.NO_PERMISSION -> sh.requestPermission()
            ShizukuState.NOT_INSTALLED -> runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))
                        .apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
            }
            else -> sh.retry()
        }
    }

    fun shizukuStatusText(state: ShizukuState): String = when (state) {
        ShizukuState.NOT_INSTALLED -> "Shevery n'est pas installé"
        ShizukuState.NOT_RUNNING -> "Shevery est installé mais pas démarré"
        ShizukuState.NO_PERMISSION -> "Shevery attend votre autorisation"
        ShizukuState.CONNECTING -> "Connexion à Shevery…"
        ShizukuState.READY -> "Connecté à Shevery"
    }

    fun shizukuActionText(state: ShizukuState): String = when (state) {
        ShizukuState.NOT_INSTALLED -> "En savoir plus"
        ShizukuState.NO_PERMISSION -> "Autoriser"
        else -> "Réessayer"
    }

    private fun component(pkg: String, cls: String) =
        Intent().setComponent(ComponentName(pkg, cls)).addCategory(Intent.CATEGORY_DEFAULT)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
