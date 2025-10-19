package com.bignerdranch.android.myapplication.ui.catalog

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ItemTabFragment : Fragment(R.layout.fragment_simple_list) {

    private val dao by lazy { AppDatabase.get(requireContext()).itemCountryDao() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val rv = view.findViewById<RecyclerView>(R.id.rv)
        rv.layoutManager = LinearLayoutManager(requireContext())
        val adapter = ItemOutflowAdapter()
        rv.adapter = adapter


        viewLifecycleOwner.lifecycleScope.launch {
            val since30d = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            val rows = withContext(kotlinx.coroutines.Dispatchers.IO) {
                // ✅ 아카이브 포함 집계
                dao.getArchivedOutflowAllTimeIncludingZero()


            }
            android.util.Log.d("OUTFLOW", "rows=${rows.size}, first=${rows.firstOrNull()}")
            adapter.submit(rows)
        }
    }

    private class ItemOutflowAdapter :
        RecyclerView.Adapter<ItemOutflowAdapter.VH>() {

        private val data = mutableListOf<ItemCountryDao.ItemOutflowRow>()

        fun submit(list: List<ItemCountryDao.ItemOutflowRow>) {
            data.clear(); data.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = android.view.LayoutInflater.from(p.context)
                .inflate(R.layout.item_simple_row, p, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val row = data[pos]
            val absOut = -row.totalOut // 음수 → 양수로 보여주기
            // 0이면 "-0개" 대신 "0개"로 표기
            val text = "${row.item}  →  -${absOut}개"

            h.tv.text = text
        }

        override fun getItemCount() = data.size

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvRow)
        }
    }
}