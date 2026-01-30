package com.bignerdranch.android.myapplication.ui.report

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
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

        val argFrom = arguments?.getLong("from", -1L) ?: -1L
        val argTo   = arguments?.getLong("to", -1L) ?: -1L

        periodLocal.value =
            if (argFrom > 0 && argTo > 0) ReportPeriod(argFrom, argTo)   // ✅ 어댑터 클릭: 그날 하루
            else reportVm.period.value                                    // ✅ 자세히 보기: A에서 설정한 기간


        // Recycler
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = ReportItemAggAdapter(
            onClick = { row ->
                // 다음 단계(C): 아이템 상세로 이동할 때 여기서 selectedItemId 넣고 navigate
                reportVm.selectedItemId.value = row.itemId

                val p = periodLocal.value ?: reportVm.period.value
                val args = Bundle().apply {
                    putLong("from", p.from)
                    putLong("to", p.to)
                }

                findNavController().navigate(R.id.action_reportItemsFragment_to_reportItemDetailFragment, args)
            }
        )
        recycler.adapter = adapter

        // 오늘 칩
        chipToday.setOnClickListener {
            periodLocal.value = todayRangeKst()
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

                periodLocal.value = ReportPeriod(from, to)
            }

            picker.show(parentFragmentManager, "report_items_range")
        }

        // 구독: 기간 표시 + 오늘 체크 + 드롭다운 국가 목록 + 아이템 집계
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // 1) 기간 바뀔 때마다 헤더 + 드롭다운 목록 갱신
                launch {
                    periodLocal.collectLatest { p0 ->
                        val p = p0 ?: return@collectLatest

                        tvPeriod.text = "${fmt.format(Date(p.from))} ~ ${fmt.format(Date(p.to))}"
                        val today = todayRangeKst()
                        chipToday.isChecked = (p.from == today.from && p.to == today.to)

                        val countries = dao.reportCountriesInPeriod(p.from, p.to)
                        val names = mutableListOf("All").apply { addAll(countries.map { it.name }) }

                        val ad = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names)
                        actCountry.setAdapter(ad)

                        if (actCountry.text.isNullOrBlank()) {
                            actCountry.setText("All", false)
                            reportVm.selectedCountryId.value = null
                        }
                    }
                }

                // 2) (기간 + 선택 국가) 바뀌면 아이템 집계 갱신
                launch {
                    combine(
                        periodLocal.filterNotNull(),
                        reportVm.selectedCountryId
                    ) { p, countryId -> p to countryId }
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
                    val p = periodLocal.value ?: return@launch
                    val countries = dao.reportCountriesInPeriod(p.from, p.to)
                    val id = countries.firstOrNull { it.name == picked }?.id
                    reportVm.selectedCountryId.value = id
                }
            }
        }
    }
}