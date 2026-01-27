package com.bignerdranch.android.myapplication.ui.report

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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

    @RequiresApi(Build.VERSION_CODES.O)
    private val KST = ZoneId.of("Asia/Seoul")

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
        val actCountry = view.findViewById<MaterialAutoCompleteTextView>(R.id.actCountry)

        val tvPeriod = view.findViewById<TextView>(R.id.tvPeriod)
        val chipToday = view.findViewById<Chip>(R.id.chipToday)

        // Recycler
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = ReportItemAggAdapter(
            onClick = { row ->
                // 다음 단계(C): 아이템 상세로 이동할 때 여기서 selectedItemId 넣고 navigate
                reportVm.selectedItemId.value = row.itemId

                findNavController().navigate(R.id.action_reportItemsFragment_to_reportItemDetailFragment)
            }
        )
        recycler.adapter = adapter

        // 오늘 칩
        chipToday.setOnClickListener {
            reportVm.toggleToday(todayRangeKst())
        }

        // 기간 클릭 -> DateRangePicker (KST 보정 적용)
        tvPeriod.setOnClickListener {
            val picker = MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText("기간 선택")
                .build()

            picker.addOnPositiveButtonClickListener { range ->
                val first = range.first ?: return@addOnPositiveButtonClickListener
                val second = range.second ?: return@addOnPositiveButtonClickListener

                // ✅ KST 기준 하루 시작~끝으로 보정 (UTC millis 그대로 쓰면 날짜 경계가 흔들릴 수 있음)
                val from = Instant.ofEpochMilli(first).atZone(KST).toLocalDate()
                    .atStartOfDay(KST).toInstant().toEpochMilli()
                val to = Instant.ofEpochMilli(second).atZone(KST).toLocalDate()
                    .plusDays(1).atStartOfDay(KST).toInstant().toEpochMilli() - 1

                reportVm.setPeriod(from, to)
            }

            picker.show(parentFragmentManager, "report_items_range")
        }

        // 구독: 기간 표시 + 오늘 체크 + 드롭다운 국가 목록 + 아이템 집계
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // 1) 기간 바뀔 때마다 헤더 + 드롭다운 목록 갱신
                launch {
                    reportVm.period.collectLatest { p ->
                        tvPeriod.text = "${fmt.format(Date(p.from))} ~ ${fmt.format(Date(p.to))}"
                        val today = todayRangeKst()
                        chipToday.isChecked = (p.from == today.from && p.to == today.to)

                        // 국가 목록(기간 내 등장한 국가들)
                        val countries = dao.reportCountriesInPeriod(p.from, p.to)
                        val names = mutableListOf("All")
                        names.addAll(countries.map { it.name })

                        val ad = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names)
                        actCountry.setAdapter(ad)

                        // 기본 선택: All (처음 진입 때만)
                        if (actCountry.text.isNullOrBlank()) {
                            actCountry.setText("All", false)
                            reportVm.selectedCountryId.value = null
                        }
                    }
                }

                // 2) (기간 + 선택 국가) 바뀌면 아이템 집계 갱신
                launch {
                    combine(reportVm.period, reportVm.selectedCountryId) { p, countryId -> p to countryId }
                        .collectLatest { (p, countryId) ->
                            val rows = dao.reportAggByItemInPeriod(p.from, p.to, countryId)
                            adapter.submit(rows)
                        }
                }
            }
        }

        // 드롭다운 선택 -> selectedCountryId 반영
        actCountry.setOnItemClickListener { _, _, pos, _ ->
            val picked = actCountry.adapter.getItem(pos) as String
            if (picked == "All") {
                reportVm.selectedCountryId.value = null
            } else {
                // name -> id 매핑(기간 내 목록 기반이므로 안전)
                viewLifecycleOwner.lifecycleScope.launch {
                    val p = reportVm.period.value
                    val countries = dao.reportCountriesInPeriod(p.from, p.to)
                    val id = countries.firstOrNull { it.name == picked }?.id
                    reportVm.selectedCountryId.value = id
                }
            }
        }
    }
}