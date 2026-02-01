package com.bignerdranch.android.myapplication.ui.report

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.widget.SearchView
import android.widget.TextView
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

class ReportItemsFragment : Fragment(R.layout.fragment_report_items) {

    private val reportVm: ReportViewModel by activityViewModels()
    private val dao: ItemCountryDao by lazy { AppDatabase.get(requireContext()).itemCountryDao() }

    private lateinit var adapter: ReportItemAggAdapter
    private val fmt = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
    private val periodLocal = MutableStateFlow<ReportPeriod?>(null)

    @RequiresApi(Build.VERSION_CODES.O)
    private val KST = ZoneId.of("Asia/Seoul")

    private lateinit var tvHeaderLine1: TextView
    private lateinit var tvHeaderLine2: TextView


    @RequiresApi(Build.VERSION_CODES.O)
    private fun todayRangeKst(): ReportPeriod {
        val now = Instant.now().atZone(KST).toLocalDate()
        val start = now.atStartOfDay(KST).toInstant().toEpochMilli()
        val endExclusive = now.plusDays(1).atStartOfDay(KST).toInstant().toEpochMilli()
        return ReportPeriod(from = start, to = endExclusive - 1)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvPeriod = view.findViewById<TextView>(R.id.tvPeriod)
        val chipToday = view.findViewById<Chip>(R.id.chipToday)
        val searchView = view.findViewById<SearchView>(R.id.searchView)
        tvHeaderLine1 = view.findViewById(R.id.tvHeaderLine1)
        tvHeaderLine2 = view.findViewById(R.id.tvHeaderLine2)


        val argFrom = arguments?.getLong("from", -1L) ?: -1L
        val argTo = arguments?.getLong("to", -1L) ?: -1L

        periodLocal.value =
            if (argFrom > 0 && argTo > 0) ReportPeriod(argFrom, argTo)
            else reportVm.period.value

        // Recycler
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = ReportItemAggAdapter { row ->
            reportVm.selectedItemId.value = row.itemId

            val p = periodLocal.value ?: reportVm.period.value
            val args = Bundle().apply {
                putLong("from", p.from)
                putLong("to", p.to)
            }
            findNavController().navigate(
                R.id.action_reportItemsFragment_to_reportItemDetailFragment,
                args
            )
        }
        recycler.adapter = adapter

        chipToday.setOnClickListener { periodLocal.value = todayRangeKst() }

        tvPeriod.setOnClickListener {
            val picker = MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText("기간 선택")
                .build()

            picker.addOnPositiveButtonClickListener { range ->
                val first = range.first ?: return@addOnPositiveButtonClickListener
                val second = range.second ?: return@addOnPositiveButtonClickListener

                val from = Instant.ofEpochMilli(first).atZone(KST).toLocalDate()
                    .atStartOfDay(KST).toInstant().toEpochMilli()

                val to = Instant.ofEpochMilli(second).atZone(KST).toLocalDate()
                    .plusDays(1).atStartOfDay(KST).toInstant().toEpochMilli() - 1

                periodLocal.value = ReportPeriod(from, to)
            }
            picker.show(parentFragmentManager, "report_items_range")
        }

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true
            override fun onQueryTextChange(q: String?): Boolean {
                adapter.filter(q.orEmpty())
                // 검색은 UI 필터라서 헤더 합계는 “전체(rows)” 기준으로 두는 게 자연스러움.
                // (검색 결과 합계로 바꾸고 싶으면 renderHeader(adapter.getShown()) 같은 구조로 확장)
                return true
            }
        })

        // 구독: 기간 표시 + 오늘 체크 + 아이템 집계
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                combine(
                    periodLocal.filterNotNull(),
                    reportVm.selectedItemNames,
                    reportVm.selectedCountryId
                ) { p, selectedNames, countryId ->
                    Triple(p, selectedNames, countryId)
                }.collectLatest { (p, selectedNames, countryId) ->

                    tvPeriod.text = "${fmt.format(Date(p.from))} ~ ${fmt.format(Date(p.to))}"
                    val today = todayRangeKst()
                    chipToday.isChecked = (p.from == today.from && p.to == today.to)

                    val rows = dao.reportAggByItemInPeriod(p.from, p.to, countryId)

                    val selectedRows =
                        if (selectedNames.isNotEmpty())
                            rows.filter { it.item in selectedNames || it.item == "쓰레기" }
                        else emptyList()

                    adapter.submit(rows)
                    renderHeader(rows, selectedRows)
                }
            }
        }
    }

    private fun renderHeader(
        rows: List<ItemCountryDao.ReportItemAggRow>,
        selectedRows: List<ItemCountryDao.ReportItemAggRow>
    ) {
        val totalCnt = rows.sumOf { it.totalDelta }
        val totalKg = rows.sumOf { it.totalKg }
        val totalPrice = rows.sumOf { it.totalPrice }

        val selCnt = selectedRows.sumOf { it.totalDelta }
        val selKg = selectedRows.sumOf { it.totalKg }
        val selPrice = selectedRows.sumOf { it.totalPrice }

        fun ratio(part: Double, total: Double): String =
            if (total > 0) String.format(Locale.KOREA, "%.1f%%", (part / total) * 100) else "-"

        // 1줄: 개수 + 무게
        val line1 = buildString {
            append("총 ${totalCnt}개")
            if (selCnt > 0)
                append(" (선택 ${selCnt}개, ${ratio(selKg, totalKg)})")

            append(" · ")

            append(String.format(Locale.KOREA, "%.2fkg", totalKg))
            if (selKg > 0)
                append(
                    String.format(
                        Locale.KOREA,
                        " (선택 %.2fkg, %s)",
                        selKg,
                        ratio(selKg, totalKg)
                    )
                )
        }

        // 2줄: 가격
        val line2 = buildString {
            append(String.format(Locale.KOREA, "총 ₩%,d", totalPrice))
            if (selPrice > 0)
                append(
                    String.format(
                        Locale.KOREA,
                        " (선택 ₩%,d, %s)",
                        selPrice,
                        ratio(selKg, totalKg) // ✅ 무게 기준 비율 유지
                    )
                )
        }

        tvHeaderLine1.text = line1
        tvHeaderLine2.text = line2
    }
}