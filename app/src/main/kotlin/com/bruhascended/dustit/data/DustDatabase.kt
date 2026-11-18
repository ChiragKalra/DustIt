package com.bruhascended.dustit.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MediaEntity::class,
        AnalysisEntity::class,
        SimilarMemberEntity::class,
        ContentHashEntity::class,
        TrashedFileEntity::class,
        HiddenFolderEntity::class,
    ],
    version = 5,
    exportSchema = true,
    // These only add columns and tables, so existing analysis survives instead of costing a rescan.
    autoMigrations = [AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
abstract class DustDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao

    companion object {
        fun build(context: Context): DustDatabase =
            Room.databaseBuilder(context, DustDatabase::class.java, "dustit.db")
                // Everything stored is a cache of MediaStore plus re-computable analysis,
                // so wiping it on a schema change only costs a rescan.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
