package com.karelisio.fichio.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.karelisio.fichio.data.db.FichioDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 0.1.x left a "fichio.db" at version 1 with an unrelated schema: opening must not crash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LegacyDatabaseUpgradeTest {
    @Test fun legacyV1DatabaseIsReplaced() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val file = ctx.getDatabasePath(FichioDatabase.NAME).apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE favorites (path TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table VALUES (42, 'legacy-0.1-hash')")
            db.version = 1
        }
        val db = FichioDatabase.build(ctx)
        val count = runBlocking { db.favorites().all().size }
        assertEquals(0, count)
        db.close()
    }
}
