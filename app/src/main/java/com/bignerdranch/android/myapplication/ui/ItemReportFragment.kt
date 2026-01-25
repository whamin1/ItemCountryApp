package com.bignerdranch.android.myapplication.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ItemReportFragment : Fragment(R.layout.fragment_item_report) {

    private val vm: ItemCountryViewModel by activityViewModels()

    private fun formatDuration(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}시간 ${m}분" else "${m}분"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val itemId = requireArguments().getLong("itemId")
        val toolbar = view.findViewById<MaterialToolbar>(R.id.topAppBar)

        toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        toolbar.inflateMenu(R.menu.menu_item_report)

        viewLifecycleOwner.lifecycleScope.launch {
            val report = vm.buildItemReport(itemId)

            toolbar.title = report.itemName

            val speedText = report.summary.kgPerHour?.let { String.format("%.2f kg/h", it) } ?: "— kg/h"
            val avgText = report.summary.avgWorkGapMs?.let { formatDuration(it) } ?: "—"
            toolbar.subtitle =
                "$speedText · 평균 $avgText · ${report.summary.sampleCount}/${report.summary.totalCount}"

            // 🔽 숨김 상태 반영
            updateHiddenIcon(toolbar, itemId)
        }

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_toggle_hidden -> {
                    toggleHidden(itemId)
                    updateHiddenIcon(toolbar, itemId)
                    true
                }
                else -> false
            }
        }

        val rv = view.findViewById<RecyclerView>(R.id.rvReport)
        val adapter = ItemReportAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            val report = vm.buildItemReport(itemId)

            val ui = mutableListOf<ItemCountryDao.ItemReportUi>()

            ui += ItemCountryDao.ItemReportUi.Header("최근 생산 로그")

            report.logs.forEach {
                ui += ItemCountryDao.ItemReportUi.LogRow(
                    time = formatTime(it.ts),
                    country = it.country,
                    weight = "${it.weightKg} kg",
                    speed = "${fmt2(it.speedKgPerHour)} kg/h",
                    duration = formatDuration(it.workGapMs)
                )
            }

            ui += ItemCountryDao.ItemReportUi.Header("다음 예상")

            report.nextPredictions.forEach {
                ui += ItemCountryDao.ItemReportUi.PredictionRow(
                    time = formatTime(it.predictedAt),
                    label = it.label
                )
            }

            adapter.submit(ui)
        }
    }

    private val PREFS_NAME = "pred_prefs"
    private val KEY_EXCLUDED_IDS = "excluded_item_ids"

    private fun prefs() =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getExcludedIds(): Set<Long> =
        prefs().getStringSet(KEY_EXCLUDED_IDS, emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet() ?: emptySet()

    private fun setExcludedIds(ids: Set<Long>) {
        prefs().edit()
            .putStringSet(KEY_EXCLUDED_IDS, ids.map { it.toString() }.toSet())
            .apply()
    }

    private fun toggleHidden(itemId: Long) {
        val set = getExcludedIds().toMutableSet()
        if (set.contains(itemId)) set.remove(itemId) else set.add(itemId)
        setExcludedIds(set)
    }

    private fun updateHiddenIcon(toolbar: MaterialToolbar, itemId: Long) {
        val hidden = getExcludedIds().contains(itemId)
        val item = toolbar.menu.findItem(R.id.action_toggle_hidden)

        if (hidden) {
            // 👁️ 감은 상태 = 숨김
            item.setIcon(R.drawable.ic_eye_closed)
            item.title = "예측에 다시 포함"
        } else {
            // 👀 뜬 상태 = 표시
            item.setIcon(R.drawable.ic_eye_open)
            item.title = "예측에서 숨기기"
        }
    }

    private fun fmt2(x: Double?): String = String.format(Locale.US, "%.2f", x)

    private fun formatTime(ts: Long): String {
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA)
        return sdf.format(Date(ts))
    }
}