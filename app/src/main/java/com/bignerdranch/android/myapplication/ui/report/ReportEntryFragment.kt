package com.bignerdranch.android.myapplication.ui.report

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
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
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.SearchView
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReportEntryFragment : Fragment(R.layout.fragment_report_entry) {

    private val reportVm: ReportViewModel by activityViewModels()
    private val fmt = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
    private lateinit var recycler: RecyclerView
    private lateinit var reportHeader: View
    private lateinit var adapter: ReportDayAdapter
    @RequiresApi(Build.VERSION_CODES.O)
    private val KST = ZoneId.of("Asia/Seoul")
    private val vm: ItemCountryViewModel by activityViewModels()
    private val dao: ItemCountryDao by lazy { AppDatabase.get(requireContext()).itemCountryDao() }
    private var cachedAllItems: List<String> = emptyList()
    private val DAY_MS = 86_400_000L
    private val KST_OFFSET_MS = 32_400_000L // +9h
    private var cachedDayRows: List<ItemCountryDao.ReportDayAggRow> = emptyList()
    private var cachedSelectedNames: Set<String> = emptySet()
    private var cachedSelectedRows: List<ItemCountryDao.SelectedItemAggRow> = emptyList()
    private var cachedSelAgg: ItemCountryDao.SelectedAgg? = null
    private var cachedPeriod: ReportPeriod? = null

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

        recycler = view.findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        reportHeader = layoutInflater.inflate(R.layout.item_report_entry_header, recycler, false)

        val chipItemFilter = reportHeader.findViewById<com.google.android.material.chip.Chip>(R.id.chipItemFilter)

        chipItemFilter.setOnClickListener {
            if (cachedAllItems.isEmpty()) {
                Toast.makeText(requireContext(), "아이템이 없습니다", Toast.LENGTH_SHORT).show()
            } else {
                openSearchableMultiSelectDialog(cachedAllItems)
            }
        }
        reportHeader.findViewById<MaterialButton>(R.id.btnDetail).setOnClickListener {
            findNavController().navigate(R.id.action_reportEntryFragment_to_reportItemsFragment)
        }
        reportHeader.findViewById<MaterialButton>(R.id.btnExport).setOnClickListener {
            val p = cachedPeriod ?: return@setOnClickListener
            exportReportToZipCsv(p.from, p.to, cachedDayRows, cachedSelectedNames, cachedSelectedRows, cachedSelAgg)
        }
        reportHeader.findViewById<MaterialButton>(R.id.btnShareWeight).setOnClickListener {
            shareWeightSummary(reportHeader)
        }

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
        recycler.adapter = ConcatAdapter(StaticHeaderAdapter(reportHeader), adapter)
        val tvPeriod = reportHeader.findViewById<TextView>(R.id.tvPeriod)
        val chipToday = reportHeader.findViewById<com.google.android.material.chip.Chip>(R.id.chipToday)



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
                        val visibleItemNames = dao.getVisibleItemNamesForReport().toSet()
                        val loggedItemCount = cachedAllItems.count { it in visibleItemNames }
                        val totalItemCount = visibleItemNames.size

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

                        val topOffRows = dao.reportAggByItemInPeriod(p.from, p.to, null)
                            .filter { it.waste == 0 && it.item !in selectedNames }
                            .sortedByDescending { it.totalPrice }
                            .take(10)
                        val sheetRows = dao.reportAggBySheetInPeriod(p.from, p.to)
                        val categoryRows = dao.reportAggByCategoryInPeriod(p.from, p.to)
                        val currentProductionDays = dao.countProductionDaysInPeriod(p.from, p.to)
                        val previousProductionDayIndexes =
                            if (currentProductionDays > 0) {
                                dao.getPreviousProductionDayIndexes(
                                    before = p.from,
                                    limit = currentProductionDays
                                )
                            } else {
                                emptyList()
                            }
                        val previousProductionDays = previousProductionDayIndexes.size
                        val previousRange =
                            if (previousProductionDayIndexes.isNotEmpty()) {
                                val previousFrom = dayIndexToRange(
                                    previousProductionDayIndexes.minOrNull()!!
                                ).first
                                val previousTo = dayIndexToRange(
                                    previousProductionDayIndexes.maxOrNull()!!
                                ).second
                                previousFrom to previousTo
                            } else {
                                null
                            }
                        val previousDayRows = previousRange?.let { (from, to) ->
                            dao.reportAggByDay(from, to)
                        }.orEmpty()
                        val previousCategoryRows = previousRange?.let { (from, to) ->
                            dao.reportAggByCategoryInPeriod(from, to)
                        }.orEmpty()
                        val previousSelAgg =
                            if (selectedNames.isNotEmpty() && previousRange != null) {
                                dao.reportSelectedAggByItemNames(
                                    previousRange.first,
                                    previousRange.second,
                                    selectedNames.toList()
                                )
                            } else {
                                null
                            }

                        adapter.submit(merged)
                        renderSummary(
                            merged,
                            previousDayRows,
                            selAgg,
                            previousSelAgg,
                            selectedNames,
                            topOffRows,
                            sheetRows,
                            categoryRows,
                            previousCategoryRows,
                            currentProductionDays,
                            previousProductionDays,
                            loggedItemCount,
                            totalItemCount
                        )
                        cachedPeriod = p
                        cachedDayRows = merged
                        cachedSelectedNames = selectedNames
                        cachedSelectedRows = selectedRows
                        cachedSelAgg = selAgg
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

    private fun shareWeightSummary(root: View) {
        val summary = root.findViewById<View>(R.id.incSummary)
        root.post {
            if (root.width <= 0 || summary.bottom <= 0) {
                Toast.makeText(requireContext(), "공유 이미지를 만들 수 없습니다.", Toast.LENGTH_SHORT).show()
                return@post
            }

            try {
                val density = resources.displayMetrics.density
                val headerHeight = (72 * density).toInt()
                val bitmap = Bitmap.createBitmap(
                    root.width,
                    headerHeight + summary.bottom,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                val background = TypedValue().also {
                    requireContext().theme.resolveAttribute(android.R.attr.colorBackground, it, true)
                }
                canvas.drawColor(background.data)

                val textColor = root.findViewById<TextView>(R.id.tvSummaryDays).currentTextColor
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = textColor
                    typeface = Typeface.DEFAULT_BOLD
                }
                paint.textSize = 20 * density
                val todayText = SimpleDateFormat("yyyy년 M월 d일", Locale.KOREA).format(Date())
                canvas.drawText(todayText, 20 * density, 29 * density, paint)
                paint.textSize = 15 * density
                paint.typeface = Typeface.DEFAULT
                canvas.drawText("오늘 무게입니다", 20 * density, 55 * density, paint)

                canvas.translate(0f, headerHeight.toFloat())
                root.draw(canvas)

                val shareDir = File(requireContext().cacheDir, "report_share").apply { mkdirs() }
                val imageFile = File(shareDir, "today_weight.png")
                imageFile.outputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                bitmap.recycle()

                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    imageFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "오늘 무게입니다")
                    clipData = ClipData.newUri(requireContext().contentResolver, "오늘 무게", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "오늘 무게 공유"))
            } catch (e: Exception) {
                Log.e("ReportEntry", "무게 요약 공유 실패", e)
                Toast.makeText(requireContext(), "공유 이미지를 만들지 못했습니다.", Toast.LENGTH_SHORT).show()
            }
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
        previousDayRows: List<ItemCountryDao.ReportDayAggRow>,
        selAgg: ItemCountryDao.SelectedAgg?,
        previousSelAgg: ItemCountryDao.SelectedAgg?,
        selectedNames: Set<String>,
        topOffRows: List<ItemCountryDao.ReportItemAggRow>,
        sheetRows: List<ItemCountryDao.ReportSheetAggRow>,
        categoryRows: List<ItemCountryDao.ReportCategoryAggRow>,
        previousCategoryRows: List<ItemCountryDao.ReportCategoryAggRow>,
        currentProductionDays: Int,
        previousProductionDays: Int,
        loggedItemCount: Int,
        totalItemCount: Int
    ) {
        val v = reportHeader

        val days = dayRows.size
        val totalKg = dayRows.sumOf { it.totalKg }
        val itemKg  = dayRows.sumOf { it.itemKg }
        val wasteKg = dayRows.sumOf { it.wasteKg }
        val totalPrice = dayRows.sumOf { it.totalPrice }

        val totalCnt = dayRows.sumOf { it.totalCnt }
        val itemCnt  = dayRows.sumOf { it.itemCnt }
        val wasteCnt = dayRows.sumOf { it.wasteCnt }
        val previousTotalKg = previousDayRows.sumOf { it.totalKg }
        val previousTotalCnt = previousDayRows.sumOf { it.totalCnt }
        val previousItemKg = previousDayRows.sumOf { it.itemKg }
        val previousWasteKg = previousDayRows.sumOf { it.wasteKg }

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

        val itemCoverage =
            if (totalItemCount > 0) String.format(
                Locale.KOREA,
                "%.1f%%",
                loggedItemCount.toDouble() / totalItemCount * 100
            ) else "-"

        fun line(label: String, kgv: Double, cnt: Int, r: String? = null) =
            if (r == null) "$label: ${kg(kgv)} / ${n(cnt)}"
            else "$label: ${kg(kgv)} / ${n(cnt)} ($r)"

        val selKg = selAgg?.selKg ?: 0.0
        val selCnt = selAgg?.selCnt ?: 0
        val selPrice = selAgg?.selPrice ?: 0L
        val previousSelKg = previousSelAgg?.selKg ?: 0.0

        val itemKgEx = (itemKg - selKg).coerceAtLeast(0.0)
        val itemCntEx = (itemCnt - selCnt).coerceAtLeast(0)
        val previousItemKgEx = (previousItemKg - previousSelKg).coerceAtLeast(0.0)

        v.findViewById<TextView>(R.id.tvSummaryDays).text = "조회 기간  ·  총 ${days}일"
        v.findViewById<TextView>(R.id.tvSummaryKgTotal).text = SpannableStringBuilder().apply {
            val increaseColor = ContextCompat.getColor(requireContext(), R.color.report_increase)
            val decreaseColor = ContextCompat.getColor(requireContext(), R.color.report_decrease)
            val currentDays = dayRows.size
            val previousDays = previousDayRows.size

            fun appendComparison(currentDaily: Double, previousDaily: Double) {
                val difference = currentDaily - previousDaily
                val comparison = when {
                    kotlin.math.abs(difference) < 0.005 -> "  — 동일"
                    difference > 0 && previousDaily <= 0.0 -> "  ▲ 신규"
                    difference > 0 -> String.format(
                        Locale.KOREA,
                        "  ▲ %.1f%%",
                        difference / previousDaily * 100
                    )
                    else -> String.format(
                        Locale.KOREA,
                        "  ▼ %.1f%%",
                        -difference / previousDaily * 100
                    )
                }
                val start = length
                append(comparison)
                when {
                    difference > 0.005 -> setSpan(
                        ForegroundColorSpan(increaseColor),
                        start,
                        length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    difference < -0.005 -> setSpan(
                        ForegroundColorSpan(decreaseColor),
                        start,
                        length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            val currentDailyKg = if (currentDays > 0) totalKg / currentDays else 0.0
            val previousDailyKg = if (previousDays > 0) previousTotalKg / previousDays else 0.0
            val currentDailyCnt = if (currentDays > 0) totalCnt.toDouble() / currentDays else 0.0
            val previousDailyCnt =
                if (previousDays > 0) previousTotalCnt.toDouble() / previousDays else 0.0

            append("[전체 · 로그가 있는 날 평균 비교]\n${kg(totalKg)}")
            appendComparison(currentDailyKg, previousDailyKg)
            append("  ·  ${n(totalCnt)}")
            appendComparison(currentDailyCnt, previousDailyCnt)
            append("\n기간 내 등장  ${loggedItemCount} / ${totalItemCount}종  ·  $itemCoverage")
        }

        fun appendCompositionChange(
            builder: SpannableStringBuilder,
            currentTotalKg: Double,
            previousTotalKg: Double
        ) {
            val currentDailyKg =
                if (currentProductionDays > 0) currentTotalKg / currentProductionDays else 0.0
            val previousDailyKg =
                if (previousProductionDays > 0) previousTotalKg / previousProductionDays else 0.0
            val difference = currentDailyKg - previousDailyKg
            val comparison = when {
                kotlin.math.abs(difference) < 0.005 -> "  — 동일"
                difference > 0 && previousDailyKg <= 0.0 -> "  ▲ 신규"
                difference > 0 -> String.format(
                    Locale.KOREA,
                    "  ▲ %.1f%%",
                    difference / previousDailyKg * 100
                )
                else -> String.format(
                    Locale.KOREA,
                    "  ▼ %.1f%%",
                    -difference / previousDailyKg * 100
                )
            }
            val start = builder.length
            builder.append(comparison)
            val color = when {
                difference > 0.005 -> ContextCompat.getColor(requireContext(), R.color.report_increase)
                difference < -0.005 -> ContextCompat.getColor(requireContext(), R.color.report_decrease)
                else -> null
            }
            color?.let {
                builder.setSpan(
                    ForegroundColorSpan(it),
                    start,
                    builder.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        val compositionItemKg = if (selectedNames.isEmpty()) itemKg else itemKgEx
        val previousCompositionItemKg =
            if (selectedNames.isEmpty()) previousItemKg else previousItemKgEx
        val compositionItemCnt = if (selectedNames.isEmpty()) itemCnt else itemCntEx
        v.findViewById<TextView>(R.id.tvSummaryKgItem).text = SpannableStringBuilder().apply {
            append(
                "[구성 · 생산일 평균 비교]\n" +
                        "• 일반 아이템  ${kg(compositionItemKg)}  ·  " +
                        "${n(compositionItemCnt)}  ·  ${ratio(compositionItemKg)}"
            )
            appendCompositionChange(this, compositionItemKg, previousCompositionItemKg)
        }

        v.findViewById<TextView>(R.id.tvSummaryKgWaste).text = SpannableStringBuilder().apply {
            append("• 쓰레기  ${kg(wasteKg)}  ·  ${n(wasteCnt)}  ·  ${ratio(wasteKg)}")
            appendCompositionChange(this, wasteKg, previousWasteKg)
            if (selectedNames.isNotEmpty()) {
                append("\n• 선택 아이템  ${kg(selKg)}  ·  ${n(selCnt)}  ·  ${ratio(selKg)}")
                appendCompositionChange(this, selKg, previousSelKg)
            }
        }

        v.findViewById<TextView>(R.id.tvSummarySheets).apply {
            isVisible = sheetRows.isNotEmpty()
            if (sheetRows.isNotEmpty()) {
                text = buildString {
                    append("[시트별 생산량]")
                    sheetRows.forEach { row ->
                        val sheetRatio =
                            if (itemKg > 0) String.format(
                                Locale.KOREA,
                                "%.1f%%",
                                row.totalKg / itemKg * 100
                            ) else "-"
                        append(
                            "\n• ${row.sheetTitle}  ${kg(row.totalKg)}  ·  " +
                                    "${n(row.totalDelta)}  ·  $sheetRatio  ·  ${money(row.totalPrice)}"
                        )
                    }
                }
            }
        }

        v.findViewById<TextView>(R.id.tvSummaryCategories).apply {
            isVisible = categoryRows.isNotEmpty()
            if (categoryRows.isNotEmpty()) {
                val previousByCategory = previousCategoryRows.associateBy { it.categoryName }
                val increaseColor = ContextCompat.getColor(requireContext(), R.color.report_increase)
                val decreaseColor = ContextCompat.getColor(requireContext(), R.color.report_decrease)
                val builder = SpannableStringBuilder(
                    "[분류별 생산량 · 생산일 평균 비교]" +
                            "\n현재 ${currentProductionDays}일 · 이전 ${previousProductionDays}일"
                )

                categoryRows.forEach { row ->
                    val categoryRatio =
                        if (itemKg > 0) String.format(
                            Locale.KOREA,
                            "%.1f%%",
                            row.totalKg / itemKg * 100
                        ) else "-"
                    builder.append(
                        "\n• ${row.categoryName}  ${kg(row.totalKg)}  ·  " +
                                "${n(row.totalDelta)}  ·  $categoryRatio  ·  ${money(row.totalPrice)}"
                    )

                    val currentDailyKg =
                        if (currentProductionDays > 0) row.totalKg / currentProductionDays else 0.0
                    val previousTotalKg = previousByCategory[row.categoryName]?.totalKg ?: 0.0
                    val previousDailyKg =
                        if (previousProductionDays > 0) previousTotalKg / previousProductionDays else 0.0
                    val difference = currentDailyKg - previousDailyKg
                    val comparison = when {
                        kotlin.math.abs(difference) < 0.005 -> "  — 동일"
                        difference > 0 && previousDailyKg <= 0.0 -> "  ▲ 신규"
                        difference > 0 -> String.format(
                            Locale.KOREA,
                            "  ▲ %.1f%%",
                            difference / previousDailyKg * 100
                        )
                        else -> String.format(
                            Locale.KOREA,
                            "  ▼ %.1f%%",
                            -difference / previousDailyKg * 100
                        )
                    }
                    val comparisonStart = builder.length
                    builder.append(comparison)
                    when {
                        difference > 0.005 -> builder.setSpan(
                            ForegroundColorSpan(increaseColor),
                            comparisonStart,
                            builder.length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        difference < -0.005 -> builder.setSpan(
                            ForegroundColorSpan(decreaseColor),
                            comparisonStart,
                            builder.length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                text = builder
            }
        }

        val tvSelectedRows = v.findViewById<TextView>(R.id.tvSummarySelectedRows)

        tvSelectedRows.isVisible = topOffRows.isNotEmpty()

        if (topOffRows.isNotEmpty()) {
            val lines = topOffRows.mapIndexed { index, row ->
                "${index + 1}. ${row.item}  ·  ${n(row.totalDelta)}  ·  ${money(row.totalPrice)}"
            }
            tvSelectedRows.text = "[오늘 금액 상위 10개]\n" + lines.joinToString("\n")
        }

        v.findViewById<TextView>(R.id.tvSummarySelected).apply {
            isVisible = false
        }

        v.findViewById<TextView>(R.id.tvSummaryPrice).text =
            if (selectedNames.isEmpty()) "[금액]\n전체  ${money(totalPrice)}"
            else {
                val remainingPrice = (totalPrice - selPrice).coerceAtLeast(0L)
                "[금액]\n전체  ${money(totalPrice)}  ·  선택  ${money(selPrice)}  ·  전체-선택  ${money(remainingPrice)}"
            }
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

    //엑셀
    private fun exportReportToZipCsv(
        periodFrom: Long,
        periodTo: Long,
        dayRows: List<ItemCountryDao.ReportDayAggRow>,
        selectedNames: Set<String>,
        selectedRows: List<ItemCountryDao.SelectedItemAggRow>,
        selAgg: ItemCountryDao.SelectedAgg?
    ) {
        viewLifecycleOwner.lifecycleScope.launch {

            val logs = if (selectedNames.isEmpty()) {
                dao.getPlusClicksInPeriod(periodFrom, periodTo) // 선택 없으면 전체
            } else {
                dao.getPlusClicksInPeriodExcludingItems(
                    periodFrom,
                    periodTo,
                    selectedNames.toList()
                )
            }

            val f1 = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date(periodFrom))
            val f2 = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date(periodTo))

            val zipName = "report_${f1}_${f2}.zip"
            val zipFile = File(requireContext().cacheDir, zipName)

            ZipOutputStream(zipFile.outputStream()).use { zos ->

                // ---------------- SUMMARY.csv ----------------
                zos.putNextEntry(ZipEntry("SUMMARY.csv"))
                val summaryCsv = buildSummaryCsv(
                    periodFrom, periodTo,
                    dayRows, selectedNames,
                    selectedRows, selAgg
                )
                zos.write("\uFEFF".toByteArray(Charsets.UTF_8))
                zos.write(summaryCsv.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // ---------------- DAILY.csv ----------------
                zos.putNextEntry(ZipEntry("DAILY.csv"))
                val dailyCsv = buildDailyCsv(dayRows)
                zos.write("\uFEFF".toByteArray(Charsets.UTF_8))
                zos.write(dailyCsv.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // ---------------- LOGS.csv ----------------
                zos.putNextEntry(ZipEntry("LOGS.csv"))
                val logsCsv = buildLogsCsv(logs, selectedNames)
                zos.write("\uFEFF".toByteArray(Charsets.UTF_8))
                zos.write(logsCsv.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                zipFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "리포트 ZIP 공유"))
        }
    }

    private fun buildSummaryCsv(
        from: Long,
        to: Long,
        dayRows: List<ItemCountryDao.ReportDayAggRow>,
        selectedNames: Set<String>,
        selectedRows: List<ItemCountryDao.SelectedItemAggRow>,
        selAgg: ItemCountryDao.SelectedAgg?
    ): String {

        val totalKg = dayRows.sumOf { it.totalKg }
        val itemKg = dayRows.sumOf { it.itemKg }
        val wasteKg = dayRows.sumOf { it.wasteKg }

        val totalCnt = dayRows.sumOf { it.totalCnt }
        val itemCnt = dayRows.sumOf { it.itemCnt }
        val wasteCnt = dayRows.sumOf { it.wasteCnt }

        val totalPrice = dayRows.sumOf { it.totalPrice }

        fun ratio(x: Double): String =
            if (totalKg > 0) String.format(Locale.KOREA, "%.1f%%", x / totalKg * 100) else "-"

        return buildString {

            appendLine("기간,${fmt.format(Date(from))} ~ ${fmt.format(Date(to))}")
            appendLine()

            appendLine("총 무게(kg),$totalKg")
            appendLine("총 개수,$totalCnt")
            appendLine("총 가격,$totalPrice")
            appendLine()

            appendLine("아이템 무게(kg),$itemKg (${ratio(itemKg)})")
            appendLine("아이템 개수,$itemCnt")
            appendLine("쓰레기 무게(kg),$wasteKg (${ratio(wasteKg)})")
            appendLine("쓰레기 개수,$wasteCnt")

            if (selectedRows.isNotEmpty()) {
                appendLine()
                appendLine("선택 아이템 상세")
                appendLine("item,kg,cnt,price")
                selectedRows.forEach {
                    appendLine("${csv(it.item)},${it.kg},${it.cnt},${it.price}")
                }
            }
        }
    }

    private fun buildDailyCsv(
        dayRows: List<ItemCountryDao.ReportDayAggRow>
    ): String {

        val DAY_MS = 86_400_000L
        val KST_OFFSET_MS = 32_400_000L
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)

        fun dayIndexToStartMs(d: Long) = d * DAY_MS - KST_OFFSET_MS

        return buildString {
            appendLine("date,totalKg,itemKg,wasteKg,totalCnt,itemCnt,wasteCnt,totalPrice,selKg,selCnt,selPrice")

            dayRows.forEach { d ->
                val dateStr = dayFmt.format(Date(dayIndexToStartMs(d.dayIndexKst)))
                appendLine(
                    "$dateStr," +
                            "${d.totalKg}," +
                            "${d.itemKg}," +
                            "${d.wasteKg}," +
                            "${d.totalCnt}," +
                            "${d.itemCnt}," +
                            "${d.wasteCnt}," +
                            "${d.totalPrice}," +
                            "${d.selKg}," +
                            "${d.selCnt}," +
                            "${d.selPrice}"
                )
            }
        }
    }

    private fun buildLogsCsv(
        logs: List<ItemCountryDao.QuantityRow>,
        selectedNames: Set<String>
    ): String {

        val tFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)

        return buildString {
            appendLine("NOTE,LOGS excludes selected items: ${selectedNames.joinToString("|")}")
            appendLine("time,item,country,fromHave,toHave,delta,weightAt,priceAt")

            logs.forEach { q ->
                appendLine(
                    "${tFmt.format(Date(q.timestamp))}," +
                            "${csv(q.item)}," +
                            "${csv(q.country)}," +
                            "${q.fromHave}," +
                            "${q.toHave}," +
                            "${q.delta}," +
                            "${q.weight ?: 0}," +
                            "${q.price ?: 0}"
                )
            }
        }
    }

    private fun csv(v: Any?): String {
        val s = (v?.toString() ?: "")
        val needsQuote = s.contains(",") || s.contains("\n") || s.contains("\r") || s.contains("\"")
        val escaped = s.replace("\"", "\"\"")
        return if (needsQuote) "\"$escaped\"" else escaped
    }

    private class StaticHeaderAdapter(
        private val headerView: View
    ) : RecyclerView.Adapter<StaticHeaderAdapter.HeaderVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderVH {
            (headerView.parent as? ViewGroup)?.removeView(headerView)
            return HeaderVH(headerView)
        }

        override fun onBindViewHolder(holder: HeaderVH, position: Int) = Unit

        override fun getItemCount(): Int = 1

        class HeaderVH(view: View) : RecyclerView.ViewHolder(view)
    }
}
