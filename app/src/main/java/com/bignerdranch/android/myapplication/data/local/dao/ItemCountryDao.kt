package com.bignerdranch.android.myapplication.data.local.dao

import androidx.room.ColumnInfo
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
import com.bignerdranch.android.myapplication.data.local.entity.ItemSearchRow
import com.bignerdranch.android.myapplication.data.local.entity.PredictionAckEntity
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
    val have: Int,
    val price: Float?
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
       ic.have AS have,
       ic.price AS price
FROM items i
JOIN item_country ic ON i.id = ic.itemId
JOIN countries c ON c.id = ic.countryId
WHERE c.hidden = 0
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
    suspend fun getQuantityLogs(itemId: Long, countryId: Long, limit: Int = 20000): List<QuantityLogEntity>

    @Update
    suspend fun updateQuantityLog(entity: QuantityLogEntity)

    @Query("SELECT * FROM quantity_log WHERE id = :id LIMIT 1")
    suspend fun getQuantityLogById(id: Long): QuantityLogEntity?

    @Query("""
SELECT id, name FROM items
WHERE name LIKE '%' || :q || '%'
ORDER BY name
LIMIT 50
""")
    suspend fun searchItemsLite(q: String): List<ItemLite> // ItemLite(id,name) data class

    @Query("""
SELECT toHave FROM quantity_log
WHERE itemId = :itemId
AND countryId IS NULL
AND archived = 0
ORDER BY timestamp DESC, id DESC
LIMIT 1
""")
    suspend fun getLatestToHaveNoCountry(itemId: Long): Int?

    @Query("""
SELECT toHave FROM quantity_log
WHERE itemId = :itemId
AND countryId = :countryId
AND archived = 0
ORDER BY timestamp DESC, id DESC
LIMIT 1
""")
    suspend fun getLatestToHave(itemId: Long, countryId: Long): Int?

    @Query("""
SELECT
l.sheetId AS sheetId,
s.title AS sheetTitle,
l.country AS country,
l.item AS item,
l.price AS price,
l.weight AS weight
FROM sheet_lines AS l
JOIN sheets AS s ON l.sheetId = s.id
WHERE :q = ''
OR l.item LIKE '%' || :q || '%'
OR l.country LIKE '%' || :q || '%'
OR s.title LIKE '%' || :q || '%'
ORDER BY s.title, l.country, l.item
LIMIT 200
""")
    suspend fun searchItemsOnce(q: String): List<ItemSearchRow>

    data class IdName(val id: Long, val name: String)

    @Query("""
SELECT MIN(id) AS id, name
FROM items
GROUP BY name
ORDER BY name
""")
    suspend fun getAllItemsIdName(): List<IdName>

    @Query("""
SELECT MIN(id) AS id, name
FROM countries
WHERE hidden = 0
GROUP BY name
ORDER BY name
""")
    suspend fun getAllCountriesIdName(): List<IdName>

    @Query("""
SELECT i.id AS id, i.name AS name
FROM items i
WHERE EXISTS (
  SELECT 1 FROM item_country ic
  WHERE ic.itemId = i.id
)
ORDER BY i.name
""")
    suspend fun getActiveItemsIdName(): List<IdName>

    // ItemCountryDao

    @Query("""
SELECT c.id AS id, c.name AS name
FROM item_country ic
JOIN countries c ON c.id = ic.countryId
WHERE ic.itemId = :itemId AND c.hidden = 0
ORDER BY c.name
""")
    suspend fun getCountriesForItem(itemId: Long): List<IdName>

    @Query("""
SELECT i.id AS id, i.name AS name
FROM items i
ORDER BY i.name
""")
    suspend fun getAllItemsIdName2(): List<IdName> // 이미 있으면 재사용

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

    suspend fun getRecentAdditions(limit: Int = 20000): List<AdditionRow>

    data class QuantityRow(
        val id: Long = 0L,
        val item: String = "",
        val country: String = "",
        val fromHave: Int = 0,
        val toHave: Int = 0,
        val delta: Int = 0,
        val timestamp: Long = 0L,
        val batchId: Long = 0L,
        val weight: Float?,
        val price: Int?
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
COALESCE(q.itemName, i.name) AS item,
COALESCE(q.countryName, c.name) AS country,
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
    LEFT JOIN items i ON i.id = q.itemId
    LEFT JOIN countries c ON c.id = q.countryId
    JOIN item_country ic ON ic.itemId = q.itemId AND ic.countryId = q.countryId
    WHERE COALESCE(q.batchId, 0) > 0
    ORDER BY q.timestamp DESC
    LIMIT :limit
""")
    suspend fun getRecentQuantityLogs(limit: Int = 20000): List<QuantityRow>

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
    suspend fun getHistoryRowsNoDelta(limit: Int = 20000): List<HistoryRow>

    // 아이템 탭: [+ 클릭]만 보기 (delta > 0, batchId = 0 또는 NULL)
    @Query("""
SELECT
  q.id AS id,
  COALESCE(q.itemName, i.name, '(deleted)') AS item,
  COALESCE(q.countryName, c.name, '(deleted)') AS country,
  q.fromHave AS fromHave,
  q.toHave AS toHave,
  q.delta AS delta,
  q.timestamp AS timestamp,
  COALESCE(q.batchId, 0) AS batchId,
  q.weightAt AS weight,
  q.priceAt AS price
FROM quantity_log q
LEFT JOIN items i ON i.id = q.itemId
LEFT JOIN countries c ON c.id = q.countryId
WHERE q.delta > 0
  AND COALESCE(q.batchId, 0) = 0
ORDER BY q.timestamp DESC, q.id DESC
LIMIT :limit
""")
    suspend fun getRecentPlusClicks(limit: Int = 20000): List<QuantityRow>

    @Query("""
UPDATE quantity_log
SET
  weightAt = COALESCE(weightAt, (
    SELECT sl.weight
    FROM sheet_lines sl
    WHERE sl.item = COALESCE(quantity_log.itemName, (SELECT name FROM items WHERE id = quantity_log.itemId))
      AND sl.country = COALESCE(quantity_log.countryName, (SELECT name FROM countries WHERE id = quantity_log.countryId))
      AND sl.weight > 0
      AND sl.isDeleted = 0
      AND sl.hidden = 0
    ORDER BY sl.createdAt DESC, sl.id DESC
    LIMIT 1
  )),
  priceAt = COALESCE(priceAt, (
    SELECT sl.price
    FROM sheet_lines sl
    WHERE sl.item = COALESCE(quantity_log.itemName, (SELECT name FROM items WHERE id = quantity_log.itemId))
      AND sl.country = COALESCE(quantity_log.countryName, (SELECT name FROM countries WHERE id = quantity_log.countryId))
      AND sl.price > 0
      AND sl.isDeleted = 0
      AND sl.hidden = 0
    ORDER BY sl.createdAt DESC, sl.id DESC
    LIMIT 1
  ))
WHERE (weightAt IS NULL OR weightAt = 0)
   OR (priceAt  IS NULL OR priceAt  = 0)
""")
    suspend fun backfillLogSnapshotsFromSheetLines(): Int

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
       COALESCE(q.itemName, i.name) AS item,
COALESCE(q.countryName, c.name) AS country,
q.weightAt AS weight,
q.priceAt AS price
FROM quantity_log q
JOIN items i     ON i.id = q.itemId
JOIN countries c ON c.id = q.countryId
JOIN item_country ic ON ic.itemId = q.itemId AND ic.countryId = q.countryId   -- ✅ 조인 추가
WHERE c.name = :country
ORDER BY q.timestamp DESC
LIMIT :limit
""")
    suspend fun getQuantityLogsByCountry(country: String, limit: Int = 20000): List<QuantityRow>

    @Query("""
        UPDATE item_country
        SET have = 0
        WHERE countryId IN (SELECT id FROM countries WHERE name = :country)
    """)
    suspend fun resetHaveByCountry(country: String)
    @Query("SELECT name FROM items ORDER BY name")
    suspend fun getAllItemsNames(): List<String>

    @Query("SELECT name FROM items WHERE id = :itemId LIMIT 1")
    suspend fun getItemNameById(itemId: Long): String?

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
        @Query("UPDATE sheets SET title = :title WHERE id = :sheetId")
        suspend fun updateSheetTitle(sheetId: Long, title: String)
        @Query("UPDATE sheet_lines SET country = :newCountry WHERE sheetId = :sheetId AND country = :oldCountry")
        suspend fun renameCountryInSheet(sheetId: Long, oldCountry: String, newCountry: String)

        @Query("DELETE FROM sheet_lines WHERE sheetId = :sheetId")
        suspend fun deleteSheetLinesBySheet(sheetId: Long)
        @Query("DELETE FROM sheet_lines WHERE sheetId = :sheetId AND country = :country")
        suspend fun deleteSheetLinesByCountry(sheetId: Long, country: String)
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

    // 삭제 기능

    @Query("""
SELECT * FROM sheet_lines
WHERE sheetId = :sheetId AND isDeleted = 0
ORDER BY id DESC
""")
    fun observeActiveLines(sheetId: Long): Flow<List<SheetLineEntity>>

    @Query("""
SELECT * FROM sheet_lines
WHERE sheetId = :sheetId AND isDeleted = 1
ORDER BY deletedAt DESC
""")
    fun observeDeletedLines(sheetId: Long): Flow<List<SheetLineEntity>>

    @Query("""
UPDATE sheet_lines
SET isDeleted = 0, deletedAt = NULL
WHERE id = :lineId
""")
    suspend fun restoreLine(lineId: Long)

    @Query("DELETE FROM sheet_lines WHERE id = :lineId")
    suspend fun hardDeleteLine(lineId: Long)

    @Query("""
UPDATE sheet_lines
SET isDeleted = 1, deletedAt = :deletedAt
WHERE id = :lineId
""")
    suspend fun softDelete(lineId: Long, deletedAt: Long)

    //

    // ItemCountryDao.kt
    @Query("""
UPDATE item_country AS ic
SET
  weight = COALESCE((
    SELECT sl.weight
    FROM sheet_lines sl
    WHERE sl.item = (SELECT name FROM items WHERE id = ic.itemId)
      AND sl.country = (SELECT name FROM countries WHERE id = ic.countryId)
      AND sl.weight > 0
    ORDER BY sl.createdAt DESC
    LIMIT 1
  ), weight),
  price = COALESCE((
    SELECT sl.price
    FROM sheet_lines sl
    WHERE sl.item = (SELECT name FROM items WHERE id = ic.itemId)
      AND sl.country = (SELECT name FROM countries WHERE id = ic.countryId)
      AND sl.price > 0
    ORDER BY sl.createdAt DESC
    LIMIT 1
  ), price)
""")
    suspend fun backfillItemCountryWeightPriceFromSheets()

    // (1) 리스트에 쓸 Row
    data class ItemWithOff(
        val itemId: Long,
        val countryId: Long,
        val item: String,
        val country: String,
        val have: Int,        // 본 have (item_country.have)
        val offHave: Int,     // OFF 모드로 쌓인 합계(Σ delta where batchId=0)
        val needed: Int,
        val weight: Float?,
        val price: Int?,
        val countryHidden: Boolean
    )

    // (2) OFF 모드 집계 포함해 관찰
    @Query("""
SELECT 
  ic.itemId,
  ic.countryId,
  i.name AS item,
  c.name AS country,
  ic.have AS have,
  COALESCE((
    SELECT SUM(q.delta) 
    FROM quantity_log q
    WHERE q.itemId = ic.itemId 
      AND q.countryId = ic.countryId
      AND COALESCE(q.batchId, 0) = 0
  ), 0) AS offHave,
  ic.needed AS needed,
  ic.weight AS weight,
  ic.price AS price,
  c.hidden AS countryHidden
FROM item_country ic
JOIN items i ON i.id = ic.itemId
JOIN countries c ON c.id = ic.countryId
WHERE c.hidden = 0
ORDER BY i.name, c.name
""")
    fun observeItemsWithOff(): kotlinx.coroutines.flow.Flow<List<ItemWithOff>>

    // (3) OFF 모드 클릭은 로그만 남기기

    @androidx.room.Transaction
    suspend fun addOffClick(itemId: Long, countryId: Long, delta: Int) {
        if (delta <= 0) return
        val before = getOffHave(itemId, countryId)
        val after = before + delta
        insertQuantityLog(
            QuantityLogEntity(
                itemId = itemId,
                countryId = countryId,
                fromHave = before, // 본 have는 안 바꾸니 0(또는 생략 가능)
                toHave = after,
                delta = delta,
                timestamp = System.currentTimeMillis(),
                batchId = null // 또는 0 (쿼리에서 COALESCE로 0 취급)
            )
        )
    }

    @androidx.room.Transaction
    suspend fun removeOffClick(itemId: Long, countryId: Long) {

        val before = getOffHave(itemId, countryId)
        val after = before - 1

        if (before <= 0) return

        insertQuantityLog(
            QuantityLogEntity(
                itemId = itemId,
                countryId = countryId,
                fromHave = before, // 본 have는 안 바꾸니 0(또는 생략 가능)
                toHave = after,
                delta = -1,
                timestamp = System.currentTimeMillis(),
                batchId = null // 또는 0 (쿼리에서 COALESCE로 0 취급)
            )
        )
    }

    // (선택) 특정 아이템의 offHave만 읽기
    @Query("""
SELECT COALESCE(SUM(delta), 0)
FROM quantity_log
WHERE itemId = :itemId
  AND countryId = :countryId
  AND COALESCE(batchId, 0) = 0
""")
    suspend fun getOffHave(itemId: Long, countryId: Long): Int

    // (선택) OFF 집계 초기화(로그 삭제)
    @Query("""
DELETE FROM quantity_log
WHERE itemId = :itemId
  AND countryId = :countryId
  AND COALESCE(batchId, 0) = 0
""")
    suspend fun clearOffFor(itemId: Long, countryId: Long): Int

    data class HaveOffRow(
        val itemId: Long,
        val countryId: Long,
        val have: Int,
        val offHave: Int
    )

    @Query("""
        SELECT ic.itemId AS itemId,
        ic.countryId AS countryId,
        ic.have AS have,
        COALESCE((
        SELECT SUM(q.delta)
        FROM quantity_log q
        WHERE q.itemId = ic.itemId
        AND q.countryId = ic.countryId
        AND COALESCE(q.batchId, 0) = 0
        AND COALESCE(q.archived, 0) = 0
        ), 0) As offHave
        FROM item_country ic
        JOIN countries c ON c.id = ic.countryId
        WHERE c.name = :country
    """)
    suspend fun getHaveAndOffByCountry(country: String): List<HaveOffRow>


    // ✅ 핵심: have를 0으로 내릴 때 off 로그에서도 동일 수량만큼(가능한 범위 내에서) 차감
    @Transaction
    suspend fun resetHaveAndConsumeOffByCountry(country: String, now: Long = System.currentTimeMillis()) {
        val rows = getHaveAndOffByCountry(country)
        rows.forEach { r ->
            val consume = kotlin.math.min(r.have, r.offHave)
            if (consume > 0) {
                insertQuantityLog(
                    QuantityLogEntity(
                        id = 0,
                        itemId = r.itemId,
                        countryId = r.countryId,
                        fromHave = 0,        // 필요 없으면 0/NULL 유지
                        toHave = 0,
                        delta = -consume,    // 🔻 offHave에서 차감
                        batchId = 0L,        // ← off 모드
                        timestamp = now,
                        archived = 0
                    )
                )
            }
        }
        // 마지막에 실제 재고 0으로
        resetHaveByCountry(country)
    }

    // ✅ ItemCountryDao.kt 안에 그대로 두고 이걸로 교체
    @Query("""
    SELECT country AS name,
    MAX(hidden) AS hidden,
    COUNT(*) AS lineCount,
    GROUP_CONCAT(item, ', ') AS items
    FROM sheet_lines
    WHERE sheetId = :sheetId
    GROUP BY country
    ORDER BY country
""")
    suspend fun getCountriesBySheet(sheetId: Long): List<CountryRow>

    data class CountryRow(
        val name: String,
        var hidden: Boolean,
        val lineCount: Int,
        val items: String
    )

    @Query("""
    UPDATE item_country
    SET lastClickedAt = :ts
    WHERE itemId = :itemId
      AND countryId = :countryId
""")
    suspend fun updateLastClickedAt(
        itemId: Long,
        countryId: Long,
        ts: Long
    )

    @Query("""
        SELECT i.name AS item,
               c.name AS country,
               ic.lastClickedAt AS ts
        FROM item_country ic
        JOIN items i ON i.id = ic.itemId
        JOIN countries c ON c.id = ic.countryId
        WHERE ic.lastClickedAt IS NOT NULL
        ORDER BY ic.lastClickedAt DESC
        LIMIT 3
    """)
    fun getRecentTouched(): Flow<List<RecentRow>>

    data class RecentRow(
        val item: String,
        val country: String,
        val ts: Long?
    )

    @Query("""
    SELECT lastClickedAt 
    FROM item_country 
    WHERE itemId = :itemId AND countryId = :countryId 
    LIMIT 1
""")
    suspend fun getLastClickedAt(itemId: Long, countryId: Long): Long?

    @androidx.room.Transaction
    suspend fun addOffDelta(itemId: Long, countryId: Long, delta: Int) {
        if (delta == 0) return

        val before = getOffHave(itemId, countryId)
        val after = before + delta

        insertQuantityLog(
            QuantityLogEntity(
                id = 0,
                itemId = itemId,
                countryId = countryId,
                fromHave = before,
                toHave = after,
                delta = delta,
                timestamp = System.currentTimeMillis(),
                batchId = null,   // OFF 모드 그대로
                archived = 0
            )
        )
    }
    @Query("""
    UPDATE sheet_lines
    SET hidden = :newHidden
    WHERE sheetId = :sheetId
      AND country = :country
""")
    suspend fun toggleCountryHidden(
        sheetId: Long,
        country: String,
        newHidden: Boolean
    )
    @Query("""
UPDATE countries
SET hidden = :newHidden
WHERE name = :country
""")
    suspend fun setCountryHidden(country: String, newHidden: Boolean)
    data class CountryDebugRow(
        val id: Long,
        val name: String,
        val hidden: Boolean
    )

    @Query("""
SELECT id, name, hidden
FROM countries
ORDER BY name
""")
    suspend fun debugCountries(): List<CountryDebugRow>

    @Query("""
        SELECT
        l.sheetId AS sheetId,
s.title AS sheetTitle,
        l.country AS country,
        l.item AS item,
        l.price AS price,
        l.weight AS weight
        FROM sheet_lines AS l
        JOIN sheets AS s ON l.sheetId = s.id
        WHERE :q = ''
        OR l.item LIKE '%' || :q || '%'
        OR l.country LIKE '%' || :q || '%'
        OR s.title LIKE '%' || :q || '%'
        ORDER BY s.title, l.country, l.item
    """)
    fun searchItems(q: String): Flow<List<ItemSearchRow>>

    @Query("""
        SELECT s.id AS sheetId,
        s.title AS sheetTitle,
        l.country AS country,
        l.item AS item,
        l.price AS price,
        l.weight AS weight
        FROM sheet_lines AS l
        JOIN sheets AS s ON l.sheetId = s.id
        WHERE LOWER(l.item) = LOWER(:item) AND LOWER(l.country) = LOWER(:country)
        ORDER BY s.id DESC
        LIMIT 1
    """)
    suspend fun findOneSearchRowByItem(item: String, country: String): ItemSearchRow?

    @Query("""
        SELECT COUNT(*)
        FROM sheet_lines
        WHERE LOWER(item) = LOWER(:item)
        AND LOWER(country) = LOWER(:country)
    """)
    suspend fun countSheetLinesByItemAndCountry(item: String, country: String): Int

    @Transaction
    suspend fun deleteSheetLineAndUnlinkIfOrphan(line: SheetLineEntity) {
        // 1) sheet_lines 삭제
        deleteSheetLineById(line.id)

        // 2) 같은 (item,country)가 다른 시트에 남아있는지
        val remain = countSheetLinesByItemAndCountry(line.item, line.country)

        // 3) 없으면 홈 링크(item_country) 제거
        if (remain == 0) {
            val itemId = getItemIdByName(line.item) ?: return
            val countryId = getCountryIdByName(line.country) ?: return
            deleteLink(itemId, countryId)
        }
    }
    @Query("DELETE FROM sheet_lines WHERE id = :id")
    suspend fun deleteSheetLineById(id: Long)

    @Query("""
        SELECT itemId, countryId, timestamp
        FROM quantity_log
        WHERE delta > 0
        AND COALESCE(batchId, 0) = 0
        AND COALESCE(archived, 0) = 0
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getRecentPlusLogLite(limit: Int = 20000): List<PlusLiteRow>

    data class PlusLiteRow(
        val itemId: Long,
        val countryId: Long,
        val timestamp: Long
    )

    data class NamePairRow(
        val itemId: Long,
        val countryId: Long,
        val item: String,
        val country: String
    )

    @Query("""
        SELECT ic.itemId AS itemId,
        ic.countryId AS countryId,
        i.name AS item,
        c.name AS country
        FROM item_country ic
        JOIN items i ON i.id = ic.itemId
        JOIN countries c ON c.id = ic.countryId
    """)
    suspend fun getAllNamePairs(): List<NamePairRow>

    data class PlusLiteIdRow(
        val itemId: Long,
        val countryId: Long,
        val timestamp: Long
    )

    @Query("""
SELECT itemId, countryId, timestamp
FROM quantity_log
WHERE delta > 0
  AND countryId IS NOT NULL
  AND COALESCE(batchId, 0) = 0
  AND COALESCE(archived, 0) = 0
ORDER BY timestamp DESC, id DESC
LIMIT :limit
""")
    suspend fun getRecentPlusLogLiteIds(limit: Int = 20000): List<PlusLiteIdRow>

    data class ItemLite(val id: Long, val name: String)

    @Query("SELECT id, name FROM items")
    suspend fun getAllItemsLite(): List<ItemLite>

    @Query("SELECT name FROM countries WHERE id = :countryId LIMIT 1")
    suspend fun getCountryNameById(countryId: Long): String?


    @Query("SELECT ackAt FROM prediction_ack WHERE itemId = :itemId LIMIT 1")
    suspend fun getAckAt(itemId: Long): Long?

    @Upsert
    suspend fun upsertAck(e: PredictionAckEntity)

    data class CountryLite(val id: Long, val name: String)

    @Query("SELECT id, name FROM countries")
    suspend fun getAllCountriesLite(): List<CountryLite>

    //고아링크 제거
    @Query("""
DELETE FROM sheet_lines
WHERE id = (SELECT id FROM items WHERE name = :item)
  AND country = (SELECT id FROM countries WHERE name = :country)
  AND NOT EXISTS (
      SELECT 1 FROM sheet_lines
      WHERE item = :item AND country = :country
  )
""")
    suspend fun unlinkIfOrphanItemCountry(item: String, country: String)

    // ✅ 시트라인에 (item,country) 조합이 몇 개 남았는지
    @Query("""
SELECT COUNT(*) FROM sheet_lines
WHERE item = :item AND country = :country
""")
    suspend fun countSheetLinesByItemCountry(item: String, country: String): Int

    //수량 로그 이사
    @Query("""
UPDATE quantity_log
SET itemId = :newItemId,
    countryId = :newCountryId
WHERE itemId = :oldItemId
  AND countryId = :oldCountryId
""")
    suspend fun migrateQuantityLogs(
        oldItemId: Long,
        oldCountryId: Long,
        newItemId: Long,
        newCountryId: Long
    )

    @Query("""
UPDATE addition_log
SET itemId = :newItemId,
    countryId = :newCountryId
WHERE itemId = :oldItemId
  AND countryId = :oldCountryId
""")
    suspend fun migrateAdditionLogs(
        oldItemId: Long,
        oldCountryId: Long,
        newItemId: Long,
        newCountryId: Long
    )

    @Query("""
UPDATE prediction_ack
SET itemId = :newItemId
WHERE itemId = :oldItemId
""")
    suspend fun migratePredictionAcks(
        oldItemId: Long,
        newItemId: Long,
    )

    @Query("SELECT * FROM prediction_ack WHERE itemId = :itemId LIMIT 1")
    suspend fun getPredictionAck(itemId: Long): PredictionAckEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertPredictionAck(e: PredictionAckEntity)

    @Query("DELETE FROM prediction_ack WHERE itemId = :itemId")
    suspend fun deletePredictionAck(itemId: Long)

    data class ItemCountryPrice(
        val item: String,
        val country: String,
        val price: Int
    )

    @Query("""
SELECT sl.item AS item,
       sl.country AS country,
       sl.price AS price
FROM sheet_lines sl
JOIN (
  SELECT item, country, MAX(createdAt) AS maxC
  FROM sheet_lines
  WHERE price > 0
  GROUP BY item, country
) t
ON sl.item = t.item AND sl.country = t.country AND sl.createdAt = t.maxC
""")
    fun observeLatestItemCountryPrices(): Flow<List<ItemCountryPrice>>
    @Query("""
SELECT id FROM sheets
ORDER BY id DESC
LIMIT 1
""")
    suspend fun getLatestSheetId(): Long?

    @Query("""
SELECT item AS item,
       country AS country,
       price AS price
FROM sheet_lines
WHERE sheetId = :sheetId
""")
    suspend fun loadItemCountryPrices(sheetId: Long): List<ItemCountryPrice>

    //// 스냅샷

    data class Snapshot(
        val itemName: String,
        val countryName: String?,
        val priceAt: Int?,
        val weightAt: Float?
    )

    @Query("""
SELECT
  i.name AS itemName,
  c.name AS countryName,
  COALESCE(ic.price , sl.price ) AS priceAt,
  COALESCE(ic.weight, sl.weight) AS weightAt
FROM items i
LEFT JOIN countries c ON c.id = :countryId
LEFT JOIN item_country ic ON ic.itemId = :itemId AND ic.countryId = :countryId
LEFT JOIN (
  SELECT s1.item, s1.country, s1.weight, s1.price
  FROM sheet_lines s1
  JOIN (
    SELECT item, country, MAX(createdAt) AS maxC
    FROM sheet_lines
    WHERE (weight > 0 OR price > 0)
    GROUP BY item, country
  ) s2
  ON s1.item = s2.item AND s1.country = s2.country AND s1.createdAt = s2.maxC
) sl ON sl.item = i.name AND sl.country = c.name
WHERE i.id = :itemId
LIMIT 1
""")
    suspend fun getSnapshot(itemId: Long, countryId: Long?): Snapshot?

    @Transaction
    suspend fun insertQuantityLogWithSnapshot(log: QuantityLogEntity): Long {
        val snap = getSnapshot(log.itemId, log.countryId)
        val fixed = log.copy(
            itemName = snap?.itemName,
            countryName = snap?.countryName,
            priceAt = snap?.priceAt,
            weightAt = snap?.weightAt
        )
        return insertQuantityLog(fixed)
    }

    @Transaction
    suspend fun insertQuantityLogSnap(log: QuantityLogEntity): Long {
        val snap = getSnapshot(log.itemId, log.countryId)
        val fixed = log.copy(
            itemName = snap?.itemName ?: log.itemName,
            countryName = snap?.countryName ?: log.countryName,
            priceAt = snap?.priceAt ?: log.priceAt,
            weightAt = snap?.weightAt ?: log.weightAt
        )
        return insertQuantityLog(fixed)
    }


    data class NameSnap(val itemName: String, val countryName: String?)

    @Query("""
SELECT
  i.name AS itemName,
  c.name AS countryName
FROM items i
LEFT JOIN countries c ON c.id = :countryId
WHERE i.id = :itemId
LIMIT 1
""")
    suspend fun getNameSnap(itemId: Long, countryId: Long?): NameSnap?

    @Transaction
    suspend fun insertQuantityLogFixed(log: QuantityLogEntity): Long {
        val snap = getNameSnap(log.itemId, log.countryId)
        val fixed = log.copy(
            itemName = snap?.itemName ?: log.itemName,      // 혹시 이미 들어오면 유지
            countryName = snap?.countryName ?: log.countryName
            // priceAt/weightAt도 같은 방식으로 스냅샷 채우면 됨
        )
        return insertQuantityLog(fixed)
    }

    data class WeightPrice(
        val weight: Float,
        val price: Int
    )

    @Query("""
SELECT weight, price
FROM sheet_lines
WHERE item = :itemName
  AND country = :countryName
  AND isDeleted = 0
LIMIT 1
""")
    suspend fun getSheetLineForItemCountry(
        itemName: String,
        countryName: String
    ): WeightPrice?

    data class ItemReport(
        val itemId: Long,
        val itemName: String,
        val summary: Summary,
        val logs: List<LogRow>,          // 최신부터 30
        val nextPredictions: List<Pred>  // 5개
    ) {
        data class Summary(
            val kgPerHour: Double?,      // null이면 계산불가
            val avgWorkGapMs: Long?,     // 최근 30 평균
            val sampleCount: Int,        // 속도 계산에 쓴 개수
            val totalCount: Int          // 보통 30
        )

        data class LogRow(
            val ts: Long,                // currTs
            val country: String,
            val weightKg: Double?,       // 0이면 null 처리 추천
            val speedKgPerHour: Double?, // weight/gap 둘 다 있어야 계산
            val workGapMs: Long
        )

        data class Pred(
            val predictedAt: Long,
            val label: String
        )
    }

    @Query("""
SELECT itemId, countryId, timestamp
FROM quantity_log
WHERE delta > 0 AND itemId = :itemId
ORDER BY timestamp ASC
LIMIT :limit
""")
    suspend fun getRecentPlusLogLiteIdsByItemAsc(
        itemId: Long,
        limit: Int
    ): List<PlusLiteIdRow>

    data class CountryWeightRow(
        val country: String,
        val weight: Float
    )

    @Query("""
SELECT country, weight
FROM sheet_lines
WHERE item = :itemName
  AND isDeleted = 0
""")
    suspend fun getWeightsByItemName(itemName: String): List<CountryWeightRow>

    sealed class ItemReportUi {
        data class Header(val title: String) : ItemReportUi()

        data class LogRow(
            val time: String,
            val country: String,
            val weight: String,
            val speed: String,
            val duration: String
        ) : ItemReportUi()

        data class PredictionRow(
            val time: String,
            val label: String
        ) : ItemReportUi()
    }
}