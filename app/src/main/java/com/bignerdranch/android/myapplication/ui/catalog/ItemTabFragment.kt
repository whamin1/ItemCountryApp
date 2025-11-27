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
    private var selectedDayMillis: Long? = null
    private var currentRows: List<ItemCountryDao.QuantityRow> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar: MaterialToolbar = view.findViewById(R.id.toolbar)
        toolbar.inflateMenu(R.menu.menu_item_tab)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_filter_date -> {
                    showDatePicker()
                    true
                }
                R.id.action_clear_filter -> {
                    selectedDayMillis = null
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

        recycler = view.findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = PlusLogAdapter()
        recycler.adapter = adapter
        // 최근 로그 불러오되, delta > 0 (버튼 + 로 증가한 것만) 필터
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = dao.getRecentPlusClicks(300) // 필요하면 개수 조절
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

    // ✅ 날짜 필터 적용 함수
    private fun applyFilter() {
        val listToShow = selectedDayMillis?.let { dayMillis ->
            // dayMillis 기준으로 그 날의 0시 ~ 23:59:59 계산
            val cal = Calendar.getInstance().apply {
                timeInMillis = dayMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 1)
            val end = cal.timeInMillis - 1

            allRows.filter { it.timestamp in start..end }
        } ?: allRows

        currentRows = listToShow

        adapter.submit(listToShow)
    }

    // ✅ 날짜 선택 다이얼로그
    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        val dialog = android.app.DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                // 선택한 날짜의 0시 기준 millis 저장
                val c = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                selectedDayMillis = c.timeInMillis
                applyFilter()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        )
        dialog.show()
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
}