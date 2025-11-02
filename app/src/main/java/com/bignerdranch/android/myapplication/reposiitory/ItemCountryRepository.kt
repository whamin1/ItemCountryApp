package com.bignerdranch.android.myapplication.repository

import android.util.Log
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.entity.AdditionLogEntity
import com.bignerdranch.android.myapplication.data.local.entity.CountryEntity
import com.bignerdranch.android.myapplication.data.local.entity.ItemCountryCrossRef
import com.bignerdranch.android.myapplication.data.local.entity.ItemEntity
import com.bignerdranch.android.myapplication.data.local.entity.QuantityLogEntity
import com.bignerdranch.android.myapplication.data.local.entity.SheetEntity
import com.bignerdranch.android.myapplication.data.local.entity.SheetLineEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ItemCountryRepository(
    private val dao: ItemCountryDao,
    private val sheetDao: ItemCountryDao.SheetDao
) {
    /** Map<아이템명, 나라리스트> 한 방에 추가 */
    suspend fun addItems(items: Map<String, List<String>>) {
        val itemEntities = items.keys.map { ItemEntity(name = it) }
        val countryNameSet = items.values.flatten().toSet()
        val countryEntities = countryNameSet.map { CountryEntity(name = it) }

        dao.upsertItems(itemEntities)
        dao.upsertCountries(countryEntities)

        val nameToItemId = mutableMapOf<String, Long>()
        val nameToCountryId = mutableMapOf<String, Long>()
        withContext(Dispatchers.IO) {
            for (name in items.keys) {
                dao.findItemByName(name)?.let { nameToItemId[name] = it.id }
            }

            for (name in countryNameSet) {
                dao.findCountryByName(name)?.let { nameToCountryId[name] = it.id }
            }
        }


        val refs = buildList {
            items.forEach { (itemName, countryNames) ->
                val itemId = nameToItemId[itemName] ?: return@forEach
                countryNames.forEach { cn ->
                    val countryId = nameToCountryId[cn] ?: return@forEach
                    add(ItemCountryCrossRef(itemId, countryId))
                }
            }
        }
        dao.insertCrossRefs(refs) // 중복은 IGNORE
        val logs: List<AdditionLogEntity> = buildList {
            items.forEach { (itemName, countries) ->
                val itemId = dao.getItemIdByName(itemName) ?: return@forEach
                countries.forEach { cn ->
                    val countryId = dao.getCountryIdByName(cn) ?: return@forEach
                    add(
                        AdditionLogEntity(
                            itemId = itemId,
                            countryId = countryId,
                            needed = 0,
                            have = 0,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        if (logs.isNotEmpty()) dao.insertAdditionLog(logs)

    }

    suspend fun addCountries(itemName: String, vararg countryNames: String) {
        addItems(mapOf(itemName to countryNames.toList()))
    }

    suspend fun getCountriesOfItem(itemName: String): List<String> =
        dao.getItemWithCountriesByName(itemName)?.countries?.map { it.name }.orEmpty()

    fun observeAll(): Flow<Map<String, List<String>>> =
        dao.observeAllItemsWithCountries().map { list ->
            list.sortedBy { it.item.name }
                .associate { it.item.name to it.countries.map { c -> c.name } }
        }

    suspend fun deleteLinkByNames(item: String, country: String) {
        val itemId = dao.getItemIdByName(item) ?: return
        val countryId = dao.getCountryIdByName(country) ?: return
        dao.deleteLink(itemId, countryId)
    }

    suspend fun deleteItemByName(item: String) {
        val itemId = dao.getItemIdByName(item) ?: return
        dao.deleteLinksByItem(itemId)
        dao.deleteItemById(itemId)
    }

    suspend fun deleteCountryByName(country: String) {
        val countryId = dao.getCountryIdByName(country) ?: return
        dao.deleteLinksByCountry(countryId)
        dao.deleteCountryById(countryId)
    }

    suspend fun updateQuantity(item: String, country: String, needed: Int, have: Int, batchId: Long? = null) {
        val itemId = dao.getItemIdByName(item) ?: return
        val countryId = dao.getCountryIdByName(country) ?: return
        //기존 have 조회
        val before = dao.getHave(itemId, countryId) ?: 0
        // 업데이트
        dao.updateQuantity(itemId, countryId, needed, have)

        // 중거분만 로그
        val delta = have - before
        if (delta > 0) {
            dao.insertQuantityLog(
                QuantityLogEntity(
                    itemId = itemId,
                    countryId = countryId,
                    fromHave = before,
                    toHave = have,
                    delta = delta,
                    timestamp = System.currentTimeMillis(),
                    batchId = batchId ?: 0L
                )
            )
        }
    }
    data class CountryQty(
        val name: String,
        val needed: Int,
        val have: Int
    )

    fun observeAllWithQuantitiesItemMap(): Flow<Map<String, List<CountryQty>>> =
        dao.observeItemCountryRows().map { rows ->
            rows.groupBy({ it.item }, { CountryQty(it.country, it.needed, it.have) })
                .toSortedMap() // 보기 좋게 정렬 (선택)
        }

    fun observeAllWithQuantitiesCountryMap(): Flow<Map<String, List<CountryQty>>> =
        dao.observeItemCountryRows().map { rows ->
            rows.groupBy({ it.country }, { CountryQty(it.item, it.needed, it.have) })
                .toSortedMap()
        }
    suspend fun getItemIdByName(name: String) = dao.getItemIdByName(name)
    suspend fun getCountryIdByName(name: String) = dao.getCountryIdByName(name)
    suspend fun getQuantityLogs(itemId: Long, countryId: Long, limit: Int = 50) =
        dao.getQuantityLogs(itemId, countryId, limit)

    suspend fun getRecentAdditions(limit: Int = 200): List<ItemCountryDao.AdditionRow> =
        dao.getRecentAdditions(limit)

    // ⓐ 나라 추가
    suspend fun addCountry(name: String) {
        dao.insertCountry(CountryEntity(name = name))
    }

    // ⓑ 나라 목록(이름) Flow - 탭 만들 때 사용
    fun getAllCountryNames(): kotlinx.coroutines.flow.Flow<List<String>> =
        dao.getAllCountryNames()

    suspend fun getHaveNow(itemId: Long, countryId: Long): Int {
        return dao.getHave(itemId, countryId) ?: 0
    }
    suspend fun logPlusEvent(
        itemId: Long,
        countryId: Long?,   // 나라별이면 넣고, 전체면 null 가능
        fromHave: Int,
        toHave: Int,
        timestamp: Long = System.currentTimeMillis()
    ) {
       val log = QuantityLogEntity(
           itemId = itemId,
           countryId = countryId,
           fromHave = fromHave,
           toHave = toHave,
           delta = toHave - fromHave, // 보통 1
           timestamp = timestamp
       )
        dao.insertQuantityLog(log)
    }

    suspend fun updateWeightAndPrice(item: String, country: String, weight: Float, price: Float) {
        dao.updateWeightAndPrice(item, country, weight, price)
    }

    // 시트 만들기 (CSV 파싱 후 라인 리스트로 저장하는 용도)
    suspend fun createSheet(title: String, lines: List<SheetLineEntity>): Long {
        val sheetId = sheetDao.insertSheet(SheetEntity(title = title))
        if (lines.isNotEmpty()) {
            val withId = lines.map { it.copy(sheetId = sheetId) }
            sheetDao.insertSheetLines(withId)
            val cnt = sheetDao.countLinesBySheetId(sheetId) // 혹은 countOrphans()로도 확인 가능
            val orphans = sheetDao.countOrphans()
            Log.d("SheetsDebug", "inserted=${withId.size}, countForSheet=$cnt, orphans=$orphans sheetId=$sheetId")
        }
        return sheetId
    }

    suspend fun toggleSheetHidden(sheetId: Long, hidden: Boolean) {
        sheetDao.setSheetHidden(sheetId, hidden)
    }

    suspend fun deleteSheet(sheetId: Long) {
        sheetDao.deleteSheetLinesBySheet(sheetId)
        sheetDao.deleteSheetById(sheetId)
    }

    fun observeSheets() = sheetDao.observeAllSheets()

    fun observeVisibleSheets(): Flow<List<SheetEntity>> = sheetDao.observeVisibleSheets()

    // ✅ 핵심: 시트 “적용”
    suspend fun applySheet(sheetId: Long) {
        val sheet = dao.getSheetById(sheetId) ?: return
        val lines = sheetDao.getSheetLines(sheetId)
        if (lines.isEmpty()) return

        if (sheet.hidden) {
            // ✅ 비활성화된 시트면 기존 아이템-나라 링크 삭제
            for (ln in lines) {
                val itemId = dao.getItemIdByName(ln.item)
                val countryId = dao.getCountryIdByName(ln.country)
                if (itemId != null && countryId != null) {
                    dao.deleteLinkByIds(itemId, countryId)
                }
            }
            Log.d("SheetsDebug", "시트 ${sheet.title} 비활성화됨 → 아이템 삭제 완료")
            return
        }

        // ✅ 활성화된 경우 기존 적용 로직 수행
        val itemToCountries = lines.groupBy({ it.item }, { it.country })
        if (itemToCountries.isNotEmpty()) {
            addItems(itemToCountries)
        }

        val batchId = System.currentTimeMillis()
        for (ln in lines) {
            updateQuantity(ln.item, ln.country, ln.needed, ln.have, batchId)
        }
    }

    fun observeSheetsWithLines(): Flow<List<ItemCountryDao.SheetWithLines>> = sheetDao.observeSheetsWithLines()

    fun observeSheetLines(sheetId: Long) = dao.observeSheetLines(sheetId)

    suspend fun updateSheetLine(line: SheetLineEntity) = dao.updateSheetLine(line)

    suspend fun deleteSheetLine(line: SheetLineEntity) = dao.deleteSheetLine(line)

    // (선택) 추가 버튼 쓸 거면
    suspend fun insertSheetLine(sheetId: Long, line: SheetLineEntity) =
        dao.insertSheetLine(line.copy(sheetId = sheetId))

}
