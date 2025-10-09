package com.bignerdranch.android.myapplication.ui.history

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import com.bignerdranch.android.myapplication.data.local.entity.SaveSessionLineEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryDetailFragment : Fragment(R.layout.fragment_history_detail) {
    companion object {
        fun new(sessionId: Long) = HistoryDetailFragment().apply {
            arguments = bundleOf("sessionId" to sessionId)
        }
    }
    private val dao by lazy { AppDatabase.get(requireContext()).saveArchiveDao() }

    override fun onViewCreated(v: View, s: Bundle?) {
        val sessionId = requireArguments().getLong("sessionId")
        val rv = v.findViewById<RecyclerView>(R.id.rvDetail)
        rv.layoutManager = LinearLayoutManager(requireContext())
        val adapter = DetailAdapter()
        rv.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            // 1) 라인 로드
            val lines = dao.getLines(sessionId)

            // 2) batchId(회차)별로 묶고 최신이 위로 오도록
            val grouped = lines.groupBy { it.batchId }
            val normalKeys = grouped.keys.filter { it != 0L }.sortedDescending()
            val keysInOrder = if (grouped.containsKey(0L)) normalKeys + 0L else normalKeys

            val sections = keysInOrder.mapIndexed { idx, key ->
                val list = grouped[key]!!.sortedByDescending { it.timestamp }
                val no = if (key == 0L) 0 else (normalKeys.size - idx) // 위쪽 최신이 큰 회차
                val caption = if (key == 0L) "배치 없음" else "${no}차"
                Section(
                    title = caption,
                    items = list
                )
            }

            adapter.submit(sections)
        }
    }

    data class Section(val title: String, val items: List<SaveSessionLineEntity>)

    private inner class DetailAdapter : RecyclerView.Adapter<VH>() {
        private val data = mutableListOf<Section>()
        fun submit(list: List<Section>) { data.clear(); data.addAll(list); notifyDataSetChanged() }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(layoutInflater.inflate(R.layout.item_added_row, p, false))
        override fun getItemCount() = data.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val sec = data[pos]
            val sb = StringBuilder("【${sec.title}】\n")
            sec.items.forEach { r ->
                val time = SimpleDateFormat("yy/MM/dd HH:mm", Locale.getDefault()).format(Date(r.timestamp))
                sb.append(" - $time  ${r.item}·${r.country}  ${r.fromHave} → ${r.toHave}  (＋${r.delta})\n")
            }
            h.tv.text = sb.toString().trimEnd()
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.tvRow)
    }
}
