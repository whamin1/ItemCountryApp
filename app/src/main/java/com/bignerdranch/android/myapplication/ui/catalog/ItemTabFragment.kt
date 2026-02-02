package com.bignerdranch.android.myapplication.ui.catalog

import android.content.Intent
import android.icu.text.NumberFormat
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import com.bignerdranch.android.myapplication.data.local.entity.ItemSearchRow
import com.bignerdranch.android.myapplication.data.local.entity.QuantityLogEntity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 아이템 탭 = “버튼 + 로 증가한 수량 로그” 전용 화면
 * - quantity_log 를 JOIN 한 Row 사용
 * - delta > 0 만 필터링해서 표시
 * - 최근순
 */
class ItemTabFragment : Fragment(R.layout.fragment_catalog_list) {

    private val fmt = SimpleDateFormat("yy/MM/dd HH:mm", Locale.getDefault())
    private val dao by lazy { AppDatabase.get(requireContext()).itemCountryDao() }

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: PlusLogAdapter
    private var allRows: List<ItemCountryDao.QuantityRow> = emptyList()
    private var selectedStartMillis: Long? = null
    private var selectedEndMillis: Long? = null
    private var toolbar: MaterialToolbar? = null
    private var currentRows: List<ItemCountryDao.QuantityRow> = emptyList()
    private var searchQuery: String = ""

    private var bulkMode = false
    private var bulkBaseDayMillis: Long? = null   // 기준 날짜(00:00)
    private var bulkNextMinute = 9 * 60           // 다음 입력 시간(분) 기본 09:00
    private var bulkStepMin = 5                   // 간격(분) 기본 5분
    private var bulkDelta = 1                     // 기본 delta
    private val BULK_END_MIN = 16 * 60     // 16:00
    private val BULK_STEP_MIN = 5          // 예: 5분 간격
    // ✅ UNDO용(마지막 1건)
    private var lastInsertedLogId: Long? = null
    private var lastBulkPrevMinute: Int? = null
    private var lastWasBulk: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar = view.findViewById(R.id.toolbar)
        val tb = toolbar!!
        tb.inflateMenu(R.menu.menu_item_tab)

        // ✅ 메뉴에서 SearchView 꺼내기
        val searchItem = tb.menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as androidx.appcompat.widget.SearchView


        searchView.queryHint = "아이템/나라 검색"

        searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                searchQuery = query.orEmpty()
                applyFilter()   // 검색어 바뀔 때마다 필터 다시 적용
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText.orEmpty()
                applyFilter()
                return true
            }
        })


        tb.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_filter_date -> {
                    showDateRangerPicker()
                    true
                }
                R.id.action_clear_filter -> {
                    selectedStartMillis = null
                    selectedEndMillis = null
                    applyFilter()
                    true
                }
                R.id.action_export_excel -> {
                    exportToCsv()
                    true
                }
                R.id.action_add_item_only -> {
                    showAddFromSheetSearchDialog()
                    true
                }
                R.id.action_bulk_start -> {
                    pickBaseDate { day0 ->
                        bulkMode = true
                        bulkBaseDayMillis = day0
                        bulkNextMinute = 9 * 60
                        updateBulkSubtitle()
                        Toast.makeText(requireContext(), "연속 입력 ON (09:00부터)", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.action_bulk_stop -> {
                    bulkMode = false
                    bulkBaseDayMillis = null
                    toolbar?.subtitle = "전체 기간"
                    Toast.makeText(requireContext(), "연속 입력 OFF", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_production_report -> {
                    findNavController().navigate(R.id.reportEntryFragment)
                    true
                }
                else -> false
            }
        }
        updateToolbarSubtitle()

        recycler = view.findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = PlusLogAdapter()
        recycler.adapter = adapter
        // 최근 로그 불러오되, delta > 0 (버튼 + 로 증가한 것만) 필터
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = dao.getRecentPlusClicks(20000) // 필요하면 개수 조절
                .filter { it.delta > 0 }             // ← 핵심: 플러스만 보기
            allRows = rows
            applyFilter()
        }

        lifecycleScope.launch {
            dao.backfillLogSnapshotsFromSheetLines()
        }

    }

    private inner class PlusLogAdapter : RecyclerView.Adapter<VH>() {
        private val data = mutableListOf<ItemCountryDao.QuantityRow>()

        fun submit(list: List<ItemCountryDao.QuantityRow>) {
            data.apply { clear(); addAll(list) }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_added_row, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = data[position]
            val time = fmt.format(Date(r.timestamp))

            val w = r.weight?.takeIf { it > 0f }?.let { " / ${it}kg" } ?: ""
            val p = r.price?.takeIf { it > 0 }?.let { " / ${NumberFormat.getInstance().format(it)}" } ?: ""
            val after = r.fromHave + r.delta
            val changeStr = "${r.fromHave}→$after"


            holder.tv.text = "$time ${r.item} · ${r.country} $changeStr $w$p"

            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                showEditDialog(data[pos])   // ✅ QuantityRow 수정 다이얼로그
            }

            holder.itemView.setOnLongClickListener {
                val realPos = holder.bindingAdapterPosition
                if (realPos == RecyclerView.NO_POSITION) return@setOnLongClickListener true
                val row = data[realPos]

                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("로그 삭제")
                    .setMessage("${row.item} · ${row.country}\n 이 로그를 삭제할까요?")
                    .setPositiveButton("삭제") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                dao.deleteQuantityLogById(row.id)
                                data.removeAt(realPos)
                                notifyItemRemoved(realPos)
                            } catch (e: Exception) {
                            e.printStackTrace()
                            }
                        }
                    }
                    .setNegativeButton("취소", null)
                    .show()
                true
            }

        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.tvRow)
    }

    //툴바 기간
    private fun updateToolbarSubtitle() {
        val tb = toolbar ?: return

        val start = selectedStartMillis
        val end = selectedEndMillis

        tb.subtitle = when {
            start != null && end != null -> {
                val dayFmt = SimpleDateFormat("yy/MM/dd", Locale.getDefault())
                val s = dayFmt.format(Date(start))
                val e = dayFmt.format(Date(end))
                "$s ~ $e"
            }
            start != null && end == null -> {
                val dayFmt = SimpleDateFormat("yy/MM/dd", Locale.getDefault())
                val s = dayFmt.format(Date(start))
                "시작: $s (끝 날짜 선택 전)"
            }
            else -> {
                "전체 기간"
            }
        }
    }

    //추가 다이얼 로그


    private fun showAddFromSheetSearchDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_add_from_sheet_search, null)

        val sv = v.findViewById<androidx.appcompat.widget.SearchView>(R.id.sv)
        val rv = v.findViewById<RecyclerView>(R.id.rv)
        val tvPicked = v.findViewById<TextView>(R.id.tvPicked)
        val tvTime = v.findViewById<TextView>(R.id.tvTime)
        val btnPickTime = v.findViewById<android.widget.Button>(R.id.btnPickTime)
        val etDelta = v.findViewById<android.widget.EditText>(R.id.etDelta)

        var pickedRow: ItemSearchRow? = null
        var pickedMillis: Long? = null

        val pickAdapter = SheetPickAdapter { row ->
            if (bulkMode && bulkBaseDayMillis != null) {
                // ✅ 한 줄 탭 = 바로 저장
                val ts = makeBulkTimestamp()          // 09:00~16:00 자동
                insertLogFromRow(row, ts, bulkDelta)  // DB 저장
                bulkNextMinute = (bulkNextMinute + bulkStepMin).coerceAtMost(16 * 60)
                updateBulkSubtitle()

                // (선택) 선택 표시만 살짝
                tvPicked.text = "저장: ${row.country} / ${row.item} / ${fmt.format(Date(ts))}"

            } else {
                // ✅ bulk 아니면 기존 방식 (선택 → 시간 선택 → 추가 버튼)
                pickedRow = row
                tvPicked.text = "선택: ${row.sheetTitle} / ${row.country} / ${row.item}"
            }
        }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = pickAdapter

        // 초기 목록 로드
        viewLifecycleOwner.lifecycleScope.launch {
            pickAdapter.submit(dao.searchItemsOnce(""))
        }

        fun runSearch(q: String) {
            viewLifecycleOwner.lifecycleScope.launch {
                pickAdapter.submit(dao.searchItemsOnce(q))
            }
        }

        sv.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                runSearch(query.orEmpty())
                hideKeyboard(sv)
                sv.clearFocus()
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                runSearch(newText.orEmpty())
                return true
            }
        })

        btnPickTime.setOnClickListener {
            pickPastDateTime { ms ->
                pickedMillis = ms
                tvTime.text = "시간: ${fmt.format(Date(ms))}"
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("로그 추가(시트 검색)")
            .setView(v)
            .setPositiveButton("추가") { _, _ ->
                val row = pickedRow
                val ts: Long = if (bulkMode && bulkBaseDayMillis != null) {
                    val minute = bulkNextMinute.coerceAtMost(BULK_END_MIN)

                    val cal = Calendar.getInstance().apply {
                        timeInMillis = bulkBaseDayMillis!!
                        set(Calendar.HOUR_OF_DAY, minute / 60)
                        set(Calendar.MINUTE, minute % 60)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    cal.timeInMillis
                } else {
                    pickedMillis ?: run {
                        Toast.makeText(requireContext(), "시간을 선택해줘", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                }

                if (bulkMode) {
                    bulkNextMinute += BULK_STEP_MIN
                    if (bulkNextMinute > BULK_END_MIN) {
                        bulkNextMinute = BULK_END_MIN
                    }
                    updateBulkSubtitle()
                }

                if (row == null) {
                    Toast.makeText(requireContext(), "항목을 선택해줘", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (ts == null) {
                    Toast.makeText(requireContext(), "시간을 선택해줘", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val delta = etDelta.text.toString().toIntOrNull() ?: 1
                if (delta <= 0) {
                    Toast.makeText(requireContext(), "delta는 1 이상", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                viewLifecycleOwner.lifecycleScope.launch {
                    val itemId = dao.getItemIdByName(row.item)
                    val countryId = dao.getCountryIdByName(row.country)

                    if (itemId == null) {
                        Toast.makeText(requireContext(), "아이템을 못 찾음: ${row.item}", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    if (countryId == null) {
                        Toast.makeText(requireContext(), "나라를 못 찾음: ${row.country}", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    val from = dao.getLatestToHave(itemId, countryId) ?: 0
                    val to = from + delta

                    // ✅ bulk면 시간 포인터 증가 전에 백업
                    val wasBulk = bulkMode && bulkBaseDayMillis != null
                    val prevMinute = if (wasBulk) bulkNextMinute else null

                    val newId = dao.insertQuantityLogWithSnapshot(
                        QuantityLogEntity(
                            itemId = itemId,
                            countryId = countryId,
                            fromHave = from,
                            toHave = to,
                            delta = delta,
                            timestamp = ts,
                            archived = 0,
                            batchId = 0L
                        )
                    )

                    // ✅ bulk 시간 포인터는 insert 성공 후 증가(상한 16:00 고정)
                    if (wasBulk) {
                        bulkNextMinute += bulkStepMin
                        if (bulkNextMinute > 16 * 60) bulkNextMinute = 16 * 60
                        updateBulkSubtitle() // 너가 만든 subtitle 갱신 함수
                    }

                    // ✅ UNDO 정보 저장
                    lastInsertedLogId = newId
                    lastWasBulk = wasBulk
                    lastBulkPrevMinute = prevMinute

                    reloadRows()
                    Toast.makeText(
                        requireContext(),
                        "실수면 아래 UNDO로 되돌릴 수 있어",
                        Toast.LENGTH_SHORT
                    ).show()

                    Snackbar.make(
                        requireView(),
                        "추가됨: ${row.country} / ${row.item}",
                        Snackbar.LENGTH_LONG
                    )

                                // ✅ Snackbar + UNDO
                    .setAction("UNDO") {
                            val undoId = lastInsertedLogId ?: return@setAction

                            viewLifecycleOwner.lifecycleScope.launch {
                                try {
                                    dao.deleteQuantityLogById(undoId)

                                    // ✅ bulk면 시간 포인터 롤백
                                    if (lastWasBulk) {
                                        val back = lastBulkPrevMinute
                                        if (back != null) {
                                            bulkNextMinute = back
                                            updateBulkSubtitle()
                                        }
                                    }

                                    lastInsertedLogId = null
                                    lastBulkPrevMinute = null
                                    lastWasBulk = false

                                    reloadRows()
                                    Toast.makeText(requireContext(), "되돌림 완료", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    Toast.makeText(requireContext(), "UNDO 실패", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun hideKeyboard(view: View) {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }
    private fun showEditDialog(row: ItemCountryDao.QuantityRow) {
        val v = layoutInflater.inflate(R.layout.dialog_edit_quantity_log, null)

        val tvHeader = v.findViewById<TextView>(R.id.tvHeader)
        val spItem = v.findViewById<android.widget.Spinner>(R.id.spItem)
        val spCountry = v.findViewById<android.widget.Spinner>(R.id.spCountry)
        val tvTime = v.findViewById<TextView>(R.id.tvTime)
        val btnPickTime = v.findViewById<android.widget.Button>(R.id.btnPickTime)
        val etDelta = v.findViewById<android.widget.EditText>(R.id.etDelta)

        tvHeader.text = "로그 수정: ${row.item} / ${row.country}"

        var pickedMillis = row.timestamp
        tvTime.text = "시간: ${fmt.format(Date(pickedMillis))}"
        etDelta.setText(row.delta.toString())

        btnPickTime.setOnClickListener {
            pickPastDateTime { ms ->
                pickedMillis = ms
                tvTime.text = "시간: ${fmt.format(Date(ms))}"
            }
        }

        // Spinner 데이터 준비(비동기)
        viewLifecycleOwner.lifecycleScope.launch {
            // 1) items 로드 후 spItem 세팅
            val items = dao.getActiveItemsIdName()
            val itemNames = items.map { it.name }
            spItem.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                itemNames
            )

            // 2) 아이템 선택 시 -> 그 아이템에 속한 나라만 로드해서 spCountry 갱신
            fun loadCountriesForSelectedItem(selectCountryName: String? = null) {
                val pickedItemName = spItem.selectedItem?.toString().orEmpty()
                val itemId = items.firstOrNull { it.name == pickedItemName }?.id ?: return

                viewLifecycleOwner.lifecycleScope.launch {
                    val countries = dao.getCountriesForItem(itemId)
                    val countryNames = countries.map { it.name }

                    spCountry.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, countryNames)

                    val idx = selectCountryName?.let { countryNames.indexOf(it) } ?: -1
                    spCountry.setSelection(if (idx >= 0) idx else 0)
                }
            }

// 초기 한번(현재 row.country로 맞춰주기)
            loadCountriesForSelectedItem(row.country)

// 아이템 바뀌면 나라 목록 갱신
            spItem.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                    loadCountriesForSelectedItem(null)
                }
                override fun onNothingSelected(parent: AdapterView<*>) {}
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("수정")
            .setView(v)
            .setPositiveButton("저장") { _, _ ->
                val newDelta = etDelta.text.toString().toIntOrNull() ?: row.delta
                if (newDelta <= 0) {
                    Toast.makeText(requireContext(), "delta는 1 이상", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val pickedItemName = spItem.selectedItem?.toString().orEmpty()
                val pickedCountryName = spCountry.selectedItem?.toString().orEmpty()

                viewLifecycleOwner.lifecycleScope.launch {
                    // 새 조합 기준 weight/price 조회
                    val line = dao.getSheetLineForItemCountry(
                        itemName = pickedItemName,
                        countryName = pickedCountryName
                    )

                    line?.weight
                    line?.price

                    val entity = dao.getQuantityLogById(row.id)
                    if (entity == null) {
                        Toast.makeText(requireContext(), "원본 로그를 찾지 못했어", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    val newItemId = dao.getItemIdByName(pickedItemName)
                    val newCountryId = dao.getCountryIdByName(pickedCountryName)

                    if (newItemId == null) {
                        Toast.makeText(requireContext(), "아이템을 못 찾음: $pickedItemName", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    if (newCountryId == null) {
                        Toast.makeText(requireContext(), "나라를 못 찾음: $pickedCountryName", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    // ✅ 안전하게 from/to 재계산: (해당 조합 최신값) 기반
                    val latest = dao.getLatestToHave(newItemId, newCountryId) ?: 0
                    val from = latest
                    val to = from + newDelta
                    val snap = dao.getSnapshot(newItemId, newCountryId)

                    dao.updateQuantityLog(
                        entity.copy(
                            itemId = newItemId,
                            countryId = newCountryId,
                            timestamp = pickedMillis,
                            delta = newDelta,
                            fromHave = from,
                            toHave = to,
                            itemName = snap?.itemName ?: pickedItemName,
                            countryName = snap?.countryName ?: pickedCountryName,
                            weightAt = snap?.weightAt,
                            priceAt = snap?.priceAt
                        )
                    )

                    Toast.makeText(requireContext(), "수정 완료", Toast.LENGTH_SHORT).show()
                    reloadRows()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun reloadRows() {
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = dao.getRecentPlusClicks(20000)
                .filter { it.delta > 0 }
            allRows = rows
            applyFilter()
        }
    }

    private fun pickPastDateTime(onPicked: (Long) -> Unit) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        val dp = android.app.DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                val chosen = Calendar.getInstance().apply {
                    set(Calendar.YEAR, y)
                    set(Calendar.MONTH, m)
                    set(Calendar.DAY_OF_MONTH, d)
                }

                val tp = android.app.TimePickerDialog(
                    requireContext(),
                    { _, hh, mm ->
                        chosen.set(Calendar.HOUR_OF_DAY, hh)
                        chosen.set(Calendar.MINUTE, mm)
                        chosen.set(Calendar.SECOND, 0)
                        chosen.set(Calendar.MILLISECOND, 0)

                        val picked = chosen.timeInMillis
                        if (picked > now) {
                            Toast.makeText(requireContext(), "미래 시간은 선택할 수 없어요", Toast.LENGTH_SHORT).show()
                            return@TimePickerDialog
                        }
                        onPicked(picked)
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    true
                )
                tp.show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        )
        dp.datePicker.maxDate = now
        dp.show()
    }

    private fun makeBulkTimestamp(): Long {
        val base = bulkBaseDayMillis ?: System.currentTimeMillis()
        val minute = bulkNextMinute.coerceIn(9 * 60, 16 * 60)
        return base + minute * 60_000L
    }

    private fun insertLogFromRow(row: ItemSearchRow, ts: Long, delta: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            val itemId = dao.getItemIdByName(row.item)
            val countryId = dao.getCountryIdByName(row.country)
            if (itemId == null || countryId == null) {
                Toast.makeText(requireContext(), "ID 못찾음: ${row.item} / ${row.country}", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val from = dao.getLatestToHave(itemId, countryId) ?: 0
            val to = from + delta

            dao.insertQuantityLogWithSnapshot(
                QuantityLogEntity(
                    itemId = itemId,
                    countryId = countryId,
                    fromHave = from,
                    toHave = to,
                    delta = delta,
                    timestamp = ts,
                    archived = 0,
                    batchId = 0L
                )
            )
            Toast.makeText(requireContext(), "저장됨 ${fmt.format(Date(ts))}", Toast.LENGTH_SHORT).show()
            reloadRows()
        }
    }

    private fun pickBaseDate(onPicked: (Long) -> Unit) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = now }

        val dp = android.app.DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                val chosen = Calendar.getInstance().apply {
                    set(Calendar.YEAR, y)
                    set(Calendar.MONTH, m)
                    set(Calendar.DAY_OF_MONTH, d)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val picked = chosen.timeInMillis
                if (picked > now) {
                    Toast.makeText(requireContext(), "미래 날짜는 안돼요", Toast.LENGTH_SHORT).show()
                    return@DatePickerDialog
                }
                onPicked(picked)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        )
        dp.datePicker.maxDate = now
        dp.show()
    }

    private fun updateBulkSubtitle() {
        if (!bulkMode || bulkBaseDayMillis == null) return

        val base = Calendar.getInstance().apply {
            timeInMillis = bulkBaseDayMillis!!
            set(Calendar.HOUR_OF_DAY, bulkNextMinute / 60)
            set(Calendar.MINUTE, bulkNextMinute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        toolbar?.subtitle = "연속 입력: ${fmt.format(base.time)}"
    }

    // ✅ 날짜 필터 적용 함수
    private fun applyFilter() {
        val start = selectedStartMillis
        val end = selectedEndMillis

        // 1) 날짜 필터
        val dateFiltered = if (start != null && end != null) {
            allRows.filter { it.timestamp in start..end }
        } else {
            allRows
        }

        // 2) 검색 필터
        val q = searchQuery.trim()
        val finalList = if (q.isNotEmpty()) {
            dateFiltered.filter { row ->
                row.item.contains(q, ignoreCase = true) ||
                        row.country.contains(q, ignoreCase = true)
            }
        } else {
            dateFiltered
        }

        // 3) export용 현재 목록도 동일하게 저장
        currentRows = finalList

        // 4) 화면 갱신 (한 번만)
        adapter.submit(finalList)

        updateToolbarSubtitle()
    }

    // ✅ 날짜 선택 다이얼로그
    private fun showDateRangerPicker() {
        val cal = Calendar.getInstance()
        // 1) 사작 날짜 선택
        val startDialog = android.app.DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val startCal = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth)
                    set(Calendar.MILLISECOND, 0)
                }
                val startMillis = startCal.timeInMillis

                // 2) 끝 날짜 선택
                val endDialog = android.app.DatePickerDialog(
                    requireContext(),
                    { _, eYear, eMonth, eDayOfMonth ->
                        val endCal = Calendar.getInstance().apply {
                            set(eYear, eMonth, eDayOfMonth)
                            set(Calendar.MILLISECOND, 999)
                        }
                        val endMillis = endCal.timeInMillis

                        //기간 저장
                        selectedStartMillis = startMillis
                        selectedEndMillis = endMillis

                        applyFilter()
                    },
                    year, month, dayOfMonth
                )
                endDialog.datePicker.minDate = startMillis
                endDialog.setTitle("끝 날짜 선택")
                endDialog.show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        )
        startDialog.setTitle("시작 날짜 선택")
        startDialog.show()
    }

    private fun exportToCsv() {
        if (currentRows.isEmpty()) {
            Toast.makeText(requireContext(), "로그가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val csv = buildString {
            appendLine("time,item,country,fromHave,after,delta,weight,price")
            currentRows.forEach { r ->
                val time = fmt.format(Date(r.timestamp))
                val after = r.fromHave + r.delta
                val weightStr = r.weight?.toString() ?: ""
                val priceStr = r.price?.toString() ?: ""

                appendLine("$time,${r.item},${r.country},${r.fromHave},$after,${r.delta},$weightStr,$priceStr")
            }
        }

        val fileName = "item_logs_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv"
        val file = File(requireContext().cacheDir, fileName)
        val bom = "\uFEFF"
        file.writeText(bom + csv, Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "엑셀/이메일로 보내기"))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        toolbar = null
        recycler.adapter = null
    }
}