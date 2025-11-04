package com.bignerdranch.android.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Junction
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.bignerdranch.android.myapplication.data.local.entity.AdditionLogEntity
import com.bignerdranch.android.myapplication.data.local.entity.CountryEntity
import com.bignerdranch.android.myapplication.data.local.entity.ItemCountryCrossRef
import com.bignerdranch.android.myapplication.data.local.entity.ItemEntity
import com.bignerdranch.android.myapplication.data.local.entity.QuantityLogEntity
import com.bignerdranch.android.myapplication.data.local.entity.SaveSessionEntity
import com.bignerdranch.android.myapplication.data.local.entity.SaveSessionLineEntity
import com.bignerdranch.android.myapplication.data.local.entity.SheetEntity
import com.bignerdranch.android.myapplication.data.local.entity.SheetLineEntity
import kotlinx.coroutines.flow.Flow

/* -------- 관계 DTO -------- */
data class ItemWithCountries(
    @Embedded val item: ItemEntity,
    @Relation(
        entity = CountryEntity::class,
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = ItemCountryCrossRef::class,
            parentColumn = "itemId",
            entityColumn = "countryId"
        )
    )
    val countries: List<CountryEntity>
)

data class CountryWithItems(
    @Embedded val country: CountryEntity,
    @Relation(
        entity = ItemEntity::class,
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = ItemCountryCrossRef::class,
            parentColumn = "countryId",
            entityColumn = "itemId"
        )
    )
    val items: List<ItemEntity>
)

data class ItemCountryRow(
    val item: String = "",
    val country: String = "",
    val needed: Int,
    val have: Int
)

/* -------- DAO -------- */
@Dao
interface ItemCountryDao {

    @Upsert
    suspend fun upsertItems(items: List<ItemEntity>): List<Long>

