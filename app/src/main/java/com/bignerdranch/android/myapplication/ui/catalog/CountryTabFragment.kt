package com.bignerdranch.android.myapplication.ui.catalog

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CountryTabFragment : Fragment(R.layout.fragment_simple_list) { // 간단 리스트 컨테이너

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val rv = view.findViewById<RecyclerView>(R.id.rv)
        rv.layoutManager = LinearLayoutManager(requireContext())
        val adapter = CountryAdapter { country ->
            // 나라 클릭 → 그 나라 저장내역 화면으로 이동
            findNavController().navigate(
                R.id.countryHistoryFragment,
                bundleOf("country" to country)
            )
        }
        rv.adapter = adapter

        // 나라 목록 로드 (Flow -> first())
        viewLifecycleOwner.lifecycleScope.launch {
            val dao = AppDatabase.get(requireContext()).itemCountryDao()
            val countries = dao.getAllCountryNames().first() // List<String>
            adapter.submit(countries)
        }
    }

    private class CountryAdapter(
        val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<VH>() {
        private val data = mutableListOf<String>()
        fun submit(list: List<String>) { data.clear(); data.addAll(list); notifyDataSetChanged() }
        override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int): VH {
            val v = android.view.LayoutInflater.from(p.context).inflate(R.layout.item_simple_row, p, false)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, pos: Int) {
            val name = data[pos]
            h.tv.text = name
            h.itemView.setOnClickListener { onClick(name) }
        }
        override fun getItemCount() = data.size
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.tvRow)
    }
}

