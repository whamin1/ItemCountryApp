package com.bignerdranch.android.myapplication.ui.report

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportDayAdapter(
    private val onClick: (ItemCountryDao.ReportDayAggRow) -> Unit
) : RecyclerView.Adapter<ReportDayAdapter.VH>() {

    private val items = mutableListOf<ItemCountryDao.ReportDayAggRow>()

    fun submit(list: List<ItemCountryDao.ReportDayAggRow>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_report_day, parent, false)
        return VH(v, onClick)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    class VH(v: View, val onClick: (ItemCountryDao.ReportDayAggRow) -> Unit) : RecyclerView.ViewHolder(v) {
        private val tvDate = v.findViewById<TextView>(R.id.tvDay)
        private val tvKg = v.findViewById<TextView>(R.id.tvKg)
        private val tvPrice = v.findViewById<TextView>(R.id.tvPrice)

        private val fmt = SimpleDateFormat("yyyy.MM.dd (E)", Locale.KOREA)

        private val DAY_MS = 86_400_000L
        private val KST_OFFSET_MS = 32_400_000L // +9h

        private fun dayIndexKstToStartMs(dayIndexKst: Long): Long {
            // 쿼리: floor((ts+9h)/day) => dayStartMsKst = dayIndex*day - 9h
            return dayIndexKst * DAY_MS - KST_OFFSET_MS
        }

        fun bind(row: ItemCountryDao.ReportDayAggRow) {
            val dayStartMs = dayIndexKstToStartMs(row.dayIndexKst)
            tvDate.text = fmt.format(Date(dayStartMs))
            tvKg.text = String.format(Locale.KOREA, "%.2f kg", row.totalKg)
            tvPrice.text = String.format(Locale.KOREA, "%,d", row.totalPrice)

            itemView.setOnClickListener { onClick(row) }
        }
    }
}