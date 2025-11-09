package com.bignerdranch.android.myapplication.ui.catalog

import android.icu.text.NumberFormat
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import kotlinx.coroutines.launch
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recycler = view.findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = PlusLogAdapter()
        recycler.adapter = adapter

        // 최근 로그 불러오되, delta > 0 (버튼 + 로 증가한 것만) 필터
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = dao.getRecentPlusClicks(300) // 필요하면 개수 조절
                .filter { it.delta > 0 }             // ← 핵심: 플러스만 보기
            adapter.submit(rows)
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
            val total = data.filter { it.item == r.item && it.country == r.country }
                .sumOf { it.delta }

            val deltaStr = if (r.delta > 0) " +${r.delta}" else if (r.delta < 0) " ${r.delta}" else ""

            val totalStr = if (total != 0) "${if (total > 0) "+" else ""}$total" else ""

            holder.tv.text = "$time ${r.item} · ${r.country} ${r.fromHave}$totalStr $w$p"
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.tvRow)
    }
}