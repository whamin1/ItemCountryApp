package com.bignerdranch.android.myapplication.ui.history

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import com.bignerdranch.android.myapplication.data.local.entity.SaveSessionEntity
import kotlinx.coroutines.launch

class HistoryDatesFragment : Fragment(R.layout.fragment_history_dates) {
    private val dao by lazy { AppDatabase.get(requireContext()).saveArchiveDao() }

    override fun onViewCreated(v: View, s: Bundle?) {
        val rv = v.findViewById<RecyclerView>(R.id.rvDates)
        rv.layoutManager = LinearLayoutManager(requireContext())
        val adapter = DateAdapter { session ->
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, HistoryDetailFragment.new(session.id))
                .addToBackStack(null)
                .commit()
        }
        rv.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            adapter.submit(dao.getSessions()) // 최신이 위
        }
    }

    private inner class DateAdapter(
        val onClick: (SaveSessionEntity) -> Unit
    ) : RecyclerView.Adapter<VH>() {
        private val data = mutableListOf<SaveSessionEntity>()
        fun submit(list: List<SaveSessionEntity>) { data.clear(); data.addAll(list); notifyDataSetChanged() }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(layoutInflater.inflate(R.layout.item_date_row, p, false))
        override fun getItemCount() = data.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val s = data[i]
            h.tv.text = s.title // "2025-10-09 17:20 저장"
            h.itemView.setOnClickListener { onClick(s) }
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.tvRow)
    }
}
