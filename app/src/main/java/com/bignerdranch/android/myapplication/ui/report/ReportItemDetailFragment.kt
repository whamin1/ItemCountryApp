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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

class ReportItemDetailFragment : Fragment(R.layout.fragment_report_item_detail) {

    private val reportVm: ReportViewModel by activityViewModels()
    private val vm: ItemCountryViewModel by activityViewModels()
    private val dao: ItemCountryDao by lazy { AppDatabase.get(requireContext()).itemCountryDao() }

    private lateinit var adapter: ReportItemLogAdapter
    private val nf = NumberFormat.getInstance(Locale.KOREA)
    private val fmt = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)

    @RequiresApi(Build.VERSION_CODES.O)
    private val KST = ZoneId.of("Asia/Seoul")

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvPeriod = view.findViewById<TextView>(R.id.tvPeriod)
        val tvTitle = view.findViewById<TextView>(R.id.tvTitle)
        val tvSummary = view.findViewById<TextView>(R.id.tvSummary)
        val actCountry = view.findViewById<MaterialAutoCompleteTextView>(R.id.actCountry)

        val argFrom = arguments?.getLong("from", -1L) ?: -1L
        val argTo   = arguments?.getLong("to", -1L) ?: -1L

        val fixedPeriod = if (argFrom > 0 && argTo > 0) {
            ReportPeriod(argFrom, argTo)
        } else {
            reportVm.period.value  // fallback
        }

        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = ReportItemLogAdapter()
        recycler.adapter = adapter

        // ✅ 기본: B 화면에서 고른 country 필터를 그대로 유지(이미 vm에 들어있음)
        if (actCountry.text.isNullOrBlank()) {
            actCountry.setText("All", false) // 표시만
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // 1) 헤더 + 드롭다운 목록(해당 아이템이 기간 내 등장한 국가)
                launch {
                    reportVm.selectedItemId.collectLatest { itemId ->
                        tvPeriod.text = "${fmt.format(Date(fixedPeriod.from))} ~ ${fmt.format(Date(fixedPeriod.to))}"

                        val id = itemId ?: run {
                            tvTitle.text = "아이템 상세 (선택 없음)"
                            return@collectLatest
                        }

                        val name = dao.getItemNameById(id) ?: "(deleted)"
                        tvTitle.text = "아이템 상세: $name"

                        val countries = dao.reportCountriesForItem(id, fixedPeriod.from, fixedPeriod.to)
                        val names = mutableListOf("All").apply { addAll(countries.map { it.name }) }
                        actCountry.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names))

                        // 기존 선택 유지
                        val curCountryId = reportVm.selectedCountryId.value
                        if (curCountryId == null) {
                            actCountry.setText("All", false)
                        } else {
                            val curName = countries.firstOrNull { it.id == curCountryId }?.name
                            if (!curName.isNullOrBlank()) actCountry.setText(curName, false)
                        }
                    }
                }

                // 2) 기간 + (선택 아이템 + 선택 국가)로 로그 갱신
                launch {
                    combine(reportVm.selectedItemId, reportVm.selectedCountryId) { itemId, countryId ->
                        itemId to countryId
                    }.collectLatest { (itemId, countryId) ->
                        val id = itemId ?: return@collectLatest

                        val report = vm.buildItemReport(
                            itemId = id,
                            from = fixedPeriod.from,
                            to = fixedPeriod.to,
                            countryId = countryId
                        )

                        adapter.submit(report.logs)

                        tvSummary.text = buildString {
                            append("총 ${nf.format(report.logs.size)}건 · ")
                            append("총 무게: ${String.format(Locale.KOREA, "%.2f", report.logs.sumOf { it.weightKg ?: 0.0 })} kg · ")
                            val avgGap = report.summary.avgWorkGapMs
                            append("평균 소요: ${if (avgGap != null) formatDuration(avgGap) else "-"} · ")
                            val kph = report.summary.kgPerHour
                            append("속도: ${if (kph != null) String.format(Locale.KOREA, "%.2f", kph) else "-"} kg/h")
                        }
                    }
                }
            }
        }

        // 드롭다운 선택 -> selectedCountryId
        actCountry.setOnItemClickListener { _, _, pos, _ ->
            val picked = actCountry.adapter.getItem(pos) as String
            if (picked == "All") {
                reportVm.selectedCountryId.value = null
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    val p = reportVm.period.value
                    val itemId = reportVm.selectedItemId.value ?: return@launch
                    val countries = dao.reportCountriesForItem(itemId, fixedPeriod.from, fixedPeriod.to)
                    reportVm.selectedCountryId.value = countries.firstOrNull { it.name == picked }?.id
                }
            }
        }
    }
    private fun formatDuration(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}시간 ${m}분" else "${m}분"
    }
}