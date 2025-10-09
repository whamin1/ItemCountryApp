package com.bignerdranch.android.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Junction
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import com.bignerdranch.android.myapplication.data.local.entity.AdditionLogEntity
import com.bignerdranch.android.myapplication.data.local.entity.CountryEntity
import com.bignerdranch.android.myapplication.data.local.entity.ItemCountryCrossRef
import com.bignerdranch.android.myapplication.data.local.entity.ItemEntity
import com.bignerdranch.android.myapplication.data.local.entity.QuantityLogEntity
import com.bignerdranch.android.myapplication.data.local.entity.SaveSessionEntity
import com.bignerdranch.android.myapplication.data.local.entity.SaveSessionLineEntity
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
    suspend fun insertQuantityLog(log: QuantityLogEntity)

    // 3) (선택) 특정 링크의 로그 조회 - 최신순
    @Query("""
    SELECT * FROM quantity_log
    WHERE itemId = :itemId AND countryId = :countryId
    ORDER BY timestamp DESC
    LIMIT :limit
""")
    suspend fun getQuantityLogs(itemId: Long, countryId: Long, limit: Int = 50): List<QuantityLogEntity>

    data class AdditionRow(
        val item: String = "",
        val country: String = "",
        val needed: Int = 0,
        val have: Int = 0,
        val timestamp: Long = 0L
    )

    @Insert
    suspend fun insertAdditionLog(log: List<AdditionLogEntity>)

    @Query("""
        SELECT i.name AS item,
               c.name AS country,
               al.needed AS needed,
               al.have AS have,
               al.timestamp AS timestamp
        FROM addition_log al
        JOIN items i ON i.id = al.itemId
        JOIN countries c ON c.id = al.countryId
        ORDER BY timestamp DESC
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
        val batchId: Long = 0L
    )

    @Query("""
    SELECT q. id AS id, 
            i.name AS item,
           c.name AS country,
           q.fromHave AS fromHave,
           q.toHave AS toHave,
           q.delta AS delta,
           q.timestamp AS timestamp,
           q.batchId AS batchId
    FROM quantity_log q
    JOIN items i ON i.id = q.itemId
    JOIN countries c ON c.id = q.countryId
    ORDER BY q.timestamp DESC
    LIMIT :limit
""")
    suspend fun getRecentQuantityLogs(limit: Int = 200): List<QuantityRow>

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
        @Query("SELECT * FROM save_session_line WHERE sessionId = :sessionId ORDER BY timestamp DESC")
        suspend fun getLines(sessionId: Long): List<SaveSessionLineEntity>
        @Query("SELECT * FROM save_session_line WHERE sessionId = :sessionId AND country = :country ORDER BY timestamp DESC")
        suspend fun getLinesByCountry(sessionId: Long, country: String): List<SaveSessionLineEntity>
    }

    @Query("DELETE FROM quantity_log")
    suspend fun deleteAllQuantityLogs()

    // 나라 이름으로 해당 나라 로그만 불러오기 (QuantityRow는 네가 이미 쓰는 DTO)
    @Query("""
SELECT q.id AS id,
       i.name AS item,
       c.name AS country,
       q.fromHave AS fromHave,
       q.toHave AS toHave,
       q.delta AS delta,
       q.timestamp AS timestamp,
       q.batchId AS batchId
FROM quantity_log q
JOIN items i ON i.id = q.itemId
JOIN countries c ON c.id = q.countryId
WHERE c.name = :country
ORDER BY q.timestamp DESC
LIMIT :limit
""")
    suspend fun getQuantityLogsByCountry(country: String, limit: Int = 500): List<QuantityRow>

    // 해당 나라의 로그만 삭제 (초기화)
    @Query("""
DELETE FROM quantity_log 
WHERE countryId IN (SELECT id FROM countries WHERE name = :country)
""")
    suspend fun deleteQuantityLogsByCountry(country: String)


}

