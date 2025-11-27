package com.bignerdranch.android.myapplication

import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.repository.ItemCountryRepository
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

data class Row(val name: String, val needed: Int, val have: Int)

/**
 * 기능:
 * - 검색어 하이라이트 (굵게 + 색상)
 * - 섹션 헤더(가나다/ABC) 자동 생성
 * - 클릭 시 MainActivity로 (head, rows) 통째로 전달 → 거기서 단건/다건 처리
 */
class EntryAdapter(
    private val onItemLongClick: (head: String, list: List<String>) -> Unit,
    private val onPickRows: (head: String, rows: List<Row>) -> Unit,
    private val onDelta: (head: String, row: Row, delta: Int) -> Unit   // 👈 추가: -/+ 클릭 이벤트

) : RecyclerView.Adapter<EntryAdapter.EntryViewHolder>() {

    private data class SectionRange(val start: Int, val end: Int)
    private val sectionRanges = mutableMapOf<String, SectionRange>()


    // ----- 섹션 키 생성(가나다/ABC) -----
    private val CHO = charArrayOf(
        'ㄱ','ㄲ','ㄴ','ㄷ','ㄸ','ㄹ','ㅁ','ㅂ','ㅃ','ㅅ','ㅆ','ㅇ','ㅈ','ㅉ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ'
    )
    private fun sectionKeyOf(text: String): String {
        if (text.isBlank()) return "#"
        val c = text.first()
        return when {
            c in 'A'..'Z' || c in 'a'..'z' -> c.uppercaseChar().toString()
            c in '0'..'9' -> c.toString()
            c in '\uAC00'..'\uD7A3' -> { // 한글 완성형
                val idx = ((c.code - 0xAC00) / (21 * 28))
                CHO.getOrNull(idx)?.toString() ?: "#"
            }
            else -> "#"
        }
    }

    private var tempHave: Map<Pair<String, String>, Int> = emptyMap()
    private var tempIsCountryMode: Boolean = false

    fun setTempSnapshot(
        pending: Map<Pair<String, String>, Pair<Int, Int>>, // (item,country)->(needed,have)
        isCountryMode: Boolean
    ) {
        tempIsCountryMode = isCountryMode
        // pending에서 have만 꺼내서 보관
        tempHave = pending.mapValues { it.value.second }
        notifyDataSetChanged()
    }
    // ----- 원본/표시 데이터 -----
    private var full: List<Pair<String, List<Row>>> = emptyList()   // 원본(검색용)
    private var items: MutableList<Any> = mutableListOf()           // HeaderRow / GroupRow (표시용)
    private val sectionFirstPos = mutableMapOf<String, Int>()       // 섹션 첫 위치(빠른 점프용)

    // 검색어 저장(하이라이트 용)
    private var currentQuery: String = ""

    // ----- 타입/데이터 모델 -----
    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_GROUP  = 1
    }

    // 섹션 헤더 여부를 어댑터가 대신 판단
    fun isHeader(position: Int): Boolean =
        position in 0 until items.size && (items[position] is HeaderRow)

    // 섹션 헤더 타이틀을 어댑터가 대신 꺼내줌
    fun headerTitleAt(position: Int): String =
        (items[position] as HeaderRow).title

    // StickyHeaderDecoration 에서 바로 쓰기 좋은 바인드 함수
    fun bindHeaderView(view: View, position: Int) {
        val title = headerTitleAt(position)
        view.findViewById<TextView>(R.id.tvSec).text = title
    }

    private data class HeaderRow(val title: String)
    private data class GroupRow(val head: String, val rows: List<Row>)

    // ----- 외부에서 데이터 주입 -----
    fun submitData(map: Map<String, List<ItemCountryRepository.CountryQty>>) {
        full = map.entries.map { e ->
            e.key to e.value.map { cq -> Row(cq.name, cq.needed, cq.have) }
        }
        rebuildSections(full, currentQuery)
    }

    // ----- 필터링 + 섹션 재구성 -----
    fun filter(query: String) {
        currentQuery = query
        rebuildSections(full, query)
    }

    private fun rebuildSections(
        src: List<Pair<String, List<Row>>>,
        query: String
    ) {
        val filtered = if (query.isBlank()) src else {
            src.filter { (head, rows) ->
                head.contains(query, ignoreCase = true) ||
                        rows.any { it.name.contains(query, ignoreCase = true) }
            }
        }

        val grouped = filtered.groupBy { sectionKeyOf(it.first) }.toSortedMap()

        items.clear()
        sectionFirstPos.clear()
        sectionRanges.clear()

        var pos = 0
        grouped.forEach { (sec, pairs) ->
            items.add(HeaderRow(sec))
            sectionFirstPos[sec] = pos
            pos++

            val startPos = pos

            pairs.sortedBy { it.first }.forEach { (head, rows) ->
                items.add(GroupRow(head, rows))
                pos++
            }

            val endPos = pos - 1

            if (endPos >= startPos) {
                sectionRanges[sec] = SectionRange(startPos, endPos)
            }
        }
        notifyDataSetChanged()
    }

    // (선택) 인덱스 점프용 API

    fun positionOfSection(section: String): Int? = sectionFirstPos[section]
    fun availableSections(): List<String> = sectionFirstPos.keys.sorted()
    // 섹션 안에서 ratio(0.0f~1.0f) 비율에 해당하는 위치 반환
    fun positionOfSection(section: String, ratioInSection: Float): Int? {
        val range = sectionRanges[section] ?: return null
        val r = ratioInSection.coerceIn(0f, 1f)

        // start ~ end 사이에서 비율만큼 이동
        val pos = range.start + ((range.end - range.start) * r).toInt()
        return pos.coerceIn(range.start, range.end)
    }
    // ----- RecyclerView 기본 -----
    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        if (items[position] is HeaderRow) TYPE_HEADER else TYPE_GROUP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
        return if (viewType == TYPE_HEADER) {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_section_header, parent, false)
            HeaderVH(v)
        } else {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_entry, parent, false)
            GroupVH(v, onItemLongClick, onPickRows)
        }
    }

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        when (holder) {
            is HeaderVH -> holder.bind((items[position] as HeaderRow).title)
            is GroupVH  -> {
                val g = items[position] as GroupRow
                holder.bind(g.head, g.rows, currentQuery)
            }
        }
    }

    // ----- 공통 뷰홀더 -----
    open class EntryViewHolder(v: View) : RecyclerView.ViewHolder(v)

    // 섹션 헤더
    private class HeaderVH(v: View) : EntryViewHolder(v) {
        private val tv: TextView = v.findViewById(R.id.tvSec)
        fun bind(title: String) { tv.text = title }
    }


    // ----- 하이라이트 유틸 -----
    private fun highlight(text: String, query: String): CharSequence {
        if (query.isBlank()) return text
        val start = text.indexOf(query, ignoreCase = true)
        if (start < 0) return text
        return SpannableString(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), start, start + query.length, 0)
            setSpan(ForegroundColorSpan(0xFF3F51B5.toInt()), start, start + query.length, 0)
        }
    }

    private inner class GroupVH(
        v: View,
        private val onItemLongClick: (head: String, list: List<String>) -> Unit,
        private val onPickRows: (head: String, rows: List<Row>) -> Unit
    ) : EntryViewHolder(v) {

        private val inflater = LayoutInflater.from(v.context)
        private val chipGroup: ChipGroup = v.findViewById(R.id.chipGroupRows)
        private val tvHead: TextView = v.findViewById(R.id.tvHead)

        fun bind(head: String, rows: List<Row>, q: String) {
            tvHead.text = highlight(head, q)
            chipGroup.removeAllViews()
            rows.forEach { r ->
                val layoutRes = if (tempIsCountryMode) {
                    R.layout.view_counter_chip_country
                } else {
                    R.layout.view_counter_chip
                }
                val chipView = inflater.inflate(layoutRes, chipGroup, false)
                val tv = chipView.findViewById<TextView>(R.id.tvLabel)
                val tvDots = chipView.findViewById<TextView>(R.id.tvDots)
                val btnMinus = chipView.findViewById<ImageButton>(R.id.btnMinus)
                val btnPlus = chipView.findViewById<ImageButton>(R.id.btnPlus)

                // 현재 모드 기준으로 (item,country) 키 만들기
                val item = if (!tempIsCountryMode) head else r.name
                val country = if (!tempIsCountryMode) r.name else head
                val key = item to country

                //baseHave: off모드면 offHaveMap 에서
                val baseHave = if (showOffHave) (offHaveMap[key] ?: 0) else r.have

                // 임시 have 값이 있으면 그걸 표시, 없으면 원래 r.have
                val displayHave = tempHave[key] ?: baseHave

                val labelPrefix = if (showOffHave) "O" else "S"
                // (선택) 임시 증감도 같이 보여주고 싶으면 extra 붙이기
                val extra = tempHave[key]?.let { v -> if (v != baseHave) " (${v - baseHave})" else "" } ?: "(0)"
                if (!tempIsCountryMode) {
                    tv.text = "${r.name} (N:${r.needed}, $labelPrefix:${displayHave})$extra"
                } else{
                    tv.text = "${r.name} \n (N:${r.needed}, $labelPrefix:${displayHave})$extra"
                }

                updateDots(tvDots, displayHave, r.needed)

                btnMinus.setOnClickListener {
                    onDelta(head, r, -1)   // 👈 HomeFragment 쪽에서 pending 반영
                }
                btnPlus.setOnClickListener {
                    onDelta(head, r, +1)
                }

                chipView.setOnClickListener {
                    onPickRows(head, listOf(r))      // ← 예전 칩 클릭 동작 복구
                }

                chipView.setOnLongClickListener {
                    onItemLongClick(head, listOf(r.name))
                    true
                }

                chipGroup.addView(chipView)
            }
        }
    }

    private fun updateDots(tvDots: TextView, have: Int, needed: Int) {
        tvDots.post {
            val totalWidth = tvDots.width
            if (totalWidth <= 0) return@post

            val dotWidth = tvDots.paint.measureText("●")
            val space = tvDots.paint.measureText(" ")
            val oneSlot = (dotWidth + space).coerceAtLeast(1f)

            val maxSlotsByWidth = (totalWidth / oneSlot).toInt().coerceAtLeast(1)
            val slots = maxSlotsByWidth
            tvDots.text = buildDots(have, slots)
        }
    }

    private fun buildDots(have: Int, slots: Int): String {
        val maxSlots = slots.coerceAtLeast(1)
        val full = have.coerceIn(0, maxSlots)
        val empty = (maxSlots - full).coerceAtLeast(0)

        return "●".repeat(full) + "○".repeat(empty)

    }

    private var showOffHave: Boolean = false
    private var offHaveMap: Map<Pair<String, String>, Int> = emptyMap()

    fun setOffHaveMode(enabled: Boolean, offHave: Map<Pair<String, String>, Int>) {
        showOffHave = enabled
        offHaveMap = offHave
        notifyDataSetChanged()
    }
}
