// AddedActivity.kt (전체를 이걸로 교체해도 됨)
package com.bignerdranch.android.myapplication.ui

import android.os.Bundle
import android.provider.ContactsContract
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class AddedActivity : AppCompatActivity() {
    private val fmt = SimpleDateFormat("yy/MM/dd HH:mm", Locale.getDefault())
    val dao = AppDatabase.get(this).itemCountryDao()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_added)

        findViewById<TextView>(R.id.tvTitle).text = "수량 변경 기록"
        val rv = findViewById<RecyclerView>(R.id.rvAdded)
        rv.layoutManager = LinearLayoutManager(this)
        val adapter = QuantityLogAdapter()
        rv.adapter = adapter

        lifecycleScope.launch {
            val rows = dao.getRecentQuantityLogs(200)   // ← 새로 만든 쿼리 호출
            adapter.submit(rows)
        }
    }

    private inner class QuantityLogAdapter : RecyclerView.Adapter<VH>() {
        private val data = mutableListOf<ItemCountryDao.QuantityRow>()
        fun submit(list: List<ItemCountryDao.QuantityRow>) {
            data.apply { clear(); addAll(list) }
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(p: android.view.ViewGroup, v: Int) =
            VH(layoutInflater.inflate(R.layout.item_added_row, p, false))
        override fun getItemCount() = data.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = data[pos]
            // 예: 25/09/09 12:34   사과 · 한국   1 → 3  (+2)
            h.tv.text = "${fmt.format(Date(r.timestamp))}   ${r.item} · ${r.country}   ${r.fromHave} → ${r.toHave}  (＋${r.delta})"

            h.itemView.setOnLongClickListener {
                AlertDialog.Builder(this@AddedActivity)
                    .setTitle("로그 삭제")
                    .setMessage("이 기록을 삭제할까요? \n(${fmt.format(Date(r.timestamp))} ${r.item} ${r.country}")
                    .setPositiveButton("삭제") { d, _ ->
                        lifecycleScope.launch {
                            dao.deleteQuantityLogById(r.id)
                            val idx = h.bindingAdapterPosition
                            if (idx != RecyclerView.NO_POSITION) {
                                data.removeAt(idx)
                                notifyItemRemoved(idx)
                            }
                        }
                        d.dismiss()
                    }
                    .setNegativeButton("취소") { d, _ -> d.dismiss() }
                    .show()
                true
            }
        }
    }

    private class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.tvRow)
    }
}
