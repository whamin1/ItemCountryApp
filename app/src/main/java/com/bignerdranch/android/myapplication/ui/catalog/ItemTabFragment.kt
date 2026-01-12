package com.bignerdranch.android.myapplication.ui.catalog

import android.content.Intent
import android.icu.text.NumberFormat
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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
            val total = data.filter { it.item == r.item && it.country == r.country }
                .sumOf { it.delta }


            holder.tv.text = "$time ${r.item} · ${r.country} $changeStr $w$p"

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