package com.bignerdranch.android.myapplication.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao

class ItemReportAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val list = mutableListOf<ItemCountryDao.ItemReportUi>()

    fun submit(newList: List<ItemCountryDao.ItemReportUi>) {
        list.clear()
        list.addAll(newList)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (list[position]) {
        is ItemCountryDao.ItemReportUi.Header -> 0
        is ItemCountryDao.ItemReportUi.LogRow -> 1
        is ItemCountryDao.ItemReportUi.PredictionRow -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderVH(inf.inflate(R.layout.row_report_header, parent, false))
            1 -> LogVH(inf.inflate(R.layout.row_report_log, parent, false))
            else -> PredVH(inf.inflate(R.layout.row_report_pred, parent, false))
        }
    }

    override fun getItemCount() = list.size

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        when (val ui = list[pos]) {
            is ItemCountryDao.ItemReportUi.Header -> (h as HeaderVH).bind(ui)
            is ItemCountryDao.ItemReportUi.LogRow -> (h as LogVH).bind(ui)
            is ItemCountryDao.ItemReportUi.PredictionRow -> (h as PredVH).bind(ui)
        }
    }

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tv = v.findViewById<TextView>(R.id.tvTitle)
        fun bind(ui: ItemCountryDao.ItemReportUi.Header) { tv.text = ui.title }
    }

    class LogVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tv = v.findViewById<TextView>(R.id.tv)
        fun bind(ui: ItemCountryDao.ItemReportUi.LogRow) {
            tv.text =
                "${ui.time} | ${ui.country} | ${ui.weight} | ${ui.speed} | ${ui.duration}"
        }
    }

    class PredVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tv = v.findViewById<TextView>(R.id.tv)
        fun bind(ui: ItemCountryDao.ItemReportUi.PredictionRow) {
            tv.text = "${ui.time} · ${ui.label}"
        }
    }
}