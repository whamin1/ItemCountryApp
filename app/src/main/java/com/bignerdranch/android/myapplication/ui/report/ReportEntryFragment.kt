package com.bignerdranch.android.myapplication.ui.report

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.SearchView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.*

class ReportEntryFragment : Fragment(R.layout.fragment_report_entry) {

    private val reportVm: ReportViewModel by activityViewModels()
    private val fmt = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ReportDayAdapter

    @RequiresApi(Build.VERSION_CODES.O)
    private val KST = ZoneId.of("Asia/Seoul")
    private val vm: ItemCountryViewModel by activityViewModels()
    private val dao: ItemCountryDao by lazy { AppDatabase.get(requireContext()).itemCountryDao() }

    private var cachedAllItems: List<String> = emptyList()

    private val DAY_MS = 86_400_000L
    private val KST_OFFSET_MS = 32_400_000L // +9h

    private fun dayIndexToRange(dayIndexKst: Long): Pair<Long, Long> {
        val start = dayIndexKst * DAY_MS - KST_OFFSET_MS
        val end = start + DAY_MS - 1
        return start to end
    }


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

        vm.loadReportSelectedItemNames()

        val chipItemFilter = view.findViewById<com.google.android.material.chip.Chip>(R.id.chipItemFilter)

        chipItemFilter.setOnClickListener {
            if (cachedAllItems.isEmpty()) {
                Toast.makeText(requireContext(), "아이템이 없습니다", Toast.LENGTH_SHORT).show()
            } else {
                openSearchableMultiSelectDialog(cachedAllItems)
            }
        }
        view.findViewById<MaterialButton>(R.id.btnDetail).setOnClickListener {
            findNavController().navigate(R.id.action_reportEntryFragment_to_reportItemsFragment)
        }

        recycler = view.findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = ReportDayAdapter(
            onClick = { row ->
                val (from, to) = dayIndexToRange(row.dayIndexKst)

                viewLifecycleOwner.lifecycleScope.launch {
                    val logs = dao.debugLogsInRange(from, to)
                    Log.d("DBG", "range=${Date(from)} ~ ${Date(to)} count=${logs.size}")
                    logs.forEach {
                        Log.d(
                            "DBG",
                            "id=${it.id} delta=${it.delta} batchId=${it.batchId} archived=${it.archived} ts=${it.timestamp}"
                        )
                    }
                }

                val args = Bundle().apply {
                    putLong("from", from)
                    putLong("to", to)
                }

                findNavController().navigate(
                    R.id.action_reportEntryFragment_to_reportItemsFragment,
                    args
                )
            }
        )
        recycler.adapter = adapter
        val tvPeriod = view.findViewById<TextView>(R.id.tvPeriod)
        val chipToday = view.findViewById<com.google.android.material.chip.Chip>(R.id.chipToday)



        chipToday.setOnClickListener {

            reportVm.toggleToday(todayRangeKst())

        }

