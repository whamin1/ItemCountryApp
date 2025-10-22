package com.bignerdranch.android.myapplication.data.local.db

import android.content.Context
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

@Database(
    entities = [
        ItemEntity::class,
        CountryEntity::class,
        ItemCountryCrossRef::class,
        QuantityLogEntity::class,
        AdditionLogEntity::class,
        SaveSessionEntity::class,
        SaveSessionLineEntity::class],
    version = 11,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemCountryDao(): ItemCountryDao
    abstract fun saveArchiveDao(): ItemCountryDao.SaveArchiveDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1) 컬럼 추가 (기본값 0)
                db.execSQL("ALTER TABLE quantity_log ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 이미 있는지 확인
                val c = db.query("PRAGMA table_info(`quantity_log`)")
                var hasBatchId = false
                c.use {
                    val nameIdx = c.getColumnIndex("name")
                    while (c.moveToNext()) {
                        if (c.getString(nameIdx) == "batchId") {
                            hasBatchId = true
                            break
                        }
                    }
                }
                if (!hasBatchId) {
                    db.execSQL("ALTER TABLE quantity_log ADD COLUMN batchId INTEGER NOT NULL DEFAULT 0")
                }
                // 없으면 추가, 있으면 아무 것도 안 함 (no-op)
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "item_country.db"
                )
                    .addMigrations(MIGRATION_9_10, MIGRATION_10_11)
                    .build().also { INSTANCE = it }
            }
    }


}