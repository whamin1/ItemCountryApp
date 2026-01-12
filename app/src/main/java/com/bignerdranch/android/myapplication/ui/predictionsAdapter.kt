package com.bignerdranch.android.myapplication.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R

class PredictionsAdapter(
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<PredictionsAdapter.VH>() {

    data class Ui(
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
        return VH(v, onClick)
    }

    override fun getItemCount() = list.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(list[position])
    }

    class VH(
        v: View,
        private val onClick: (String) -> Unit
    ) : RecyclerView.ViewHolder(v) {
        private val tvItem = v.findViewById<TextView>(R.id.tvItem)
        private val tvMeta = v.findViewById<TextView>(R.id.tvMeta)

        fun bind(ui: Ui) {
            tvItem.text = ui.item
            tvMeta.text = "${formatEta(ui.etaMs)} · ${ui.label}"
            itemView.setOnClickListener { onClick(ui.item) }
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