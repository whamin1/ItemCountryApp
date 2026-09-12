package com.bignerdranch.android.myapplication.ui.report

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import java.text.NumberFormat
import java.text.Collator
import java.util.Locale

class ReportItemAggAdapter(
    private val onClick: (ItemCountryDao.ReportItemAggRow) -> Unit
) : RecyclerView.Adapter<ReportItemAggAdapter.VH>() {

    private var totalKgAll: Double = 0.0
    private val all = mutableListOf<ItemCountryDao.ReportItemAggRow>()
    private val shown = mutableListOf<ItemCountryDao.ReportItemAggRow>()

    fun submit(list: List<ItemCountryDao.ReportItemAggRow>) {
        val collator = Collator.getInstance(Locale.KOREA)
        val sorted = list.sortedWith { left, right ->
            collator.compare(right.item, left.item)
        }
        all.clear()
        all.addAll(sorted)
        shown.clear()
        shown.addAll(sorted)
        totalKgAll = list.sumOf { it.totalKg }
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        shown.clear()
        if (query.isBlank()) {
            shown.addAll(all)
        } else {
            val q = query.trim()
            shown.addAll(all.filter {
                it.item.contains(q, ignoreCase = true)
            })
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_report_item_agg, parent, false)
        return VH(v, onClick)
    }

    override fun getItemCount() = shown.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(shown[position], totalKgAll)
    }

    class VH(v: View, private val onClick: (ItemCountryDao.ReportItemAggRow) -> Unit) :
        RecyclerView.ViewHolder(v) {

        private val tvItem = v.findViewById<TextView>(R.id.tvItem)
        private val tvCount = v.findViewById<TextView>(R.id.tvCount)
        private val tvKg = v.findViewById<TextView>(R.id.tvKg)
        private val tvRatio = v.findViewById<TextView>(R.id.tvRatio)
        private val tvPrice = v.findViewById<TextView>(R.id.tvPrice)
        private val btn = v.findViewById<ImageButton>(R.id.btnDetail)

        private val nf: NumberFormat = NumberFormat.getInstance(Locale.KOREA)

        fun bind(row: ItemCountryDao.ReportItemAggRow, totalKgAll: Double) {

            tvItem.text = row.item.ifBlank { "(unknown)" }
            tvCount.text = "갯수: ${nf.format(row.totalDelta)}"
            tvKg.text = String.format(Locale.KOREA, "무게: %.2f kg", row.totalKg)

            val ratio = if (totalKgAll > 0) (row.totalKg / totalKgAll) * 100.0 else 0.0
            tvRatio.text = String.format(Locale.KOREA, "비율: %.1f%%", ratio)

            // 달러지만 기호 없이 숫자만 + 콤마
            tvPrice.text = "가격: ${nf.format(row.totalPrice)}"

            itemView.setOnClickListener { onClick(row) }
            btn.setOnClickListener { onClick(row) }
        }
    }
}
