package com.karelisio.fichio.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
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

        fun build(context: Context): FichioDatabase =
            Room.databaseBuilder(context.applicationContext, FichioDatabase::class.java, NAME)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()

        fun inMemory(context: Context): FichioDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, FichioDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
