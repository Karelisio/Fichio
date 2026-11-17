package com.karelisio.fichio.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun viewModeToInt(mode: ViewMode): Int = mode.ordinal

    @TypeConverter
    fun intToViewMode(value: Int): ViewMode = ViewMode.entries[value]

    @TypeConverter
    fun sortModeToInt(mode: SortMode): Int = mode.ordinal

    @TypeConverter
    fun intToSortMode(value: Int): SortMode = SortMode.entries[value]
}

@Database(
    entities = [
        FavoriteEntity::class,
        PinnedFolderEntity::class,
        OpenTabEntity::class,
        TrashItemEntity::class,
        SearchIndexEntity::class,
        FolderSettingsEntity::class,
        TransferLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class FichioDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun pinnedDao(): PinnedDao
    abstract fun tabDao(): TabDao
    abstract fun trashDao(): TrashDao
    abstract fun searchDao(): SearchDao
    abstract fun folderSettingsDao(): FolderSettingsDao
    abstract fun transferDao(): TransferDao

    companion object {
        const val NAME = "fichio.db"
    }
}
