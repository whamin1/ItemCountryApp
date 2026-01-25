package com.bignerdranch.android.myapplication.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R

class PredictionsAdapter(
    private val onClick: (String) -> Unit,
    private val onLongClick: (Ui) -> Unit,
    private val onReportClick: (Ui) -> Unit

) : RecyclerView.Adapter<PredictionsAdapter.VH>() {

    data class Ui(
        val itemId: Long,
        val item: String,
        val etaMs: Long,
        val label: String,
        val predictedAt: Long
    )

    private var list: List<Ui> = emptyList()

    fun submit(newList: List<Ui>) {
        list = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pred_row, parent, false)
        return VH(v, onClick, onLongClick, onReportClick)
    }

    override fun getItemCount() = list.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(list[position])
    }

    class VH(
        v: View,
        private val onClick: (String) -> Unit,
        private val onLongClick: (Ui) -> Unit,
        private val onReportClick: (Ui) -> Unit
    ) : RecyclerView.ViewHolder(v) {
        private val tvItem = v.findViewById<TextView>(R.id.tvItem)
        private val tvMeta = v.findViewById<TextView>(R.id.tvMeta)
        private val btnReport = v.findViewById<View>(R.id.btnReport)


        fun bind(ui: Ui) {
            tvItem.text = ui.item
            tvMeta.text = "${formatEta(ui.etaMs)} · ${ui.label}"
            itemView.setOnClickListener { onClick(ui.item) }
            itemView.setOnLongClickListener {
                onLongClick(ui); true
            }
            btnReport.setOnClickListener { onReportClick(ui) }
        }

        fun formatEta(ms: Long): String {
            val totalMin = ms / 60_000
            val h = totalMin / 60
            val m = totalMin % 60
            return when {
                h <= 0 -> "${m}분"
                m == 0L -> "${h}시간"
                else -> "${h}시간 ${m}분"
            }
        }
    }
}