package com.bignerdranch.android.myapplication.ui.report

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import java.util.Locale

class ReportItemAdapter(
    private val onClick: (ItemCountryDao.ReportItemAggRow) -> Unit
) : RecyclerView.Adapter<ReportItemAdapter.VH>() {

    private val items = mutableListOf<ItemCountryDao.ReportItemAggRow>()
    private var totalKgAll: Double = 0.0

    fun submit(list: List<ItemCountryDao.ReportItemAggRow>) {
        items.clear()
        items.addAll(list)
        totalKgAll = items.sumOf { it.totalKg }.let { if (it <= 0.0) 1.0 else it }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_report_item, parent, false)
        return VH(v, onClick)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], totalKgAll)
    }

    class VH(v: View, val onClick: (ItemCountryDao.ReportItemAggRow) -> Unit) : RecyclerView.ViewHolder(v) {
        private val tvTitle = v.findViewById<TextView>(R.id.tvTitle)
        private val tvCount = v.findViewById<TextView>(R.id.tvCount)
        private val tvKg    = v.findViewById<TextView>(R.id.tvKg)
        private val tvRatio = v.findViewById<TextView>(R.id.tvRatio)
        private val tvPrice = v.findViewById<TextView>(R.id.tvPrice)

        fun bind(row: ItemCountryDao.ReportItemAggRow, totalKgAll: Double) {
            val ratio = (row.totalKg / totalKgAll) * 100.0

            tvTitle.text = row.item
            tvCount.text = String.format(Locale.KOREA, "%,d", row.totalDelta)
            tvKg.text    = String.format(Locale.KOREA, "%,.2f kg", row.totalKg)
            tvRatio.text = String.format(Locale.KOREA, "%.1f%%", ratio)
            tvPrice.text = String.format(Locale.KOREA, "%,d", row.totalPrice) // 달러지만 기호 없이

            itemView.setOnClickListener { onClick(row) }
        }
    }
}