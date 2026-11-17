package com.karelisio.fichio.shortcuts

import android.content.Context

/** App shortcuts + pinned folder shortcuts on the launcher. CONTRACT STUB. */
object Shortcuts {
    const val EXTRA_PATH = "com.karelisio.fichio.extra.PATH"
    const val EXTRA_DESTINATION = "com.karelisio.fichio.extra.DESTINATION"

    /** Asks the launcher to pin a shortcut that opens [path]. */
    fun pinFolder(context: Context, path: String, label: String): Boolean = false

    /** Refreshes dynamic shortcuts (favourites). */
    fun refreshDynamic(context: Context, favorites: List<Pair<String, String>>) {}
}