        // 기간 표시 구독
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(reportVm.period, reportVm.refreshTick, reportVm.selectedItemNames) { p, _, names ->
                    p to names
                }
                    .collectLatest { (p, selectedNames) ->
                        tvPeriod.text = "${fmt.format(Date(p.from))} ~ ${fmt.format(Date(p.to))}"

                        val today = todayRangeKst()
                        chipToday.isChecked = (p.from == today.from && p.to == today.to)

                        vm.fixWasteWeightAtInPeriod(p.from, p.to)

                        cachedAllItems = dao.reportDistinctItemsInPeriod(p.from, p.to)

                        chipItemFilter.text =
                            if (selectedNames.isEmpty()) "아이템 필터"
                            else "필터 ${selectedNames.size}/10"
                        chipItemFilter.isChecked = selectedNames.isNotEmpty()

                        val rows = dao.reportAggByDay(p.from, p.to)

                        val selByDay: Map<Long, ItemCountryDao.DaySelAgg> =
                            if (selectedNames.isNotEmpty()) {
                                dao.reportSelectedAggByDay(p.from, p.to, selectedNames.toList())
                                    .associateBy { it.dayIndexKst }
                            } else emptyMap()

                        val merged = rows.map { r ->
                            val sel = selByDay[r.dayIndexKst]
                            r.copy(
                                selKg = sel?.kg ?: 0.0,
                                selCnt = sel?.cnt ?: 0,
                                selPrice = sel?.price ?: 0L
                            )
                        }


                            val selectedRows =
                            if (selectedNames.isEmpty()) emptyList()
                            else dao.reportAggForSelectedItems(p.from, p.to, selectedNames.toList(), 10)

                        val selAgg = if (selectedNames.isNotEmpty()) {
                            dao.reportSelectedAggByItemNames(p.from, p.to, selectedNames.toList())
                        } else null

                        adapter.submit(merged)
                        renderSummary(merged, selAgg, selectedNames, selectedRows)  // ✅ 이게 맞는 호출
                    }
            }
        }

        // 기간 클릭 → DateRangePicker
        tvPeriod.setOnClickListener {
            val picker = MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText("기간 선택")
                .build()

            picker.addOnPositiveButtonClickListener { range ->
                val startUtc = range.first ?: return@addOnPositiveButtonClickListener
                val endUtc   = range.second ?: return@addOnPositiveButtonClickListener

                val from = kstStartOfDayMs(startUtc)
                val to   = kstEndOfDayMs(endUtc)

                reportVm.setPeriod(from, to)
            }

            picker.show(parentFragmentManager, "report_range")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun kstStartOfDayMs(utcMillis: Long): Long {
        val d = Instant.ofEpochMilli(utcMillis).atZone(KST).toLocalDate()
        return d.atStartOfDay(KST).toInstant().toEpochMilli()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun kstEndOfDayMs(utcMillis: Long): Long {
        val d = Instant.ofEpochMilli(utcMillis).atZone(KST).toLocalDate()
        return d.plusDays(1).atStartOfDay(KST).toInstant().toEpochMilli() - 1
    }
    private fun renderSummary(
        dayRows: List<ItemCountryDao.ReportDayAggRow>,
        selAgg: ItemCountryDao.SelectedAgg?,
        selectedNames: Set<String>,
        selectedRows: List<ItemCountryDao.SelectedItemAggRow>
    ) {
        val v = view ?: return

        val days = dayRows.size
        val totalKg = dayRows.sumOf { it.totalKg }
        val itemKg  = dayRows.sumOf { it.itemKg }
        val wasteKg = dayRows.sumOf { it.wasteKg }
        val totalPrice = dayRows.sumOf { it.totalPrice }

        val totalCnt = dayRows.sumOf { it.totalCnt }
        val itemCnt  = dayRows.sumOf { it.itemCnt }
        val wasteCnt = dayRows.sumOf { it.wasteCnt }

        val kgFormat = NumberFormat.getNumberInstance(Locale.KOREA).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        val numFormat = NumberFormat.getNumberInstance(Locale.KOREA)

        fun kg(x: Double) = "${kgFormat.format(x)} kg"
        fun n(x: Int) = "${numFormat.format(x)}개"
        fun money(x: Long) = numFormat.format(x)

        fun ratio(x: Double) =
            if (totalKg > 0) String.format(Locale.KOREA, "%.1f%%", (x / totalKg) * 100) else "-"

        fun line(label: String, kgv: Double, cnt: Int, r: String? = null) =
            if (r == null) "$label: ${kg(kgv)} / ${n(cnt)}"
            else "$label: ${kg(kgv)} / ${n(cnt)} ($r)"

        val selKg = selAgg?.selKg ?: 0.0
        val selCnt = selAgg?.selCnt ?: 0
        val selPrice = selAgg?.selPrice ?: 0L

        val itemKgEx = (itemKg - selKg).coerceAtLeast(0.0)
        val itemCntEx = (itemCnt - selCnt).coerceAtLeast(0)

        v.findViewById<TextView>(R.id.tvSummaryDays).text = "총 ${days}일"
        v.findViewById<TextView>(R.id.tvSummaryKgTotal).text =
            line("총 무게", totalKg, totalCnt)

        v.findViewById<TextView>(R.id.tvSummaryKgItem).text =
            if (selectedNames.isEmpty())
                line("아이템", itemKg, itemCnt, ratio(itemKg))
            else
                line("아이템(선택 제외)", itemKgEx, itemCntEx, ratio(itemKgEx))

        v.findViewById<TextView>(R.id.tvSummaryKgWaste).text =
            line("쓰레기", wasteKg, wasteCnt, ratio(wasteKg))

        val tvSelectedRows = v.findViewById<TextView>(R.id.tvSummarySelectedRows)

        tvSelectedRows.isVisible = selectedRows.isNotEmpty()

        if (selectedRows.isNotEmpty()) {
            val lines = selectedRows.map { r ->
                val rRatio =
                    if (totalKg > 0) String.format(Locale.KOREA, "%.1f%%", (r.kg / totalKg) * 100) else "-"

                // cnt 옆에 가격 표시
                "${r.item}: ${kg(r.kg)} / ${n(r.cnt)} ($rRatio) (${money(r.price)})"
            }
            tvSelectedRows.text = lines.joinToString("\n")
        }

        // ✅ 선택 라인(새 TextView 하나 추가 권장)
        v.findViewById<TextView>(R.id.tvSummarySelected).apply {
            isVisible = selectedNames.isNotEmpty()
            if (selectedNames.isNotEmpty()) {
                text = line("선택 아이템", selKg, selCnt, ratio(selKg))
            }
        }

        v.findViewById<TextView>(R.id.tvSummaryPrice).text =
            if (selectedNames.isEmpty()) "총 가격: ${money(totalPrice)}"
            else "총 가격: ${money(totalPrice)} (선택: ${money(selPrice)})"
    }

    private fun openSearchableMultiSelectDialog(allItems: List<String>) {
        val v = layoutInflater.inflate(R.layout.dialog_multi_select_items, null)
        val search = v.findViewById<SearchView>(R.id.searchView)
        val tvCount = v.findViewById<TextView>(R.id.tvCount)
        val rv = v.findViewById<RecyclerView>(R.id.recycler)

        rv.layoutManager = LinearLayoutManager(requireContext())

        fun updateCount(sel: Set<String>) {
            tvCount.text = "${sel.size}/10 선택"
        }

        val adapter = ReportItemMultiSelectAdapter(
            allItems = allItems,
            initialSelected = reportVm.selectedItemNames.value,
            maxSel = 10,
            onSelectionChanged = { sel -> updateCount(sel) },
            onOverLimit = {
                Toast.makeText(requireContext(), "최대 10개까지 선택 가능", Toast.LENGTH_SHORT).show()
            }
        )

        rv.adapter = adapter
        updateCount(adapter.getSelected())

        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = true
            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filter(newText.orEmpty())
                return true
            }
        })

        AlertDialog.Builder(requireContext())
            .setTitle("아이템 선택")
            .setView(v)
            .setPositiveButton("적용") { _, _ ->
                reportVm.setSelectedItemNames(adapter.getSelected())
                reportVm.forceRefresh()
            }
            .setNegativeButton("취소", null)
            .setNeutralButton("초기화") { _, _ ->
                reportVm.clearSelectedItemNames()
                reportVm.forceRefresh()
            }
            .show()
    }
}