package com.karelisio.fichio.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FavoriteEntity::class,
        PinnedEntity::class,
        TabEntity::class,
        FolderSettingsEntity::class,
        TrashEntity::class,
        FileIndexEntity::class,
        FileIndexFts::class,
        TransferLogEntity::class,
        RecentEntity::class,
        TagEntity::class,
        FileTagEntity::class,
        ContentMetaEntity::class,
        ContentFts::class,
    ],
    // v1 = the 0.1.x app (different schema, same file name): dropped on upgrade.
    // v3 = tags + content index ([MIGRATION_2_3]).
    version = FichioDatabase.VERSION,
    exportSchema = true,
)
abstract class FichioDatabase : RoomDatabase() {
    abstract fun favorites(): FavoriteDao
    abstract fun pinned(): PinnedDao
    abstract fun tabs(): TabDao
    abstract fun folderSettings(): FolderSettingsDao
    abstract fun trash(): TrashDao
    abstract fun fileIndex(): FileIndexDao
    abstract fun transferLog(): TransferLogDao
    abstract fun recents(): RecentDao
    abstract fun tags(): TagDao
    abstract fun content(): ContentDao
    abstract fun collections(): CollectionsDao

    companion object {
        const val NAME = "fichio.db"

        /**
         * Current schema version. Any schema change bumps it AND adds a [Migration] to
         * [MIGRATIONS] (checked by DatabaseMigrationPolicyTest + the instrumented
         * DatabaseMigrationTest against the exported schemas in app/schemas).
         */
        const val VERSION = 3

        /** 2 → 3: colour tags (tags, file_tags) and the content index (content_meta, content_fts). */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `tags` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `file_tags` (`path` TEXT NOT NULL, `tagId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`path`, `tagId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_tags_tagId` ON `file_tags` (`tagId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `content_meta` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `path` TEXT NOT NULL, `modified` INTEGER NOT NULL, `size` INTEGER NOT NULL, `indexed` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_content_meta_path` ON `content_meta` (`path`)")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `content_fts` USING FTS4(`path` TEXT NOT NULL, `body` TEXT NOT NULL, tokenize=unicode61, notindexed=`path`)")
            }
        }

        /** Explicit migrations, one per bump from v2 on. Never destructive. */
        val MIGRATIONS: List<Migration> = listOf(MIGRATION_2_3)

        /** The only versions whose data may be dropped: v1 = 0.1.x, an unrelated schema. */
        internal val DESTRUCTIVE_FROM = intArrayOf(1)

        fun build(context: Context, name: String = NAME): FichioDatabase =
            Room.databaseBuilder(context.applicationContext, FichioDatabase::class.java, name)
                .addMigrations(*MIGRATIONS.toTypedArray())
                // 0.1.x shipped an unrelated schema under the same name/version 1: start fresh.
                .fallbackToDestructiveMigrationFrom(true, *DESTRUCTIVE_FROM)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()

        fun inMemory(context: Context): FichioDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, FichioDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
