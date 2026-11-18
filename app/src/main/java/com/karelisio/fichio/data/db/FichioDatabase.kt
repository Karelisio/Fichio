package com.karelisio.fichio.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

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
    ],
    // v1 = the 0.1.x app (different schema, same file name): dropped on upgrade.
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

    companion object {
        const val NAME = "fichio.db"

        /**
         * Current schema version. Any schema change bumps it AND adds a [Migration] to
         * [MIGRATIONS] (checked by DatabaseMigrationPolicyTest + the instrumented
         * DatabaseMigrationTest against the exported schemas in app/schemas).
         */
        const val VERSION = 2

        /** Explicit migrations, one per bump from v2 on (e.g. MIGRATION_2_3). Never destructive. */
        val MIGRATIONS: List<Migration> = listOf()

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
