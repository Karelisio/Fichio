package com.karelisio.fichio.data

import android.app.Application
import androidx.room.DatabaseConfiguration
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.karelisio.fichio.data.db.FichioDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Guards the Room upgrade policy of the *production* builder ([FichioDatabase.build]):
 * - no global `fallbackToDestructiveMigration()`: only v1 (0.1.x, unrelated schema) may be dropped;
 * - every version above 2 is reachable through explicit [FichioDatabase.MIGRATIONS];
 * - the version constant matches the latest exported schema.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DatabaseMigrationPolicyTest {
    private lateinit var db: FichioDatabase
    private lateinit var config: DatabaseConfiguration
    private val version = FichioDatabase.VERSION

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        ctx.getDatabasePath("policy-test.db").delete()
        db = FichioDatabase.build(ctx, "policy-test.db")
        db.openHelper.writableDatabase // opens: the real version is written to the file
        config = RoomDatabase::class.java.getDeclaredField("configuration")
            .apply { isAccessible = true }
            .get(db) as DatabaseConfiguration
    }

    @After fun tearDown() = db.close()

    @Test fun openedDatabaseHasTheDeclaredVersion() {
        assertEquals(version, db.openHelper.readableDatabase.version)
    }

    @Test fun noGlobalDestructiveFallback() {
        assertTrue("fallbackToDestructiveMigration() must not be used", config.requireMigration)
    }

    @Test fun onlyVersionOneMayBeDropped() {
        assertArrayEquals(intArrayOf(1), FichioDatabase.DESTRUCTIVE_FROM)
        assertFalse("v1 (0.1.x) is replaced", config.isMigrationRequired(1, version))
        for (from in 2 until version) {
            assertTrue("upgrade from v$from must be a real migration", config.isMigrationRequired(from, version))
        }
        // Even a hypothetical future version must not be destructively upgradable.
        assertTrue(config.isMigrationRequired(version, version + 1))
    }

    @Test fun everyVersionAboveTwoHasAnExplicitMigration() {
        for (v in 3..version) {
            assertTrue(
                "FichioDatabase.MIGRATIONS has no migration ending at v$v",
                FichioDatabase.MIGRATIONS.any { it.endVersion == v && it.startVersion in 2 until v },
            )
        }
        for (from in 2 until version) {
            assertNotNull("no migration path v$from → v$version", config.migrationContainer.findMigrationPath(from, version))
        }
    }

    @Test fun migrationsListIsWiredInTheBuilder() {
        FichioDatabase.MIGRATIONS.forEach { m ->
            assertTrue(
                "MIGRATION_${m.startVersion}_${m.endVersion} not added to the builder",
                config.migrationContainer.contains(m.startVersion, m.endVersion),
            )
            assertTrue("migration beyond VERSION", m.endVersion <= version && m.startVersion >= 2)
        }
    }

    @Test fun latestExportedSchemaMatchesVersion() {
        val dir = File("schemas/${FichioDatabase::class.java.name}").takeIf { it.isDirectory }
            ?: File("app/schemas/${FichioDatabase::class.java.name}")
        val versions = dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { it.nameWithoutExtension.toIntOrNull() }
        assertTrue("exported schemas not found in $dir", versions.isNotEmpty())
        assertEquals("exportSchema: the latest schema json must be v$version", version, versions.max())
        for (v in 2..version) assertTrue("schema $v.json missing (needed by MigrationTestHelper)", v in versions)
    }
}
