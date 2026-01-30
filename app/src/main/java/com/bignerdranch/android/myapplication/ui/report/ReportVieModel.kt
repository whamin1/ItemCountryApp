package com.bignerdranch.android.myapplication.ui.report

import android.app.Application
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bignerdranch.android.myapplication.repository.ReportPrefRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.*

data class ReportPeriod(val from: Long, val to: Long)

class ReportViewModel(app: Application) : AndroidViewModel(app) {

    private val prefRepo = ReportPrefRepository(app.applicationContext)

    private val _selectedItemNames = MutableStateFlow<Set<String>>(emptySet())
    val selectedItemNames = _selectedItemNames.asStateFlow()

    init {
        viewModelScope.launch {
            _selectedItemNames.value = prefRepo.selectedItemNamesFlow.first()
        }
    }

    fun setSelectedItemNames(names: Set<String>) {
        _selectedItemNames.value = names
        viewModelScope.launch { prefRepo.saveSelectedItemNames(names) }
    }

    fun clearSelectedItemNames() {
        _selectedItemNames.value = emptySet()
        viewModelScope.launch { prefRepo.clearSelectedItemNames() }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private val kst: ZoneId = ZoneId.of("Asia/Seoul")

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startOfThisMonth(): Long {
        val now = ZonedDateTime.now(kst)
        val firstDay = now.withDayOfMonth(1).toLocalDate()
        return firstDay.atStartOfDay(kst).toInstant().toEpochMilli()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun endOfToday(): Long {
        val now = ZonedDateTime.now(kst).toLocalDate()
        // 오늘 23:59:59.999
        return now.plusDays(1).atStartOfDay(kst).toInstant().toEpochMilli() - 1
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private val _period = MutableStateFlow(
        ReportPeriod(
            from = startOfThisMonth(),
            to = endOfToday()
        )
    )
    @RequiresApi(Build.VERSION_CODES.O)
    val period: StateFlow<ReportPeriod> = _period

    private val _refreshTick = MutableStateFlow(0L)
    val refreshTick: StateFlow<Long> = _refreshTick

    @RequiresApi(Build.VERSION_CODES.O)
    fun setPeriod(from: Long, to: Long) {
        _period.value = ReportPeriod(from, to)
        _refreshTick.value = System.currentTimeMillis()
    }

    fun forceRefresh() {
        _refreshTick.value = System.currentTimeMillis()
    }

    val selectedItemId = MutableStateFlow<Long?>(null)
    val selectedCountryId = MutableStateFlow<Long?>(null)
    var prevPeriodBeforeToday: ReportPeriod? = null

    @RequiresApi(Build.VERSION_CODES.O)
    fun toggleToday(today: ReportPeriod) {
        val cur = _period.value
        val isToday = (cur.from == today.from && cur.to == today.to)

        if (!isToday) {
            prevPeriodBeforeToday = cur
            setPeriod(today.from, today.to)
        } else {
            val prev = prevPeriodBeforeToday
            if (prev != null) setPeriod(prev.from, prev.to)
            else setPeriod(startOfThisMonth(), endOfToday())
        }
    }

}