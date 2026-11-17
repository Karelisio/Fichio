package com.karelisio.fichio

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.karelisio.fichio.data.db.FichioDatabase
import com.karelisio.fichio.data.prefs.SettingsRepository
import com.karelisio.fichio.data.trash.TrashManager
import com.karelisio.fichio.domain.file.FileSystem
import rikka.shizuku.Shizuku

/** Manual service locator, since Fichio is small enough not to need DI. */
class AppContainer(context: Context) {
    val database: FichioDatabase = Room.databaseBuilder(
        context.applicationContext,
        FichioDatabase::class.java,
        FichioDatabase.NAME
    ).build()

    val settingsRepository = SettingsRepository(context.applicationContext)
    val fileSystem = FileSystem(context.applicationContext)
    val trashManager = TrashManager(context.applicationContext, database)
}

class FichioApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Shevery/Shizuku (optional). Failure here is harmless: the rest of
        // the app works normally and simply reports that Shevery is inactive.
        runCatching { Shizuku.addBinderReceivedListenerSticky(ShizukuListener) }

        // Schedule the periodic trash purge (auto-empty after the retention
        // window) and search-index refresh.
        runCatching { com.karelisio.fichio.worker.WorkScheduler.schedule(this) }
    }

    private object ShizukuListener : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() { /* Service availability is observed live. */ }
    }
}
