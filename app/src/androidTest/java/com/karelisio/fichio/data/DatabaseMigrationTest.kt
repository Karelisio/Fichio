package com.karelisio.fichio.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.karelisio.fichio.data.db.FichioDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room upgrades on the device's real SQLite, against the schemas exported in app/schemas
 * (copied into the androidTest assets).
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule val helper = MigrationTestHelper(instrumentation, FichioDatabase::class.java)

    private val names = mutableListOf<String>()
    private fun dbName(tag: String) = "migration-$tag.db".also { names += it; context.deleteDatabase(it) }

    @After fun cleanUp() = names.forEach { context.deleteDatabase(it) }

    /** User data written by 0.2.x (v2) survives every migration up to the current version. */
    @Test fun v2DataSurvivesUpgradeToLatest() {
        val name = dbName("v2-latest")
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO favorites (path, label, position, addedAt) VALUES ('/storage/emulated/0/DCIM', 'DCIM', 0, 1)")
            execSQL("INSERT INTO trash (id, originalPath, trashPath, name, isDirectory, size, deletedAt) VALUES ('t1', '/a/b.txt', '/t/b.txt', 'b.txt', 0, 3, 42)")
            close()
        }
        helper.runMigrationsAndValidate(name, FichioDatabase.VERSION, true, *FichioDatabase.MIGRATIONS.toTypedArray()).close()

        // And the production builder opens it without dropping anything.
        val db = FichioDatabase.build(context, name)
        try {
            val favorites = runBlocking { db.favorites().all() }
            assertEquals(listOf("/storage/emulated/0/DCIM"), favorites.map { it.path })
            assertEquals(1, runBlocking { db.trash().all() }.size)
        } finally {
            db.close()
        }
    }

    /** Each declared migration produces exactly the exported schema of its end version. */
    @Test fun everyMigrationMatchesItsExportedSchema() {
        FichioDatabase.MIGRATIONS.forEach { m ->
            val name = dbName("${m.startVersion}-${m.endVersion}")
            helper.createDatabase(name, m.startVersion).close()
            helper.runMigrationsAndValidate(name, m.endVersion, true, m).close()
        }
        for (v in 3..FichioDatabase.VERSION) {
            assertTrue("no migration to v$v", FichioDatabase.MIGRATIONS.any { it.endVersion == v })
        }
    }

    /** 0.1.x left an unrelated "v1" schema under the same name: it is replaced, no crash. */
    @Test fun foreignV1DatabaseIsReplacedOnOpen() {
        val name = dbName("foreign-v1")
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE favorites (path TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL)")
            db.execSQL("INSERT INTO favorites VALUES ('/old', 'old')")
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table VALUES (42, 'legacy-0.1-hash')")
            db.version = 1
        }
        val db = FichioDatabase.build(context, name)
        try {
            assertEquals(0, runBlocking { db.favorites().all() }.size)
            assertEquals(FichioDatabase.VERSION, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }
}
