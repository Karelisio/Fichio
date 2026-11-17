package com.karelisio.fichio.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations. */
sealed interface Route {
    @Serializable data object Onboarding : Route
    @Serializable data object Home : Route
    /** File browser. [path] null = restore tabs / primary storage. */
    @Serializable data class Browser(val path: String? = null, val newTab: Boolean = false) : Route
    @Serializable data class Category(val category: String) : Route
    @Serializable data class Search(val scopePath: String? = null) : Route
    @Serializable data object Trash : Route
    @Serializable data class Preview(val path: String) : Route
    @Serializable data class UriPreview(val uri: String, val mimeType: String? = null) : Route
    @Serializable data class Editor(val path: String) : Route
    @Serializable data class Archive(val path: String) : Route
    @Serializable data class ApkInfo(val path: String) : Route
    @Serializable data object InstalledApps : Route
    @Serializable data object Analysis : Route
    @Serializable data object Transfer : Route
    @Serializable data object Settings : Route
    @Serializable data object Permissions : Route
}
