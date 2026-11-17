package com.karelisio.fichio

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.karelisio.fichio.core.fs.FileSystems
import com.karelisio.fichio.core.fs.LocalBackend
import com.karelisio.fichio.core.fs.VolumeRepository
import com.karelisio.fichio.data.FavoritesRepository
import com.karelisio.fichio.data.RecentsRepository
import com.karelisio.fichio.data.db.FichioDatabase
import com.karelisio.fichio.data.prefs.SettingsRepository
import com.karelisio.fichio.ops.OperationManager
import com.karelisio.fichio.ops.UndoManager
import com.karelisio.fichio.search.SearchIndexer
import com.karelisio.fichio.shizuku.ShizukuManager
import com.karelisio.fichio.trash.TrashRepository
import com.karelisio.fichio.ui.components.FichioImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container. Everything is lazy so that nothing touches another
 * dependency while it is itself being constructed (no circular init at launch).
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    /** Application-wide scope for work that must outlive screens. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: FichioDatabase by lazy { FichioDatabase.build(appContext) }
    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }
    val volumes: VolumeRepository by lazy { VolumeRepository(appContext) }
    val shizuku: ShizukuManager by lazy { ShizukuManager(appContext) }
    val fileSystems: FileSystems by lazy { FileSystems(LocalBackend()) { shizuku.backend } }
    val trash: TrashRepository by lazy { TrashRepository(appContext, database, fileSystems) }
    val undo: UndoManager by lazy { UndoManager() }
    val operations: OperationManager by lazy { OperationManager(appContext, fileSystems, trash, undo, appScope) }
    val favorites: FavoritesRepository by lazy { FavoritesRepository(database) }
    val recents: RecentsRepository by lazy { RecentsRepository(database, fileSystems, appScope) }
    val indexer: SearchIndexer by lazy { SearchIndexer(appContext, database, fileSystems, appScope) }
}

class FichioApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        container.volumes.refresh()
        runCatching { container.shizuku.init() }
        runCatching { container.indexer.start() }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        FichioImageLoader.create(context, container.fileSystems)

    companion object {
        lateinit var instance: FichioApp
            private set
        val container: AppContainer get() = instance.container
    }
}

/** Shortcut for composables / services. */
val Context.container: AppContainer get() = (applicationContext as FichioApp).container
