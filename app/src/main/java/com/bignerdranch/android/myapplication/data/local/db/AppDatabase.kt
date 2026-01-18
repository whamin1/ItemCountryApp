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
import com.bignerdranch.android.myapplication.data.local.entity.PredictionAckEntity
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
        SheetLineEntity::class,
        PredictionAckEntity::class
    ],
    version = 20,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemCountryDao(): ItemCountryDao
    abstract fun saveArchiveDao(): ItemCountryDao.SaveArchiveDao
    abstract fun sheetDao(): ItemCountryDao.SheetDao


    companion object {

        // ✅ 여기로 옮기기 (companion 안)
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {

                if (!hasColumn(db, "item_country", "lastClickedAt")) {
                    db.execSQL("ALTER TABLE item_country ADD COLUMN lastClickedAt INTEGER")
                }

                db.execSQL("""
            CREATE TABLE IF NOT EXISTS user_holidays (
                date TEXT NOT NULL PRIMARY KEY
            )
        """.trimIndent())

                // ✅ 이게 핵심
                db.execSQL("""
            CREATE TABLE IF NOT EXISTS prediction_ack (
                itemId INTEGER NOT NULL,
                ackAt INTEGER NOT NULL,
                PRIMARY KEY(itemId)
            )
        """.trimIndent())
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {

                // ✅ prediction_ack 테이블 생성
                db.execSQL("""
            CREATE TABLE IF NOT EXISTS prediction_ack (
                itemId INTEGER NOT NULL,
                ackAt INTEGER NOT NULL,
                PRIMARY KEY(itemId)
            )
        """.trimIndent())

                // (옵션) 혹시 lastClickedAt 같은 것도 17에서 누락됐을 가능성 대비
                if (!hasColumn(db, "item_country", "lastClickedAt")) {
                    db.execSQL("ALTER TABLE item_country ADD COLUMN lastClickedAt INTEGER")
                }

                // (옵션) user_holidays도 혹시 누락 방어
                db.execSQL("""
            CREATE TABLE IF NOT EXISTS user_holidays (
                date TEXT NOT NULL PRIMARY KEY
            )
        """.trimIndent())
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sheet_lines ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sheet_lines ADD COLUMN deletedAt INTEGER")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE quantity_log ADD COLUMN itemName TEXT")
                db.execSQL("ALTER TABLE quantity_log ADD COLUMN countryName TEXT")
                db.execSQL("ALTER TABLE quantity_log ADD COLUMN priceAt INTEGER")
                db.execSQL("ALTER TABLE quantity_log ADD COLUMN weightAt REAL")
                db.execSQL("""
                    UPDATE quantity_log
                    SET itemName = (SELECT name FROM items WHERE id = quantity_log.itemId)
                    WHERE itemName IS NULL
                    """)

                db.execSQL("""
                    UPDATE quantity_log
                    SET countryName = (SELECT name FROM countries WHERE id = quantity_log.countryId)
                    WHERE countryName IS NOT NULL AND countryId IS NOT NULL
                    """)
                db.execSQL("""
                    UPDATE quantity_log
                    SET priceAt = (
                    SELECT ic.price
                    FROM item_country ic
                    WHERE ic.itemId = quantity_log.itemId
                    AND ic.countryId = quantity_log.countryId
                    )
                    WHERE countryId IS NOT NULL AND priceAt IS NULL
                    """)

                db.execSQL("""
                    UPDATE quantity_log
                    SET weightAt = (
                    SELECT ic.weight
                    FROM item_country ic
                    WHERE ic.itemId = quantity_log.itemId
                    AND ic.countryId = quantity_log.countryId
                    )
                    WHERE countryId IS NOT NULL AND weightAt IS NULL
                    """)
                db.execSQL("""
UPDATE quantity_log
SET priceAt = (
  SELECT sl.price
  FROM sheet_lines sl
  WHERE sl.item = (SELECT name FROM items WHERE id = quantity_log.itemId)
    AND sl.country = (SELECT name FROM countries WHERE id = quantity_log.countryId)
    AND sl.price > 0
  ORDER BY sl.createdAt DESC
  LIMIT 1
)
WHERE countryId IS NOT NULL AND (priceAt IS NULL OR priceAt = 0)
""")

                db.execSQL("""
UPDATE quantity_log
SET weightAt = (
  SELECT sl.weight
  FROM sheet_lines sl
  WHERE sl.item = (SELECT name FROM items WHERE id = quantity_log.itemId)
    AND sl.country = (SELECT name FROM countries WHERE id = quantity_log.countryId)
    AND sl.weight > 0
  ORDER BY sl.createdAt DESC
  LIMIT 1
)
WHERE countryId IS NOT NULL AND (weightAt IS NULL OR weightAt = 0)
""")
            }
        }



        private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return true
                }
            }
            return false
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
//                     .fallbackToDestructiveMigration()

//                    // ✅ 마이그레이션 추가
                    .addMigrations(MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}