package com.bignerdranch.android.myapplication.data.local.db

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.entity.AdditionLogEntity
import com.bignerdranch.android.myapplication.data.local.entity.CountryEntity
import com.bignerdranch.android.myapplication.data.local.entity.ItemCountryCrossRef
import com.bignerdranch.android.myapplication.data.local.entity.ItemEntity
import com.bignerdranch.android.myapplication.data.local.entity.QuantityLogEntity
import com.bignerdranch.android.myapplication.data.local.entity.SaveSessionEntity
import com.bignerdranch.android.myapplication.data.local.entity.SaveSessionLineEntity
import com.bignerdranch.android.myapplication.data.local.entity.SheetEntity
import com.bignerdranch.android.myapplication.data.local.entity.SheetLineEntity
import java.util.concurrent.Executors

@Database(
    entities = [
        ItemEntity::class,
        CountryEntity::class,
        ItemCountryCrossRef::class,
        QuantityLogEntity::class,
        AdditionLogEntity::class,
        SaveSessionEntity::class,
        SaveSessionLineEntity::class,
        SheetEntity::class,
        SheetLineEntity::class
    ],
    version = 15,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemCountryDao(): ItemCountryDao
    abstract fun saveArchiveDao(): ItemCountryDao.SaveArchiveDao
    abstract fun sheetDao(): ItemCountryDao.SheetDao

    companion object {

        // ✅ 여기로 옮기기 (companion 안)
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE item_country ADD COLUMN lastClickedAt INTEGER")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "item_country.db"
                )
                    .setQueryCallback(
                        { sql, _ -> Log.d("SQL", sql) },
                        Executors.newSingleThreadExecutor()
                    )
                    // ❌ 이건 지우고
                    // .fallbackToDestructiveMigration()

                    // ✅ 마이그레이션 추가
                    .addMigrations(MIGRATION_14_15)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}