package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.data.model.AppSetting
import com.example.data.model.BlockingProfile
import com.example.data.model.NfcTag

@Database(
    entities = [
        NfcTag::class,
        BlockingProfile::class,
        AppSetting::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class TagOutDatabase : RoomDatabase() {
    abstract fun nfcTagDao(): NfcTagDao
    abstract fun blockingProfileDao(): BlockingProfileDao
    abstract fun appSettingDao(): AppSettingDao

    companion object {
        @Volatile
        private var INSTANCE: TagOutDatabase? = null

        fun getInstance(context: Context): TagOutDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TagOutDatabase::class.java,
                    "tagout_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
