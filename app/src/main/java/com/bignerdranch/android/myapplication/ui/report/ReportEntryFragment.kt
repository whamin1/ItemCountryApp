package com.bignerdranch.android.myapplication.ui.report

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.annotation.RequiresApi
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
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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


        view.findViewById<MaterialButton>(R.id.btnDetail).setOnClickListener {
            findNavController().navigate(R.id.action_reportEntryFragment_to_reportItemsFragment)
        }

        recycler = view.findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = ReportDayAdapter(
            onClick = { row ->
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
                combine(reportVm.period, reportVm.refreshTick) { p, _ -> p }
                    .collectLatest { p ->
                        // ✅ 기간 텍스트 갱신
                        tvPeriod.text = "${fmt.format(Date(p.from))} ~ ${fmt.format(Date(p.to))}"

                        // ✅ 오늘 칩 체크 상태도 갱신(원하면)
                        val today = todayRangeKst()
                        chipToday.isChecked = (p.from == today.from && p.to == today.to)

                        vm.fixWasteWeightAtInPeriod(p.from, p.to)

                        val rows = dao.reportAggByDay(p.from, p.to)
                        adapter.submit(rows)
                        renderSummary(rows)
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
    private fun renderSummary(dayRows: List<ItemCountryDao.ReportDayAggRow>) {
        val v = view ?: return

        val days = dayRows.size
        val totalKg = dayRows.sumOf { it.totalKg }
        val itemKg = dayRows.sumOf { it.itemKg }
        val wasteKg = dayRows.sumOf { it.wasteKg }
        val totalPrice = dayRows.sumOf { it.totalPrice }

        val kgFormat = NumberFormat.getNumberInstance(Locale.KOREA).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        val priceFormat = NumberFormat.getNumberInstance(Locale.KOREA)

        fun kg(x: Double) = "${kgFormat.format(x)} kg"
        fun money(x: Long) = priceFormat.format(x)
        fun ratio(x: Double) =
            if (totalKg > 0) String.format(Locale.KOREA, "%.1f%%", (x / totalKg) * 100)
            else "-"

        v.findViewById<TextView>(R.id.tvSummaryDays).text = "총 ${days}일"
        v.findViewById<TextView>(R.id.tvSummaryKgTotal).text =
            "총 무게: ${kg(totalKg)}"

        v.findViewById<TextView>(R.id.tvSummaryKgItem).text =
            "아이템: ${kg(itemKg)} (${ratio(itemKg)})"

        v.findViewById<TextView>(R.id.tvSummaryKgWaste).text =
            "쓰레기: ${kg(wasteKg)} (${ratio(wasteKg)})"

        v.findViewById<TextView>(R.id.tvSummaryPrice).text =
            "총 가격: ${money(totalPrice)}"
    }
}