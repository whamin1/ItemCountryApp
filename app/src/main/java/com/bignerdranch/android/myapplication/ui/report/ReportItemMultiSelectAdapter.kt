package com.bignerdranch.android.myapplication.ui.report

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckedTextView
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R

class ReportItemMultiSelectAdapter(
    allItems: List<String>,
    initialSelected: Set<String>,
    private val maxSel: Int = 10,
    private val onSelectionChanged: (Set<String>) -> Unit,
    private val onOverLimit: () -> Unit
) : RecyclerView.Adapter<ReportItemMultiSelectAdapter.VH>() {

    private val selected = initialSelected.toMutableSet()
    private val origin = allItems
    private var filtered = allItems

    fun filter(q: String) {
        val t = q.trim()
        filtered = if (t.isEmpty()) origin
        else origin.filter { matchesQuery(it, t) }
        notifyDataSetChanged()
    }

    fun getSelected(): Set<String> = selected.toSet()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.row_check_item, parent, false)
        return VH(v as CheckedTextView)
    }

    override fun getItemCount(): Int = filtered.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val name = filtered[position]
        holder.ctv.text = name
        holder.ctv.isChecked = selected.contains(name)

        holder.ctv.setOnClickListener {
            val isChecked = selected.contains(name)
            if (isChecked) {
                selected.remove(name)
                holder.ctv.isChecked = false
            } else {
                if (selected.size >= maxSel) {
                    onOverLimit()
                    return@setOnClickListener
                }
                selected.add(name)
                holder.ctv.isChecked = true
            }
            onSelectionChanged(getSelected())
        }
    }

    class VH(val ctv: CheckedTextView) : RecyclerView.ViewHolder(ctv)

    private val CHO = charArrayOf(
        'ㄱ','ㄲ','ㄴ','ㄷ','ㄸ','ㄹ','ㅁ','ㅂ','ㅃ','ㅅ','ㅆ','ㅇ','ㅈ','ㅉ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ'
    )

    private fun isChosungChar(c: Char): Boolean = c in CHO

    private fun isOnlyChosung(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        return t.all { isChosungChar(it) }
    }

    private fun toChosungString(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val code = ch.code
            if (code in 0xAC00..0xD7A3) {
                val idx = (code - 0xAC00) / (21 * 28)
                sb.append(CHO[idx])
            } else {
                // 한글 음절이 아니면 그대로 넣지 말고 스킵하는 편이 깔끔
                // sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * 초성/일반 혼합 검색 매칭
     */
    private fun matchesQuery(name: String, q: String): Boolean {
        val query = q.trim()
        if (query.isEmpty()) return true

        // 1) 일반 contains (한글/영문/숫자 모두)
        if (name.contains(query, ignoreCase = true)) return true

        // 2) 초성 전용(ㄱㅅㅈ) 검색이면 초성끼리 비교
        if (isOnlyChosung(query)) {
            val choName = toChosungString(name)
            return choName.contains(query)
        }

        // 3) 섞여있으면: 초성만 뽑아 비교도 한 번(체감용)
        val onlyCho = query.filter { isChosungChar(it) }
        if (onlyCho.isNotEmpty()) {
            val choName = toChosungString(name)
            if (choName.contains(onlyCho)) return true
        }

        return false
    }
}