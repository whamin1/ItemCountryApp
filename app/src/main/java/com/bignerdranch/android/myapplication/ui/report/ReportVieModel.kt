package com.bignerdranch.android.myapplication.ui.report

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.*
import java.util.*

data class ReportPeriod(val from: Long, val to: Long)

class ReportViewModel : ViewModel() {
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