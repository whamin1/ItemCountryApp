package com.bignerdranch.android.myapplication.ui.itemcountry

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import com.bignerdranch.android.myapplication.data.local.entity.QuantityLogEntity
import com.bignerdranch.android.myapplication.data.local.entity.SaveSessionLineEntity
import com.bignerdranch.android.myapplication.data.local.entity.SheetEntity
import com.bignerdranch.android.myapplication.data.local.entity.SheetLineEntity
import com.bignerdranch.android.myapplication.repository.ItemCountryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ItemCountryViewModel(app: Application) : AndroidViewModel(app) {

    val repository: ItemCountryRepository by lazy {
        val db = AppDatabase.get(app)
        ItemCountryRepository(db.itemCountryDao(), db.sheetDao(), db.saveArchiveDao())
    }
    private var repo: ItemCountryRepository = repository

    private val _sessionId = MutableStateFlow<Long?>(null)
    private val _onlySaved = MutableStateFlow(false)

    val sessionLines: StateFlow<List<SaveSessionLineEntity>> =
        combine(_sessionId.filterNotNull(), _onlySaved) { id, only ->
            id to only
        }.flatMapLatest { (id, only) ->
            repo.observeSessionLines(id, true) // ✅ 여기!
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

    fun setSessionId(id: Long) { _sessionId.value = id }
    fun setOnlySaved(only: Boolean) { _onlySaved.value = only }

    // 1) 아이템 -> 나라
    val uiStateItem: StateFlow<Map<String, List<String>>>

    // 2) 나라 -> 아이템
    val uiStateCountry: StateFlow<Map<String, List<String>>>

    val uiStateItemQty: StateFlow<Map<String, List<ItemCountryRepository.CountryQty>>>
    val uiStateCountryQty: StateFlow<Map<String, List<ItemCountryRepository.CountryQty>>>


    init {
        val db = AppDatabase.get(app)
        repo = ItemCountryRepository(db.itemCountryDao(), db.sheetDao(), db.saveArchiveDao())

        uiStateItem = repo.observeAll().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap()
        )

        uiStateCountry = repo.observeAll()
            .map { itemMap ->
                val map = mutableMapOf<String, MutableList<String>>()
                itemMap.forEach { (item, countries) ->
                    countries.forEach { country ->
                        map.getOrPut(country) { mutableListOf() }.add(item)
                    }
                }
                map
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyMap()
            )
        uiStateItemQty = repo.observeAllWithQuantitiesItemMap().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap()
        )
        uiStateCountryQty = repo.observeAllWithQuantitiesCountryMap().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap()
        )
    }

    suspend fun seedNowIfEmpty(defaults: Map<String, List<String>>) {
        if (uiStateItem.value.isEmpty()) {
            repo.addItems(defaults) // suspend 호출, 여기서 완료까지 대기
        }
    }

    // ⓐ 카탈로그 상단 탭용: 모든 나라 이름 Flow
    val allCountryNames: Flow<List<String>> =
        repository.getAllCountryNames()

    // ⓑ 나라 추가 (FAB에서 호출)
    fun addCountry(name: String) {
        viewModelScope.launch {
            repository.addCountry(name)
        }
    }

    suspend fun addItemsSuspend(items: Map<String, List<String>>) {
        repo.addItems(items)
    }

    fun addCountries(item: String, vararg countries: String) {
        viewModelScope.launch { repo.addCountries(item, *countries) }
    }

    suspend fun getCountriesOfItem(item: String): List<String> =
        repo.getCountriesOfItem(item)

    fun deleteLink(item: String, country: String) = viewModelScope.launch {
        repo.deleteLinkByNames(item, country)
    }

    fun deleteItem(item: String) = viewModelScope.launch {
        repo.deleteItemByName(item)
    }

    fun deleteCountry(country: String) = viewModelScope.launch {
        repo.deleteCountryByName(country)
    }

    fun updateQuantity(item: String, country: String, needed: Int, have: Int, batchId: Long? = null) = viewModelScope.launch {
        repo.updateQuantity(item, country, needed, have, batchId)
    }

    fun getQuantityLogs(item: String, country: String, limit: Int = 50, onResult: (List<QuantityLogEntity>) -> Unit) {
        viewModelScope.launch {
            val itemId = repo.getItemIdByName(item) ?: return@launch
            val countryId = repo.getCountryIdByName(country) ?: return@launch
            val logs = repo.getQuantityLogs(itemId, countryId, limit)
            onResult(logs)
        }
    }
    fun loadRecentAdditions(limit: Int = 200, onResult: (List<ItemCountryDao.AdditionRow>) -> Unit) {
        viewModelScope.launch {
            val rows = repo.getRecentAdditions(limit)
            onResult(rows)
        }
    }

    fun updateWeightAndPrice(item: String, country: String, weight: Float, price: Float) {
        viewModelScope.launch {
            repo.updateWeightAndPrice(item, country, weight, price)
        }
    }

    suspend fun onPlusClicked(itemId: Long, countryId: Long) {
        val from = repo.getHaveNow(itemId, countryId)
        val to = from + 1

        withContext(Dispatchers.IO) {
            repo.logPlusEvent(
                itemId = itemId,
                countryId = countryId,
                fromHave = from,
                toHave = to
            )
        }
    }

    val sheets: Flow<List<SheetEntity>> = repo.observeSheets()

    fun createSheet(title: String, lines: List<SheetLineEntity>) = viewModelScope.launch {
        repo.createSheet(title, lines)
    }

    fun applySheet(sheetId: Long) = viewModelScope.launch {
        repo.applySheet(sheetId)
    }

    fun toggleSheetHidden(sheetId: Long, hidden: Boolean) = viewModelScope.launch {
        repo.toggleSheetHidden(sheetId, hidden)
    }

    fun deleteSheet(sheetId: Long) = viewModelScope.launch {
        repo.deleteSheet(sheetId)
    }

    val sheetWithLines: Flow<List<ItemCountryDao.SheetWithLines>> = repo.observeSheetsWithLines()

    fun observeSheetLines(sheetId: Long) = repo.observeSheetLines(sheetId)

    fun updateSheetLine(line: SheetLineEntity) = viewModelScope.launch {
        repo.updateSheetLine(line)
    }

    fun deleteSheetLine(line: SheetLineEntity) = viewModelScope.launch {
        repo.deleteSheetLine(line)
    }

    // (선택) 추가
    fun insertSheetLine(sheetId: Long, line: SheetLineEntity) = viewModelScope.launch {
        repo.insertSheetLine(sheetId, line)
    }
}