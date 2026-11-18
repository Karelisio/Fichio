package com.karelisio.fichio.data

import android.app.Application
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.karelisio.fichio.data.db.FichioDatabase
import com.karelisio.fichio.data.db.FileTagEntity
import com.karelisio.fichio.search.SearchQuery
import com.karelisio.fichio.search.SearchSqlBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds a real v2 database from the exported schema (schemas/.../2.json), migrates it with
 * [FichioDatabase.MIGRATIONS] through the production builder (Room validates the v3 schema on
 * open) and checks that existing data survived and the new tables work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MigrationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()

    private fun schemaFile(version: Int): File {
        val rel = "schemas/com.karelisio.fichio.data.db.FichioDatabase/$version.json"
        return listOf(File(rel), File("app/$rel")).firstOrNull { it.exists() }
            ?: error("Exported schema $rel not found from ${File(".").absolutePath}")
    }

    /** Every statement Room ran to create the schema of [version] (tables, indices, FTS triggers, master table). */
    private fun createStatements(version: Int): List<String> {
        val db = Json.parseToJsonElement(schemaFile(version).readText()).jsonObject["database"]!!.jsonObject
        val out = ArrayList<String>()
        db["entities"]!!.jsonArray.forEach { e ->
            val o = e.jsonObject
            val table = o["tableName"]!!.jsonPrimitive.content
            fun JsonObject.sql(key: String) = this[key]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
            out += o.sql("createSql")
            o["indices"]?.jsonArray?.forEach { out += it.jsonObject.sql("createSql") }
            o["contentSyncTriggers"]?.jsonArray?.forEach { out += it.jsonPrimitive.content.replace("\${TABLE_NAME}", table) }
        }
        db["setupQueries"]!!.jsonArray.forEach { out += it.jsonPrimitive.content }
        return out
    }

    private fun createV2(file: File) {
        val statements = createStatements(2)
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(file.name)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) = statements.forEach { db.execSQL(it) }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO favorites (path, label, position, addedAt) VALUES ('/s/Docs', 'Docs', 0, 1)")
            db.execSQL("INSERT INTO recents (path, openedAt) VALUES ('/s/Docs/a.txt', 5)")
            db.execSQL(
                "INSERT INTO file_index (path, name, parent, extension, type, isDirectory, size, modified, generation) " +
                    "VALUES ('/s/Docs/Rapport annuel.txt', 'Rapport annuel.txt', '/s/Docs', 'txt', 'TEXT', 0, 10, 3, 1)",
            )
            assertEquals(2, db.version)
        }
    }

    @Test
    fun migrates2To3KeepingData() {
        val file = ctx.getDatabasePath(FichioDatabase.NAME).apply { parentFile?.mkdirs(); delete() }
        createV2(file)

        val db = FichioDatabase.build(ctx)
        try {
            runBlocking {
                // Old data is still there (no destructive fallback from 2).
                assertEquals(listOf("/s/Docs"), db.favorites().all().map { it.path })
                assertEquals(listOf("/s/Docs/a.txt"), db.recents().allPaths())
                val b = SearchSqlBuilder.build(SearchQuery(text = "rapport"))
                assertEquals(listOf("Rapport annuel.txt"), db.fileIndex().search(SimpleSQLiteQuery(b.sql, b.argsArray)).map { it.name })

                // New tables are usable.
                db.tags().insertIfMissing(com.karelisio.fichio.tags.TagPalette.defaults())
                assertEquals(7, db.tags().all().size)
                db.tags().assign(listOf(FileTagEntity("/s/Docs/Rapport annuel.txt", 1)))
                assertEquals(listOf("/s/Docs/Rapport annuel.txt"), db.tags().pathsWith(listOf(1L)))
                db.content().replace("/s/Docs/Rapport annuel.txt", 3, 10, "chiffre d'affaires en hausse")
                val c = SearchSqlBuilder.buildContent(SearchQuery(text = "affaires", inContent = true))!!
                val hits = db.content().hits(SimpleSQLiteQuery(c.sql, c.argsArray))
                assertEquals(listOf("/s/Docs/Rapport annuel.txt"), hits.map { it.path })
            }
            assertEquals(3, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationListIsWiredAndExplicit() {
        assertTrue(FichioDatabase.MIGRATIONS.any { it.startVersion == 2 && it.endVersion == 3 })
        // The v3 schema the migration targets is committed.
        assertTrue(schemaFile(3).exists())
    }
}
