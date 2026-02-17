package com.bignerdranch.android.myapplication.repository

import android.content.Context
import android.util.Log
import androidx.room.Transaction
import androidx.room.withTransaction
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import com.bignerdranch.android.myapplication.data.local.entity.AdditionLogEntity
import com.bignerdranch.android.myapplication.data.local.entity.CountryEntity
import com.bignerdranch.android.myapplication.data.local.entity.ItemCountryCrossRef
import com.bignerdranch.android.myapplication.data.local.entity.ItemEntity
import com.bignerdranch.android.myapplication.data.local.entity.ItemSearchRow
import com.bignerdranch.android.myapplication.data.local.entity.PredictionAckEntity
import com.bignerdranch.android.myapplication.data.local.entity.QuantityLogEntity
import com.bignerdranch.android.myapplication.data.local.entity.SheetEntity
import com.bignerdranch.android.myapplication.data.local.entity.SheetLineEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import kotlin.math.abs

class ItemCountryRepository(
    private val appContext: Context,
    private val db: AppDatabase,
    private val dao: ItemCountryDao,
    private val sheetDao: ItemCountryDao.SheetDao,
    private val saveArchiveDao: ItemCountryDao.SaveArchiveDao
) {

    private val prefs = appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private fun setActiveSheetId(id: Long) {
        prefs.edit().putLong("active_sheet_id", id).apply()
    }
    private fun getActiveSheetId(): Long {
        return prefs.getLong("active_sheet_id", -1L)
    }

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
        dao.updateQuantityClamped(itemId, countryId, needed, have)

        // 중거분만 로그
        val delta = have - before
        if (delta > 0) {
            dao.insertQuantityLogSnap(
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
        val have: Int,
        val price: Int = 0
    )

    fun observeAllWithQuantitiesItemMap(): Flow<Map<String, List<CountryQty>>> =
        kotlinx.coroutines.flow.combine(
            dao.observeItemCountryRows(),
            dao.observeLatestItemCountryPrices()
        ) { rows, prices ->

            val priceMap = prices.associate { (it.item to it.country) to it.price }

            rows.groupBy({ it.item }) { r ->
                CountryQty(
                    name = r.country,
                    needed = r.needed,
                    have = r.have,
                    price = priceMap[r.item to r.country] ?: 0
                )
            }.toSortedMap()
        }

    fun observeAllWithQuantitiesCountryMap(): Flow<Map<String, List<CountryQty>>> =
        kotlinx.coroutines.flow.combine(
            dao.observeItemCountryRows(),
            dao.observeLatestItemCountryPrices()
        ) { rows, prices ->

            val priceMap = prices.associate { (it.item to it.country) to it.price }

            rows.groupBy({ it.country }) { r ->
                CountryQty(
                    name = r.item,
                    needed = r.needed,
                    have = r.have,
                    price = priceMap[r.item to r.country] ?: 0
                )
            }.toSortedMap()
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
    fun getAllCountryNames(): Flow<List<String>> =
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
        dao.insertQuantityLogSnap(log)
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
        setActiveSheetId(sheetId)
        dao.backfillItemCountryWeightPriceFromSheets()
    }

    fun observeSheetsWithLines(): Flow<List<ItemCountryDao.SheetWithLines>> = sheetDao.observeSheetsWithLines()

    fun observeSheetLines(sheetId: Long) = dao.observeSheetLines(sheetId)

    suspend fun getMaxSortOrder(sheetId: Long) = dao.getMaxSortOrder(sheetId)

    suspend fun updateSheetLine(line: SheetLineEntity) = dao.updateSheetLine(line)

    suspend fun deleteSheetLine(line: SheetLineEntity) = dao.deleteSheetLine(line)

    // (선택) 추가 버튼 쓸 거면
    suspend fun insertSheetLine(sheetId: Long, line: SheetLineEntity) =
        dao.insertSheetLine(line.copy(sheetId = sheetId))

    fun observeSessionLines(sessionId: Long, onlySaved: Boolean) =
        saveArchiveDao.observeSessionLines(sessionId, if (onlySaved) 1 else 0)

    // 필요하면 한 번만 전체 가져오는 함수(초기 디버그용)
    suspend fun getAllLines(sessionId: Long) = saveArchiveDao.getAllLines(sessionId)

    fun observeItemsWithOff() = dao.observeItemsWithOff()

    suspend fun addOff(itemId: Long, countryId: Long, delta: Int) {
        dao.addOffClick(itemId, countryId, delta)
    }

    suspend fun getOffHave(itemId: Long, country: Long) = dao.getOffHave(itemId, country)

    suspend fun clearOff(itemId: Long, countryId: Long) = dao.clearOffFor(itemId, countryId)

    suspend fun removeOffClick(itemId: Long, countryId: Long) {
        dao.removeOffClick(itemId, countryId)
    }

    suspend fun addOffAndTouch(itemId: Long, countryId: Long, delta: Int) {
        if (delta <= 0) return
        val now = System.currentTimeMillis()

        // 1) OFF 모드 클릭 로그
        dao.addOffClick(itemId, countryId, delta)

        // 2) 최근 클릭 시간 업데이트
        dao.updateLastClickedAt(itemId, countryId,now)
    }

    fun observeRecentTouched(): Flow<List<ItemCountryDao.RecentRow>> =
        dao.getRecentTouched()

    suspend fun toggleCountryHidden(
        sheetId: Long,
        country: String,
        newHidden: Boolean
    ) {
        dao.toggleCountryHidden(sheetId, country, newHidden)
        dao.setCountryHidden(country, newHidden)
    }
    suspend fun renameSheet(sheetId: Long, title: String) {
        sheetDao.updateSheetTitle(sheetId, title)
    }

    suspend fun renameCountryInSheet(sheetId: Long, oldCountry: String, newCountry: String) {
        sheetDao.renameCountryInSheet(sheetId, oldCountry, newCountry)
    }

    suspend fun deleteSheetLinesByCountry(sheetId: Long, country: String) {
        sheetDao.deleteSheetLinesByCountry(sheetId, country)
    }

    fun searchSheetItems(q: String): Flow<List<ItemSearchRow>> {
        return dao.searchItems(q)
    }
    suspend fun findOneItemRowForJump(item: String, country: String): ItemSearchRow? {
        return dao.findOneSearchRowByItem(item, country)
    }

    suspend fun deleteSheetLineAndUnlink(line: SheetLineEntity) {
        db.withTransaction {
            // 1) 시트 라인 삭제
            dao.deleteSheetLine(line)

            // 2) 홈 링크 삭제(A)
            val itemId = dao.getItemIdByName(line.item) ?: return@withTransaction
            val countryId = dao.getCountryIdByName(line.country) ?: return@withTransaction
            dao.deleteLink(itemId, countryId)
        }
    }

    suspend fun deleteSheetLineAndUnlinkIfOrphan(line: SheetLineEntity) {
        dao.deleteSheetLineAndUnlinkIfOrphan(line)
    }

    fun observeDeletedLines(sheetId: Long) = dao.observeDeletedLines(sheetId)

    suspend fun sorfDeleteLine(lineId: Long) {
        dao.softDelete(lineId, System.currentTimeMillis())
    }

    suspend fun restoreLines(sheetId: Long) = dao.restoreLine(sheetId)

    suspend fun hardDeleteSheetLine(sheetId: Long) =
        dao.hardDeleteLine(sheetId)


    suspend fun observeActiveLines(sheetId: Long) = dao.observeActiveLines(sheetId)


    suspend fun updateSheetLineAndApplyHome(old: SheetLineEntity, new: SheetLineEntity) {
        db.withTransaction {
            // 1) 시트 라인 업데이트
            dao.updateSheetLine(new)

            // 2) new item/country upsert + id 확보
            dao.upsertItems(listOf(ItemEntity(name = new.item)))
            dao.upsertCountries(listOf(CountryEntity(name = new.country)))

            val newItemId = dao.getItemIdByName(new.item) ?: return@withTransaction
            val newCountryId = dao.getCountryIdByName(new.country) ?: return@withTransaction

            // 3) 홈 링크 보장
            dao.insertCrossRefs(listOf(ItemCountryCrossRef(newItemId, newCountryId)))

            // 4) 홈 수량/무게/가격 즉시 반영
            dao.updateQuantityClamped(newItemId, newCountryId, new.needed, new.have)
            dao.updateWeightAndPrice(new.item, new.country, new.weight, new.price.toFloat())

            // ---------------------------
            // ✅ 여기부터 "이사(마이그레이션)"
            // ---------------------------
            val keyChanged = old.item != new.item || old.country != new.country
            if (keyChanged) {
                val oldItemId = dao.getItemIdByName(old.item)
                val oldCountryId = dao.getCountryIdByName(old.country)

                if (oldItemId != null && oldCountryId != null) {
                    // 5) 로그/ACK 이사
                    dao.migrateQuantityLogs(oldItemId, oldCountryId, newItemId, newCountryId)
                    dao.migrateAdditionLogs(oldItemId, oldCountryId, newItemId, newCountryId)

                    val oldAck = dao.getPredictionAck(oldItemId)
                    val newAck = dao.getPredictionAck(newItemId)

                    if (oldAck != null || newAck != null) {
                        val mergedAckAt = maxOf(oldAck?.ackAt ?: 0L, newAck?.ackAt ?: 0L)
                        dao.upsertPredictionAck(PredictionAckEntity(itemId = newItemId, ackAt = mergedAckAt))
                        if (oldAck != null) dao.deletePredictionAck(oldItemId)
                    }

                    val oldLast = dao.getLastClickedAt(oldItemId, oldCountryId)
                    if (oldLast != null) {
                        // new 쪽 lastClickedAt이 더 최신이면 그걸 유지하는 게 안전
                        val newLast = dao.getLastClickedAt(newItemId, newCountryId)
                        val keep = maxOf(oldLast, newLast ?: 0L)
                        dao.updateLastClickedAt(newItemId, newCountryId, keep)
                    }

                    // 7) old(item,country)가 시트라인에 더 이상 없으면 링크 제거
                    val left = dao.countSheetLinesByItemCountry(old.item, old.country)
                    if (left == 0) {
                        dao.deleteLink(oldItemId, oldCountryId)
                    }
                }
            }
        }
    }


    //예측 함수
////////////////////////////////////////////////////
    data class PredItem(
        val itemId: Long,
        val item: String,
        val predictedAt: Long,
        val label: String,
        val score: Double,
        val avgIntervalMs: Long,
        val topCountries: List<String>
    )

    data class ItemAvgGap(
        val itemId: Long,
        val avgGapMs: Long,
        val sampleCount: Int
    )

    suspend fun getAllItemsNamesOnce(): List<String> = dao.getAllItemsNames()

    suspend fun getAllCountryNamesOnce(): List<String> =
        dao.getAllCountryNames().first()

    suspend fun getItemNameById(itemId: Long): String? {
        return dao.getItemNameById(itemId)
    }
    suspend fun fixWasteWeightAtInPeriod(from: Long, to: Long): Int {
        return dao.fixWasteWeightAtInPeriod(from, to)
    }

    private val _selectedItemNames = MutableStateFlow<Set<String>>(emptySet())
    val selectedItemNames = _selectedItemNames.asStateFlow()

    suspend fun toggleEnabled(item: String, country: String, enabled: Boolean) {
        return dao.setItemCountryEnabled(item, country, enabled)
    }

    suspend fun saveLineOrder(lines: List<SheetLineEntity>) {
        val orders = lines.mapIndexed { idx, ln -> ItemCountryDao.IdOrder(ln.id, idx) }
        return dao.updateLineOrders(orders)
    }

    fun observeActiveLinesUi(sheetId: Long) =
        dao.observeActiveLinesUi(sheetId)

    suspend fun setEnabled(item: String, country: String, enabled: Boolean) {
        dao.setItemCountryEnabled(item, country, enabled)
    }

    suspend fun updateLineOrders(list: List<ItemCountryDao.IdOrder>) {
        dao.updateLineOrders(list)
    }

    @Transaction
    suspend fun restoreLineToBottom(lineId: Long) {
        val sheetId = dao.getSheetIdByLineId(lineId) // 없으면 추가 필요
        val newOrder = dao.getMaxSortOrder(sheetId) + 1
        dao.restoreLineToOrder(lineId, newOrder)
    }

    suspend fun cleanupOrphanLinksOnce(): Int {
        val orphans = dao.findOrphanLinksBySheet()

        // ✅ 너무 길면 50개만
        val preview = orphans.take(50).joinToString("\n") {
            "${it.item} / ${it.country} (itemId=${it.itemId}, countryId=${it.countryId})"
        }

        Log.d("CLEANUP", "Orphan links = ${orphans.size}\n$preview")

        val deleted = dao.deleteOrphanLinksBySheet()
        Log.d("CLEANUP", "Deleted orphan links = $deleted")

        return deleted
    }


    private val KST = ZoneId.of("Asia/Seoul")

    private fun toKstDate(ts: Long): LocalDate =
        Instant.ofEpochMilli(ts).atZone(KST).toLocalDate()

    private fun countGlobalMissingDaysBetween(
        d1: Long,
        d2: Long,
        activeDays: Set<Long>
    ): Long {
        var missing = 0L
        var day = d1 + 1
        while (day < d2) {
            if (!activeDays.contains(day)) missing++
            day++
        }
        return missing
    }

    //중앙값 함수
    private fun median(list: List<Long>): Long {
        if (list.isEmpty()) return 0L
        val s = list.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }


    suspend fun buildPredictions(
        limit: Int = 20000,
        minIntervalMs: Long = 10 * 60_000,   // 너무 촘촘한 간격 컷(10분)
        snapBusiness: Boolean = false,       // ⚠️ 기본은 false 추천(왜곡/중복 줄임)
        allowOverdue: Boolean = false,       // 이미 지난 예측은 제외
        take: Int = 100000,                     // 전체 결과 상한(UI 안전)
        perItemMax: Int = 10,                // ✅ 아이템당 최대 10개
        horizonDays: Int = 365               // ✅ 상한 없이 가려면 크게(예: 365일)
    ): List<PredItem> {



        val logs = dao.getRecentPlusLogLiteIds(limit)
        val now = System.currentTimeMillis()

        val itemNameMap = dao.getAllItemsLite().associate { it.id to it.name }
        val groups = logs.groupBy { it.itemId }

        //아이템 이름
        suspend fun resolveItemName(itemId: Long): String? {
            return itemNameMap[itemId] ?: dao.getItemNameById(itemId)
        }

        val results = mutableListOf<PredItem>()

        val horizonMs = horizonDays * 24L * 60L * 60L * 1000L
        val end = now + horizonMs

        val DAY_MS = 86_400_000L
        val KST_OFFSET = 9 * 60 * 60 * 1000L
        val MIN_MS = 60_000L
        fun dayIndexKst(ts: Long) = (ts + KST_OFFSET) / DAY_MS
        fun minuteOfDayKst(ts: Long) = ((ts + KST_OFFSET) % DAY_MS) / MIN_MS  // 0~1439

        val lastMinuteByDay: Map<Long, Long> =
            logs.groupBy { dayIndexKst(it.timestamp) }
                .mapValues { (_, dayLogs) -> dayLogs.maxOf { minuteOfDayKst(it.timestamp) } }

        val END = 16 * 60L
        val EARLY = 15 * 60L



        fun earlyLeaveTailMs(day: Long): Long {
            val lastMin = lastMinuteByDay[day] ?: return 0L // 그날 로그가 없으면 여기선 0 (빈날 로직에서 처리)
            return if (lastMin < EARLY) (END - lastMin) * MIN_MS else 0L
        }

        fun sumEarlyLeaveTailBetween(prevTs: Long, currTs: Long, activeDays: Set<Long>): Long {
            val prevDay = dayIndexKst(prevTs)
            val currDay = dayIndexKst(currTs)
            if (prevDay >= currDay) return 0L

            var sum = 0L

            // prevDay tail
            if (activeDays.contains(prevDay)) sum += earlyLeaveTailMs(prevDay)

            // middle days tail (로그 있는 날만)
            var d = prevDay + 1
            while (d < currDay) {
                if (activeDays.contains(d)) {
                    sum += earlyLeaveTailMs(d)
                }
                d++
            }

            // currDay tail은 빼지 않음
            return sum
        }


        fun scoreFor(predicted: Long): Double {
            // 가까울수록 점수 높게 (2시간 스케일)
            return 1.0 / (1.0 + (predicted - now).toDouble() / (2 * 60 * 60 * 1000))
        }


        // 디버그 카운터

        var cutTs = 0
        var cutAllOverdue = 0
        var keptItems = 0


////////////////////////////////////////////////////////
        val activeDayIndexes: Set<Long> = logs
            .map { it.timestamp / DAY_MS }
            .toSet()


        val HOUR_MS = 3_600_000L

// ✅ 전체 아이템용: 루프 밖에서 1번만
        val workGapsByItem = mutableMapOf<Long, MutableList<Long>>()

        for ((itemId, itemLogs) in groups) {

            if (itemLogs.size < 2) continue
            val sortedLogs = itemLogs.sortedBy { it.timestamp }

            for (i in 1 until sortedLogs.size) {

                val prevTs = sortedLogs[i - 1].timestamp
                val currTs = sortedLogs[i].timestamp
                val rawGap = currTs - prevTs

                val prevDay = dayIndexKst(prevTs)
                val currDay = dayIndexKst(currTs)

                val missingDays = countGlobalMissingDaysBetween(prevDay, currDay, activeDayIndexes)
                val dayDiff = (currDay - prevDay).coerceAtLeast(0L)

                val baseNonWork = dayDiff * (17 * HOUR_MS)
                val missingExtra = missingDays * (7 * HOUR_MS)

                // ✅ 조기퇴근은 "두 로그 사이 합"을 쓰는 게 맞음
                val earlyExtra = sumEarlyLeaveTailBetween(prevTs, currTs, activeDayIndexes)

                val workGap = rawGap - baseNonWork - missingExtra - earlyExtra
                val workGapSafe = maxOf(0L, workGap)

                workGapsByItem.getOrPut(itemId) { mutableListOf() }.add(workGapSafe)

            }
        }

// ✅ 여기서부터 '아이템당 1번' 평균 출력
        workGapsByItem.forEach { (itemId, gaps) ->
            if (gaps.isEmpty()) return@forEach
            val avgMs = gaps.average().toLong()

        }

        fun formatPredictionTimeStep1(
            predictedAt: Long,
            now: Long = System.currentTimeMillis()
        ): String {

            val diffMs = predictedAt - now

            // 1) 이미 지난 경우
            if (diffMs <= 0) {
                return "지남"
            }

            val diffMin = diffMs / MIN_MS
            val diffHour = diffMs / HOUR_MS

            return when {
                diffMin < 1 -> "곧"
                diffMin < 60 -> "${diffMin}분 후"
                diffHour < 24 -> "${diffHour}시간 후"
                else -> "${diffHour / 24}일 후"
            }
        }

        val avgList = workGapsByItem.mapNotNull { (itemId, gaps) ->
            if (gaps.isEmpty()) return@mapNotNull null

            val avgMs = gaps.average().toLong()
            ItemAvgGap(
                itemId = itemId,
                avgGapMs = avgMs,
                sampleCount = gaps.size
            )
        }


//////////////////////////////////////////////////////////////////

        val avgGapByItem = avgList.associateBy { it.itemId }  // itemId -> ItemAvgGap

        //////////////////////////////////

        for ((itemId, rows) in groups) {
            val gaps = workGapsByItem[itemId] ?: continue
            val recent = gaps.takeLast(15)               // ✅ 최근 15개만(10~30 적당)
            val interval = median(recent)                // ✅ 평균 대신 중앙값
            if (interval <= 0L) continue
            val name = itemNameMap[itemId] ?: continue
            val ackAt = dao.getAckAt(itemId) ?: 0L

            val tsAll = rows.map { it.timestamp }.distinct().sorted()
            if (tsAll.size < 2) { cutTs++; continue }

            val base = tsAll.last()

            var anyFuture = false
            var count = 0
            var k = 1L

            while (count < perItemMax) {
                val predicted = addBusinessTime(base, interval * k)

                if (predicted > end) break
                if (predicted <= ackAt) { k++; continue }
                if (!allowOverdue && predicted <= now) { k++; continue }

                anyFuture = true

                results += PredItem(
                    itemId = itemId,
                    item = name,
                    predictedAt = predicted,
                    label = formatPredictionTimeStep1(predicted, now),
                    score = scoreFor(predicted),
                    avgIntervalMs = interval,
                    topCountries = emptyList()
                )

                count++
                k++
            }

            if (!anyFuture) cutAllOverdue++ else keptItems++
        }


        val onePerItem = results
            .filter { it.predictedAt > now }
            .groupBy { it.itemId }
            .mapNotNull { (_, list) -> list.minByOrNull { it.predictedAt } }

        return onePerItem
            .sortedBy { it.predictedAt }
            .take(take)

    }

    /////////

    private val LUNCH_START_MIN = 12 * 60 // 12:00
    private val LUNCH_END_MIN   = 13 * 60 // 13:00
    private val MIN_MS = 60_000L
    private val HOUR_MS = 3_600_000L
    private val DAY_MS = 86_400_000L

    private val WORK_START_MIN = 9 * 60    // 09:00
    private val WORK_END_MIN = 16 * 60     // 16:00
    private val WORK_DAY_MIN = WORK_END_MIN - WORK_START_MIN // 420분 = 7h
    private val WORK_DAY_MS = WORK_DAY_MIN * MIN_MS

    private val KST_OFFSET_MS = 9 * HOUR_MS

    private fun dayIndexKst(ts: Long): Long = (ts + KST_OFFSET_MS) / DAY_MS
    private fun minuteOfDayKst(ts: Long): Long = ((ts + KST_OFFSET_MS) % DAY_MS) / MIN_MS

    private fun kstTsOf(dayIndex: Long, minuteOfDay: Long): Long {
        // dayIndex, minuteOfDay는 KST 기준
        return dayIndex * DAY_MS + minuteOfDay * MIN_MS - KST_OFFSET_MS
    }

    private fun snapToWorkStartIfNeeded(ts: Long): Long {
        var day = dayIndexKst(ts)
        val min = minuteOfDayKst(ts)

        // 주말에 들어오면 -> 월요일로 스킵
        day = nextWorkDay(day)

        return when {
            min < WORK_START_MIN -> kstTsOf(day, WORK_START_MIN.toLong())
            min in LUNCH_START_MIN until LUNCH_END_MIN -> kstTsOf(day, LUNCH_END_MIN.toLong())
            min >= WORK_END_MIN  -> {
                val next = nextWorkDay(day + 1)
                kstTsOf(next, WORK_START_MIN.toLong())
            }
            else -> {
                // 평일 근무시간 안이면 그대로, (혹시 주말 근무시간 안이더라도 위에서 nextWorkDay로 걸러짐)
                ts
            }
        }
    }

    /**
     * startTs에서 시작해서 "근무시간만" workMs 만큼 흘렸을 때의 timestamp를 반환
     */
    private fun addBusinessTime(startTs: Long, workMs: Long): Long {
        if (workMs <= 0L) return startTs

        var ts = snapToWorkStartIfNeeded(startTs)
        var remaining = workMs

        while (remaining > 0L) {
            val day = dayIndexKst(ts)
            val min = minuteOfDayKst(ts)

            // ✅ 점심시간이면 13:00으로 점프
            if (min in LUNCH_START_MIN until LUNCH_END_MIN) {
                ts = kstTsOf(day, LUNCH_END_MIN.toLong())
                continue
            }

            // ✅ 현재 구간 끝(오전이면 12:00, 오후면 16:00)
            val segmentEndMin = when {
                min < LUNCH_START_MIN -> LUNCH_START_MIN
                else -> WORK_END_MIN
            }

            val leftThisSegmentMs = (segmentEndMin - min).coerceAtLeast(0) * MIN_MS

            if (remaining <= leftThisSegmentMs) {
                return ts + remaining
            }

            remaining -= leftThisSegmentMs

            // ✅ 구간 끝에 도달했으면 다음 위치로 이동
            ts = when (segmentEndMin) {
                LUNCH_START_MIN -> kstTsOf(day, LUNCH_END_MIN.toLong()) // 12:00 -> 13:00
                else -> { // 16:00 -> 다음 근무일 09:00
                    val next = nextWorkDay(day + 1)
                    kstTsOf(next, WORK_START_MIN.toLong())
                }
            }
        }

        return ts
    }



    private fun isWeekendDayIndex(dayIndex: Long): Boolean {
        val ts = kstTsOf(dayIndex, WORK_START_MIN.toLong()) // 그날 09:00(KST)
        val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul")).apply {
            timeInMillis = ts
        }
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
    }

    private fun nextWorkDay(dayIndex: Long): Long {
        var d = dayIndex
        while (isWeekendDayIndex(d)) d++
        return d
    }

    ///////////

    suspend fun buildItemReport(
        itemId: Long,
        from: Long,
        to: Long,
        countryId: Long?,
        logLimit: Int = 5000,
        showLogs: Int = 100,
        predCount: Int = 5
    ): ItemCountryDao.ItemReport {
        val now = System.currentTimeMillis()

        // 1) 이름 맵
        val itemNameMap = dao.getAllItemsLite().associate { it.id to it.name }
        val countryNameMap = dao.getAllCountriesLite().associate { it.id to it.name } // 없으면 만들어야 함
        val itemName = itemNameMap[itemId] ?: (dao.getItemNameById(itemId) ?: "Item")

        // 2) 해당 item의 +로그(ASC)
        val rows = dao.getPlusLogsByItemAscInPeriod(
            itemId = itemId,
            from = from,
            to = to,
            countryId = countryId,
            limit = logLimit
        )
        if (rows.size < 2) {
            return ItemCountryDao.ItemReport(
                itemId = itemId,
                itemName = itemName,
                summary = ItemCountryDao.ItemReport.Summary(null, null, 0, 0),
                logs = emptyList(),
                nextPredictions = emptyList()
            )
        }

        // 3) itemName 기준 weight 맵(countryName -> weightKg)
        val weightByCountry: Map<String, Double> =
            dao.getWeightsByItemName(itemName)
                .associate { it.country to it.weight.toDouble() }
                .filterValues { it > 0.0 }

        // 4) “근무일/조기퇴근/빈날” 계산은 예측과 동일하게
        //    리포트도 예측과 같은 전역 activeDays를 쓰는게 가장 일관성 좋음
        val globalLogs = dao.getRecentPlusLogLiteIds(20000) // 네가 이미 쓰는 함수
        val activeDayIndexes: Set<Long> = globalLogs
            .map { dayIndexKst(it.timestamp) }
            .toSet()

        val lastMinuteByDay: Map<Long, Long> =
            globalLogs.groupBy { dayIndexKst(it.timestamp) }
                .mapValues { (_, dayLogs) -> dayLogs.maxOf { minuteOfDayKst(it.timestamp) } }

        fun earlyLeaveTailMs(day: Long): Long {
            val lastMin = lastMinuteByDay[day] ?: return 0L
            return if (lastMin < (15 * 60L)) (16 * 60L - lastMin) * MIN_MS else 0L
        }

        fun sumEarlyLeaveTailBetween(prevTs: Long, currTs: Long): Long {
            val prevDay = dayIndexKst(prevTs)
            val currDay = dayIndexKst(currTs)
            if (prevDay >= currDay) return 0L

            var sum = 0L
            if (activeDayIndexes.contains(prevDay)) sum += earlyLeaveTailMs(prevDay)

            var d = prevDay + 1
            while (d < currDay) {
                if (activeDayIndexes.contains(d)) sum += earlyLeaveTailMs(d)
                d++
            }
            return sum
        }

        fun formatPredictionTimeStep1(
            predictedAt: Long,
            now: Long = System.currentTimeMillis()
        ): String {

            val diffMs = predictedAt - now

            // 1) 이미 지난 경우
            if (diffMs <= 0) {
                return "지남"
            }

            val diffMin = diffMs / MIN_MS
            val diffHour = diffMs / HOUR_MS

            return when {
                diffMin < 1 -> "곧"
                diffMin < 60 -> "${diffMin}분 후"
                diffHour < 24 -> "${diffHour}시간 후"
                else -> "${diffHour / 24}일 후"
            }
        }

        // 5) workGap 계산 (예측 코드 그대로)
        val workGaps = mutableListOf<Long>() // i=1..n-1 gap
        for (i in 1 until rows.size) {
            val prevTs = rows[i - 1].timestamp
            val currTs = rows[i].timestamp
            val rawGap = currTs - prevTs

            val prevDay = dayIndexKst(prevTs)
            val currDay = dayIndexKst(currTs)

            val missingDays = countGlobalMissingDaysBetween(prevDay, currDay, activeDayIndexes)
            val dayDiff = (currDay - prevDay).coerceAtLeast(0L)

            val baseNonWork = dayDiff * (17 * HOUR_MS)
            val missingExtra = missingDays * (7 * HOUR_MS)
            val earlyExtra = sumEarlyLeaveTailBetween(prevTs, currTs)

            val workGap = rawGap - baseNonWork - missingExtra - earlyExtra
            workGaps += maxOf(0L, workGap)
        }

        // 6) 표시용 로그 30개(최신부터)
        //    gap은 “currTs 기준”이니까 rows[i]와 workGaps[i-1]가 한 쌍
        val pairs = (1 until rows.size).map { i ->
            val curr = rows[i]
            val gap = workGaps[i - 1]
            curr to gap
        }.takeLast(showLogs).asReversed()

        val logUi = buildList<ItemCountryDao.ItemReport.LogRow> {
            // 0번째는 gap 계산 불가 -> null/0 처리
            val first = rows.first()
            val c0 = countryNameMap[first.countryId] ?: "?"
            val w0 = weightByCountry[c0]
            add(ItemCountryDao.ItemReport.LogRow(
                ts = first.timestamp,
                country = c0,
                weightKg = w0,
                speedKgPerHour = null,
                workGapMs = 0L // 또는 -1L 같은 sentinel
            ))

            // 나머지는 기존대로
            for (i in 1 until rows.size) {
                val curr = rows[i]
                val gap = workGaps[i - 1]
                val country = countryNameMap[curr.countryId] ?: "?"
                val w = weightByCountry[country]
                val speed = if (w != null && gap > 0L) w / (gap.toDouble() / HOUR_MS) else null

                add(ItemCountryDao.ItemReport.LogRow(
                    ts = curr.timestamp,
                    country = country,
                    weightKg = w,
                    speedKgPerHour = speed,
                    workGapMs = gap
                ))
            }
        }

// ✅ showLogs 적용은 마지막에
        val sliced = logUi.takeLast(showLogs).asReversed()

        // 7) 요약(kg/h, 평균 gap)
        val valid = logUi.filter { it.weightKg != null && it.workGapMs > 0L }
        val totalWeight = valid.sumOf { it.weightKg!! }
        val totalHours = valid.sumOf { it.workGapMs }.toDouble() / HOUR_MS.toDouble()

        val kgPerHour = if (valid.size >= 3 && totalHours > 0) totalWeight / totalHours else null
        val avgGap = if (logUi.isNotEmpty()) (logUi.sumOf { it.workGapMs } / logUi.size) else null

        // 8) 다음 예상 5개
        val recent15 = workGaps.takeLast(15)
        val interval = median(recent15)
        val base = rows.last().timestamp

        val preds = mutableListOf<ItemCountryDao.ItemReport.Pred>()
        var k = 1L
        while (preds.size < predCount) {
            val predicted = addBusinessTime(base, interval * k)
            preds += ItemCountryDao.ItemReport.Pred(
                predictedAt = predicted,
                label = formatPredictionTimeStep1(predicted, now)
            )
            k++
        }

        return ItemCountryDao.ItemReport(
            itemId = itemId,
            itemName = itemName,
            summary = ItemCountryDao.ItemReport.Summary(
                kgPerHour = kgPerHour,
                avgWorkGapMs = avgGap,
                sampleCount = valid.size,
                totalCount = logUi.size
            ),
            logs = logUi,
            nextPredictions = preds
        )
    }

    ///////

    suspend fun ackPrediction(itemId: Long) {
        dao.upsertAck(PredictionAckEntity(itemId, System.currentTimeMillis()))
    }

}