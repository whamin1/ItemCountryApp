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

class ReportItemLogAdapter : RecyclerView.Adapter<ReportItemLogAdapter.VH>() {

    private val items = mutableListOf<ItemCountryDao.ItemReport.LogRow>()
    private val tf = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA)

    fun submit(list: List<ItemCountryDao.ItemReport.LogRow>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_report_item_log, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], tf)

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvTime = v.findViewById<TextView>(R.id.tvTime)
        private val tvCountry = v.findViewById<TextView>(R.id.tvCountry)
        private val tvDuration = v.findViewById<TextView>(R.id.tvDuration)
        private val tvSpeed = v.findViewById<TextView>(R.id.tvSpeed)

        private val tvKg = v.findViewById<TextView>(R.id.tvKg)

        fun bind(row: ItemCountryDao.ItemReport.LogRow, tf: SimpleDateFormat) {
            tvTime.text = tf.format(Date(row.ts))
            tvCountry.text = row.country.ifBlank { "(unknown)" }

            tvKg.text = if (row.weightKg != null)
                String.format(Locale.KOREA, "무게: %.2f kg", row.weightKg)
            else "무게: -"

            tvDuration.text = "걸린시간: ${formatDuration(row.workGapMs)}"

            tvSpeed.text = if (row.speedKgPerHour != null)
                String.format(Locale.KOREA, "속도: %.2f kg/h", row.speedKgPerHour)
            else "속도: -"
        }
        private fun formatDuration(ms: Long): String {
            val totalMin = ms / 60_000
            val h = totalMin / 60
            val m = totalMin % 60
            return if (h > 0) "${h}시간 ${m}분" else "${m}분"
        }
    }
}