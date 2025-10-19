package com.bignerdranch.android.myapplication.ui.catalog

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import com.bignerdranch.android.myapplication.data.local.entity.SaveSessionEntity
import com.bignerdranch.android.myapplication.ui.history.HistoryDetailFragment
import kotlinx.coroutines.launch

class CountryHistoryFragment : Fragment(R.layout.fragment_country_history) {

    companion object {
        fun new(countryName: String) = CountryHistoryFragment().apply {
            arguments = bundleOf("country" to countryName)
        }
    }

    private val archiveDao by lazy { AppDatabase.get(requireContext()).saveArchiveDao() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val country = requireArguments().getString("country") ?: return

        val rv = view.findViewById<RecyclerView>(R.id.rvHistory)
        rv.layoutManager = LinearLayoutManager(requireContext())
        val adapter = SessionAdapter { session ->
            // 세션(날짜) 클릭 → 그때의 상세 기록 화면으로
            findNavController().navigate(
                R.id.historyDetailFragment, bundleOf("sessionId" to session.id)
            )
        }
        rv.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            val sessions = archiveDao.getSessionsByCountry(country)  // 최신이 위
            adapter.submit(sessions)
        }
    }

    private class SessionAdapter(
        val onClick: (SaveSessionEntity) -> Unit
    ) : RecyclerView.Adapter<SessionAdapter.VH>() {
        private val data = mutableListOf<SaveSessionEntity>()

        fun submit(list: List<SaveSessionEntity>) {
            data.clear(); data.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = android.view.LayoutInflater.from(p.context).inflate(R.layout.item_added_row, p, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = data[pos]
            // s.title 에 "한국 · 2025-10-09 17:20" 처럼 저장해두었죠
            h.tv.text = s.title
            h.itemView.setOnClickListener { onClick(s) }
        }

        override fun getItemCount() = data.size

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvRow)
        }
    }
}