    @Upsert
    suspend fun upsertCountries(countries: List<CountryEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRefs(refs: List<ItemCountryCrossRef>)

    @Query("SELECT * FROM items WHERE name = :name LIMIT 1")
    fun findItemByName(name: String): ItemEntity?

    @Query("SELECT * FROM countries WHERE name = :name LIMIT 1")
    fun findCountryByName(name: String): CountryEntity?

    @Transaction
    @Query("SELECT * FROM items WHERE name = :itemName LIMIT 1")
    suspend fun getItemWithCountriesByName(itemName: String): ItemWithCountries?

    @Transaction
    @Query("SELECT * FROM countries WHERE name = :countryName LIMIT 1")
    suspend fun getCountryWithItemsByName(countryName: String): CountryWithItems?

    @Transaction
    @Query("SELECT * FROM items ORDER BY name")
    fun observeAllItemsWithCountries(): Flow<List<ItemWithCountries>>

    @Query("SELECT id FROM items WHERE name = :name LIMIT 1")
    suspend fun getItemIdByName(name: String): Long?

    @Query("SELECT id FROM countries WHERE name = :name LIMIT 1")
    suspend fun getCountryIdByName(name: String): Long?


    @Query("""
        UPDATE item_country
        SET weight = :weight, price = :price
        WHERE itemId = (SELECT id FROM items WHERE name = :item)
        AND countryId = (SELECT id FROM countries WHERE name = :country)
    """)
    suspend fun updateWeightAndPrice(item: String, country: String, weight: Float, price: Float)
    @Query("DELETE FROM item_country WHERE itemId = :itemId AND countryId = :countryId")
    suspend fun deleteLink(itemId: Long, countryId: Long)

    // 아이템 전체 삭제 (관계 → 본체 순)
    @Query("DELETE FROM item_country WHERE itemId = :itemId")
    suspend fun deleteLinksByItem(itemId: Long)

    @Query("DELETE FROM items WHERE id = :itemId")
    suspend fun deleteItemById(itemId: Long)

    // 나라 전체 삭제 (관계 → 본체 순)
    @Query("DELETE FROM item_country WHERE countryId = :countryId")
    suspend fun deleteLinksByCountry(countryId: Long)

    @Query("DELETE FROM countries WHERE id = :countryId")
    suspend fun deleteCountryById(countryId: Long)

    @Query("UPDATE item_country SET needed = :needed, have = :have WHERE itemId = :itemId AND countryId = :countryId")
    suspend fun updateQuantity(itemId: Long, countryId: Long, needed: Int, have: Int)

    @Query("""
SELECT i.name AS item,
       c.name AS country,
       ic.needed AS needed,
       ic.have AS have
FROM items i
JOIN item_country ic ON i.id = ic.itemId
JOIN countries c ON c.id = ic.countryId
ORDER BY i.name, c.name
""")
    fun observeItemCountryRows(): Flow<List<ItemCountryRow>>

    @Query("""
        SELECT have
        FROM item_country
        WHERE itemId = :itemId AND countryId = :countryId
        LIMIT 1
    """)
    suspend fun getHave(itemId: Long, countryId: Long): Int?

    @Insert
    suspend fun insertQuantityLog(log: QuantityLogEntity): Long

    // 3) (선택) 특정 링크의 로그 조회 - 최신순
    @Query("""
    SELECT * FROM quantity_log
    WHERE itemId = :itemId AND countryId = :countryId
    ORDER BY timestamp DESC
    LIMIT :limit
""")
    suspend fun getQuantityLogs(itemId: Long, countryId: Long, limit: Int = 50): List<QuantityLogEntity>

    data class AdditionRow(
        val id: Long = 0L,
        val item: String = "",
        val country: String = "",
        val needed: Int = 0,
        val have: Int = 0,
        val timestamp: Long = 0L,
        val batchId: Long = 0L
    )

    @Insert
    suspend fun insertAdditionLog(log: List<AdditionLogEntity>)

    @Query("""
    SELECT a.id AS id,
           i.name AS item,
           c.name AS country,
           a.needed AS needed,
           a.have   AS have,
           a.timestamp AS timestamp,
           0 AS batchId
    FROM addition_log a
    JOIN items i     ON i.id = a.itemId
    JOIN countries c ON c.id = a.countryId
    ORDER BY a.timestamp DESC
    LIMIT :limit
""")

    suspend fun getRecentAdditions(limit: Int = 200): List<AdditionRow>

    data class QuantityRow(
        val id: Long = 0L,
        val item: String = "",
        val country: String = "",
        val fromHave: Int = 0,
        val toHave: Int = 0,
        val delta: Int = 0,
        val timestamp: Long = 0L,
        val batchId: Long = 0L,
        val weight: Float,
        val price: Float
    )

    data class HistoryRow(
        val id: Long = 0L,
        val item: String = "",
        val country: String = "",
        val fromHave: Int = 0,
        val toHave: Int = 0,
        val timestamp: Long = 0L,
        val batchId: Long = 0L
    )

    // 저장 로그 전용 (배치가 있는 것만 보이게)
    @Query("""
    SELECT q.id AS id,
           i.name AS item,
           c.name AS country,
           q.fromHave AS fromHave,
           q.toHave AS toHave,
           q.delta AS delta,
           q.timestamp AS timestamp,
           COALESCE(q.batchId, 0) AS batchId,
           ic.weight AS weight,
           ic.price AS price
    FROM quantity_log q
    JOIN items i ON i.id = q.itemId
    JOIN countries c ON c.id = q.countryId
    JOIN item_country ic ON ic.itemId = q.itemId AND ic.countryId = q.countryId
    WHERE COALESCE(q.batchId, 0) > 0
    ORDER BY q.timestamp DESC
    LIMIT :limit
""")
    suspend fun getRecentQuantityLogs(limit: Int = 200): List<QuantityRow>

    @Query("""
    SELECT q.id AS id,
           i.name AS item,
           c.name AS country,
           q.fromHave AS fromHave,
           q.toHave AS toHave,
           q.timestamp AS timestamp,
           COALESCE(q.batchId, 0) AS batchId
    FROM quantity_log q
    JOIN items i ON i.id = q.itemId
    JOIN countries c ON c.id = q.countryId
    WHERE COALESCE(q.batchId, 0) > 0
    ORDER BY q.timestamp DESC
    LIMIT :limit
""")
    suspend fun getHistoryRowsNoDelta(limit: Int = 200): List<HistoryRow>

    // 아이템 탭: [+ 클릭]만 보기 (delta > 0, batchId = 0 또는 NULL)
    @Query("""
    SELECT q.id AS id,
           i.name AS item,
           c.name AS country,
           q.fromHave AS fromHave,
           q.toHave AS toHave,
           q.delta AS delta,
           q.timestamp AS timestamp,
           COALESCE(q.batchId, 0) AS batchId,
           ic.weight AS weight,
           ic.price AS price
    FROM quantity_log q
    JOIN items i ON i.id = q.itemId
    JOIN countries c ON c.id = q.countryId
    JOIN item_country ic ON ic.itemId = q.itemId AND ic.countryId = q.countryId
    WHERE q.delta > 0
      AND COALESCE(q.batchId, 0) = 0
    ORDER BY q.timestamp DESC
    LIMIT :limit
""")
    suspend fun getRecentPlusClicks(limit: Int = 200): List<QuantityRow>

    @Query("DELETE FROM quantity_log WHERE id = :id")
    suspend fun deleteQuantityLogById(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCountry(country: CountryEntity): Long

    @Query("SELECT name FROM countries ORDER BY name")
    fun getAllCountryNames(): kotlinx.coroutines.flow.Flow<List<String>>

    @Dao
    interface SaveArchiveDao {
        @Insert suspend fun insertSession(s: SaveSessionEntity): Long
        @Insert suspend fun insertLines(lines: List<SaveSessionLineEntity>)
        @Query("SELECT * FROM save_session ORDER BY createdAt DESC")
        suspend fun getSessions(): List<SaveSessionEntity>
        @Query("SELECT * FROM save_session WHERE country = :country ORDER BY createdAt DESC")
        suspend fun getSessionsByCountry(country: String): List<SaveSessionEntity>
        @Query("SELECT * FROM save_session_line WHERE sessionId = :sessionId ORDER BY timestamp DESC")
        suspend fun getLines(sessionId: Long): List<SaveSessionLineEntity>
        @Query("SELECT * FROM save_session_line WHERE sessionId = :sessionId AND country = :country AND COALESCE(batchId, 0) > 0 ORDER BY timestamp DESC")
        suspend fun getLinesByCountry(sessionId: Long, country: String): List<SaveSessionLineEntity>

        @Query("""
  SELECT *
  FROM save_session_line
  WHERE sessionId = :sessionId
    AND COALESCE(batchId, 0) > 0      -- ✅ 저장 로그만
  ORDER BY timestamp DESC
""")
        suspend fun getLinesExcludeClicks(sessionId: Long): List<SaveSessionLineEntity>

        @Query("""
            SELECT *FROM save_session_line WHERE sessionId = :sessionId ORDER BY timestamp DESC
            """)
        suspend fun getAllLines(sessionId: Long): List<SaveSessionLineEntity>

        @Query("""
SELECT *
FROM save_session_line
WHERE sessionId = :sessionId
  AND (:onlySaved = 0 OR COALESCE(batchId, 0) > 0)
ORDER BY timestamp DESC
""")
        fun observeSessionLines(
            sessionId: Long,
            onlySaved: Int // 0 = 전체, 1 = 저장 로그만(batchId > 0)
        ): kotlinx.coroutines.flow.Flow<List<SaveSessionLineEntity>>

    }

    @Query("""
DELETE FROM quantity_log
WHERE countryId IN (SELECT id FROM countries WHERE name = :country)
AND COALESCE(batchId, 0) > 0
""")
    suspend fun deleteQuantityLogsByCountry(country: String)

    // 나라 이름으로 해당 나라 로그만 불러오기 (QuantityRow는 네가 이미 쓰는 DTO)
    @Query("""
SELECT q.id AS id,
       i.name AS item,
       c.name AS country,
       q.fromHave AS fromHave,
       q.toHave AS toHave,
       q.delta AS delta,
       q.timestamp AS timestamp,
       COALESCE(q.batchId, 0) AS batchId,
       ic.weight AS weight,           -- ✅ 추가
       ic.price  AS price             -- ✅ 추가
FROM quantity_log q
JOIN items i     ON i.id = q.itemId
JOIN countries c ON c.id = q.countryId
JOIN item_country ic ON ic.itemId = q.itemId AND ic.countryId = q.countryId   -- ✅ 조인 추가
WHERE c.name = :country
ORDER BY q.timestamp DESC
LIMIT :limit
""")
    suspend fun getQuantityLogsByCountry(country: String, limit: Int = 500): List<QuantityRow>

    @Query("""
        UPDATE item_country
        SET have = 0
        WHERE countryId IN (SELECT id FROM countries WHERE name = :country)
    """)
    suspend fun resetHaveByCountry(country: String)
    @Query("SELECT name FROM items ORDER BY name")
    suspend fun getAllItemsNames(): List<String>


    // 아카이브에 저장된 내역을 기준으로 "최근 since(ms) 이후 나간 수량" 집계.
// items에 없는 이름도 있을 수 있어서, items 테이블을 기준으로 왼쪽 조인해 0을 채움.
    @Query("""
    SELECT i.name AS item,
           COALESCE((
             SELECT SUM(CASE WHEN l.delta < 0 AND l.timestamp >= :since THEN l.delta ELSE 0 END)
             FROM save_session_line l
             WHERE l.item = i.name
           ), 0) AS totalOut
    FROM items i
    ORDER BY totalOut ASC
""")
    suspend fun getArchivedOutflowSinceIncludingZero(since: Long): List<ItemOutflowRow>

    @Query("""
    SELECT i.name AS item,
           COALESCE((
             SELECT SUM(CASE WHEN l.delta < 0 THEN l.delta ELSE 0 END)
             FROM save_session_line l
             WHERE l.item = i.name
           ), 0) AS totalOut
    FROM items i
    ORDER BY totalOut ASC
""")
    suspend fun getArchivedOutflowAllTimeIncludingZero(): List<ItemOutflowRow>
    data class ItemOutflowRow(
        val item: String,
        val totalOut: Int
    )

    @Query("""
SELECT i.name AS item, ic.have AS have
FROM item_country ic
JOIN items i ON i.id = ic.itemId
JOIN countries c ON c.id = ic.countryId
WHERE c.name = :country
""")
    suspend fun getHaveByCountry(country: String): List<HaveRow>

    data class HaveRow(
        val item: String,
        val have: Int
    )

    @Query("UPDATE quantity_log SET archived = 1 WHERE countryId = :countryId")
    suspend fun markQuantityLogsArchivedByCountry(countryId: Long)


    data class SheetWithLines(
        @Embedded val sheet: SheetEntity,
        @Relation(
            parentColumn = "id",
            entityColumn = "sheetId",
            entity = SheetLineEntity::class
        )
        val lines: List<SheetLineEntity>
    )

    @Dao
    interface SheetDao {
        @Insert
        suspend fun insertSheet(sheet: SheetEntity): Long

        @Insert
        suspend fun insertSheetLines(lines: List<SheetLineEntity>)

        @Query("SELECT * FROM sheets WHERE hidden = 0 ORDER BY createdAt DESC")
        fun observeVisibleSheets(): Flow<List<SheetEntity>>

        @Query("SELECT * FROM sheets ORDER BY createdAt DESC")
        fun observeAllSheets(): Flow<List<SheetEntity>>

        @Query("SELECT * FROM sheet_lines WHERE sheetId = :sheetId ORDER BY item, country")
        suspend fun getSheetLines(sheetId: Long): List<SheetLineEntity>

        @Query("UPDATE sheets SET hidden = :hidden WHERE id = :sheetId")
        suspend fun setSheetHidden(sheetId: Long, hidden: Boolean)

        @Query("DELETE FROM sheet_lines WHERE sheetId = :sheetId")
        suspend fun deleteSheetLinesBySheet(sheetId: Long)

        @Query("DELETE FROM sheets WHERE id = :sheetId")
        suspend fun deleteSheetById(sheetId: Long)
        @Transaction
        @Query("SELECT * FROM sheets ORDER BY createdAt DESC")
        fun observeSheetsWithLines(): Flow<List<SheetWithLines>>

        @Query("SELECT COUNT(*) FROM sheet_lines WHERE sheetId = :sheetId")
        suspend fun countLinesBySheetId(sheetId: Long): Int

        @Query("SELECT COUNT(*) FROM sheet_lines WHERE sheetId = 0")
        suspend fun countOrphans(): Int


    }

    @Query("SELECT * FROM sheets WHERE id = :sheetId LIMIT 1")
    suspend fun getSheetById(sheetId: Long): SheetEntity?

    @Query("SELECT * FROM sheet_lines WHERE sheetId = :sheetId")
    suspend fun getSheetLines(sheetId: Long): List<SheetLineEntity>

    @Query("DELETE FROM item_country WHERE itemId = :itemId AND countryId = :countryId")
    suspend fun deleteLinkByIds(itemId: Long, countryId: Long)

    @Query("SELECT * FROM sheet_lines WHERE sheetId = :sheetId ORDER BY createdAt ASC")
    fun observeSheetLines(sheetId: Long): kotlinx.coroutines.flow.Flow<List<SheetLineEntity>>

    @Update
    suspend fun updateSheetLine(line: SheetLineEntity)

    @Delete
    suspend fun deleteSheetLine(line: SheetLineEntity)

    @Insert
    suspend fun insertSheetLine(line: SheetLineEntity)


}