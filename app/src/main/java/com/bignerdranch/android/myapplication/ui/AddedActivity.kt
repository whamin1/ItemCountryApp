package com.bignerdranch.android.myapplication.ui

import android.os.Bundle
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
    private val dao by lazy { AppDatabase.get(this).itemCountryDao() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_added)

        findViewById<TextView>(R.id.tvTitle).text = "수량 변경 기록"
        val rv = findViewById<RecyclerView>(R.id.rvAdded)
        rv.layoutManager = LinearLayoutManager(this)
        val adapter = QuantityBatchAdapter()
        rv.adapter = adapter

        loadAndSubmit(adapter)


    }

    private fun loadAndSubmit(adapter: QuantityBatchAdapter){
        lifecycleScope.launch {
            val rows: List<ItemCountryDao.HistoryRow> = dao.getHistoryRowsNoDelta(200)   // ← 새로 만든 쿼리 호출
            val batches = buildBatches(rows)
            adapter.submit(batches)
        }
    }

    // ---- 배치 모델/빌더 ----
    data class CountryBatch(
        val country: String,
        val no: Int,                     // 1차, 2차…
        val label: String,               // 시간 라벨 // 배치 내 delta 합
        val items: List<ItemCountryDao.HistoryRow>,
        val totalCount: Int
    )

    private fun buildBatches(rows: List<ItemCountryDao.HistoryRow>): List<CountryBatch> {
        if (rows.isEmpty()) return emptyList()

        val byCountry = rows.groupBy { it.country }

        val result = mutableListOf<CountryBatch>()

        byCountry.forEach { (country, listForCountry) ->
            // batchId로 묶기 (0은 '배치 없음'으로 취급)
            val grouped = listForCountry.groupBy { it.batchId }

            // 1) 0L(배치 없음)는 제일 아래로 보내고,
            // 2) 정상 배치는 최신(batchId 큰 값)부터 위로 오게 내림차순 정렬
            val normalKeys = grouped.keys.filter { it != 0L }.sortedDescending()
            val keysInOrder = if (grouped.containsKey(0L)) normalKeys + 0L else normalKeys

            val totalBatches = normalKeys.size // 번호를 매길 대상(배치 없음 제외)

            keysInOrder.forEachIndexed { idx, key ->
                val items = grouped[key]!!.sortedByDescending { it.timestamp }
                val newest = items.first()
                val oldest = items.last()

                val timeLabel =
                    if (fmt.format(Date(newest.timestamp)) == fmt.format(Date(oldest.timestamp))) {
                        fmt.format(Date(newest.timestamp))
                    } else {
                        "${fmt.format(Date(oldest.timestamp))} ~ ${fmt.format(Date(newest.timestamp))}"
                    }

                // 최신이 화면 위에 오지만, 번호는 오래된 게 1차, 최신일수록 큰 번호가 되도록!
                val no = if (key == 0L) 0 else (totalBatches - idx)

                val caption = if (key == 0L) "배치 없음"
                else "${no}차"

                result += CountryBatch(
                    country = country,
                    no = no,
                    label = "$country · $caption · $timeLabel",
                    items = items,
                    totalCount = items.size
                )
            }
        }

        return result.sortedByDescending { it.items.first().timestamp }
    }

    private inner class QuantityBatchAdapter : RecyclerView.Adapter<VH>() {
        private val data = mutableListOf<CountryBatch>()
        fun submit(list: List<CountryBatch>) {
            data.clear(); data.addAll(list);notifyDataSetChanged()
        }

        override fun onCreateViewHolder(p: android.view.ViewGroup, v: Int) =
            VH(layoutInflater.inflate(R.layout.item_added_row, p, false))

        override fun getItemCount() = data.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val b = data[pos]
            val sb = StringBuilder()
            sb.append("【${b.label}】 합계 +${b.totalCount}\n")
            b.items.forEach { r ->
                val diff = r.toHave - r.fromHave        // ✅ delta 없이 실시간 계산
                val sign = when {
                    diff > 0 -> "＋"
                    diff < 0 -> "−"
                    else -> "±"
                }
                // 예: 25/09/25 10:30  사과·한국  1 → 3 (+2)
                sb.append(" - ${fmt.format(Date(r.timestamp))}  ${r.item}·${r.country}  ${r.fromHave} → ${r.toHave}  (＋${diff})\n")
            }
            h.tv.text = sb.toString().trimEnd()

            h.itemView.setOnLongClickListener {
                val labels = b.items.map { r ->
                    val diff = r.toHave - r.fromHave        // ✅ delta 없이 실시간 계산
                    val sign = when {
                        diff > 0 -> "＋"
                        diff < 0 -> "−"
                        else -> "±"
                    }
                    "${fmt.format(Date(r.timestamp))}  ${r.item}·${r.country}  ${r.fromHave} → ${r.toHave}  (＋${diff})"
                }.toTypedArray()

                AlertDialog.Builder(this@AddedActivity)
                    .setTitle("${b.no}차에서 삭제할 항목 선택")
                    .setItems(labels) { d, which ->
                        val target = b.items[which]
                        lifecycleScope.launch {
                            dao.deleteQuantityLogById(target.id)
                            // 최신 상태 다시 로드해서 갱신 (인덱스 계산 실수 방지)
                        loadAndSubmit(this@QuantityBatchAdapter)
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