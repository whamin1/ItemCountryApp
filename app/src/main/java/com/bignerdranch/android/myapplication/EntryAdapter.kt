package com.bignerdranch.android.myapplication

import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log.e
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.repository.ItemCountryRepository
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

data class Row(val name: String, val needed: Int, val have: Int, val price: Int = 0)

/**
 * 기능:
 * - 검색어 하이라이트 (굵게 + 색상)
 * - 섹션 헤더(가나다/ABC) 자동 생성
 * - 클릭 시 MainActivity로 (head, rows) 통째로 전달 → 거기서 단건/다건 처리
 */
class EntryAdapter(
    private val onItemLongClick: (head: String, row: Row) -> Unit,
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
    private var allowedItemNames: Set<String>? = null

    fun setTempSnapshot(
        pending: Map<Pair<String, String>, Pair<Int, Int>>,
        isCountryMode: Boolean
    ) {
        val newTempHave = pending.mapValues { it.value.second }

        if (tempIsCountryMode == isCountryMode && tempHave == newTempHave) {
            return
        }

        val modeChanged = tempIsCountryMode != isCountryMode
        tempIsCountryMode = isCountryMode
        tempHave = newTempHave
        if (modeChanged) rebuildSections(full, filterQuery) else notifyDataSetChanged()
    }
    // ----- 원본/표시 데이터 -----
    private var full: List<Pair<String, List<Row>>> = emptyList()   // 원본(검색용)
    private var items: MutableList<Any> = mutableListOf()           // HeaderRow / GroupRow (표시용)
    private val sectionFirstPos = mutableMapOf<String, Int>()       // 섹션 첫 위치(빠른 점프용)

    // 검색어 저장(하이라이트 용)
    // 기존 currentQuery 삭제(or 안 씀)
    private var highlightQuery: String = ""
    private var filterQuery: String = ""

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
            e.key to e.value.map { cq -> Row(cq.name, cq.needed, cq.have, cq.price) }
        }
        rebuildSections(full, filterQuery)
    }

    fun setAllowedItemNames(itemNames: Set<String>?) {
        val normalized = itemNames?.map { it.trim().lowercase() }?.toSet()
        if (allowedItemNames == normalized) return
        allowedItemNames = normalized
        rebuildSections(full, filterQuery)
    }

    fun findMatchPosition(query: String, fromIndex: Int = 0): Int? {
        if (query.isBlank()) return null
        val q = query.lowercase()

        for (i in fromIndex until items.size) {
            when (val e = items[i]) {
                is HeaderRow -> {
                    if (e.title.lowercase().contains(q)) return i
                }
                is GroupRow -> {
                    if (e.head.lowercase().contains(q)) return i
                    if (e.rows.any { it.name.lowercase().contains(q) }) return i
                }
            }
        }
        return null
    }
    private var highlightedHead: String? = null



    fun highlightHead(head: String?) {
        highlightedHead = head?.trim()
        notifyDataSetChanged()
    }
    fun setHighlightQuery(query: String?) {
        highlightQuery = query?.trim().orEmpty()
        notifyDataSetChanged()
    }

    // ----- 필터링 + 섹션 재구성 -----
    fun filter(query: String) {
        highlightQuery = query
        rebuildSections(full, filterQuery)
    }

    private fun rebuildSections(
        src: List<Pair<String, List<Row>>>,
        query: String
    ) {
        val categoryFiltered = allowedItemNames?.let { allowed ->
            if (tempIsCountryMode) {
                src.mapNotNull { (country, rows) ->
                    val matchingRows = rows.filter { it.name.trim().lowercase() in allowed }
                    if (matchingRows.isEmpty()) null else country to matchingRows
                }
            } else {
                src.filter { (item, _) -> item.trim().lowercase() in allowed }
            }
        } ?: src

        val filtered = if (query.isBlank()) categoryFiltered else {
            categoryFiltered.filter { (head, rows) ->
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
                holder.bind(g.head, g.rows, filterQuery)
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
        private val onItemLongClick: (head: String, row: Row) -> Unit,
        private val onPickRows: (head: String, rows: List<Row>) -> Unit
    ) : EntryViewHolder(v) {

        private val inflater = LayoutInflater.from(v.context)
        private val chipGroup: ChipGroup = v.findViewById(R.id.chipGroupRows)
        private val tvHead: TextView = v.findViewById(R.id.tvHead)

        fun bind(head: String, rows: List<Row>, q: String) {
            val isHighlighted  = highlightedHead?.equals(head, ignoreCase = true) == true

            tvHead.text = if (isHighlighted) {
                SpannableString(head).apply {
                    setSpan(StyleSpan(Typeface.BOLD), 0, head.length, 0)
                    setSpan(ForegroundColorSpan(0xFFFF9800.toInt()), 0, head.length, 0)
                }
            } else {
                highlight(head, q) // 기존 검색 하이라이트
            }


            chipGroup.removeAllViews()
            rows.forEach { r ->
                val layoutRes = if (tempIsCountryMode) {
                    R.layout.view_counter_chip_country
                } else {
                    R.layout.view_counter_chip
                }
                val chipView = inflater.inflate(layoutRes, chipGroup, false)
                val tv = chipView.findViewById<TextView>(R.id.tvLabel)
                val tvCount = chipView.findViewById<TextView>(R.id.tvCount)
                val tvDots = chipView.findViewById<TextView>(R.id.tvDots)
                val btnPlus = chipView.findViewById<ImageButton>(R.id.btnPlus)
                val tvPrice = chipView.findViewById<TextView>(R.id.tvPrice)


                tvPrice.text = if (r.price > 0) "%,d".format(r.price) else ""

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
                tv.text = r.name
                tvCount.text = if (!tempIsCountryMode) {
                    "(N:${r.needed}, $labelPrefix:${displayHave})"
                } else {
                    "(N:${r.needed}, $labelPrefix:${displayHave})$extra"
                }

                // 목표가 있는 아이템만 현재 수량 상태를 색으로 구분한다.
                if (r.needed > 0) {
                    val statusColor = when {
                        displayHave < r.needed -> R.color.home_count_short
                        displayHave == r.needed -> R.color.home_count_met
                        else -> R.color.home_count_over
                    }
                    tvCount.setTextColor(
                        ContextCompat.getColor(chipView.context, statusColor)
                    )
                }

                updateDots(tvDots, displayHave, r.needed)

                btnPlus.setOnClickListener {
                    onDelta(head, r, +1)
                }

                tvDots.setOnClickListener {onDelta(head, r, +1)
                }

                chipView.setOnClickListener {
                    onPickRows(head, listOf(r))      // ← 예전 칩 클릭 동작 복구
                }

                chipView.setOnLongClickListener {
                    onItemLongClick(head, r)
                    true
                }

                chipGroup.addView(chipView)
            }
        }
    }

    fun findHeadPositionExact(head: String): Int? {
        val target = head.trim()
        if (target.isEmpty()) return null

        for (i in items.indices) {
            val e = items[i]
            if (e is GroupRow && e.head.equals(target, ignoreCase = true)) {
                return i
            }
        }
        return null
    }

    private fun updateDots(tvDots: TextView, have: Int, needed: Int) {
        tvDots.text = buildDots(have, 10)
    }

    private fun buildDots(have: Int, slots: Int): String {
        val numbers = listOf("①","②","③","④","⑤","⑥","⑦","⑧","⑨","⑩")

        val maxSlots = slots.coerceAtLeast(1)
        val full = have.coerceIn(0, maxSlots)

        val sb = StringBuilder()

        for (i in 0 until maxSlots) {
            if (i < full) {
                sb.append(numbers.getOrNull(i) ?: "●")
            } else {
                sb.append("○")
            }
        }

        return sb.toString()
    }

    private var showOffHave: Boolean = false
    private var offHaveMap: Map<Pair<String, String>, Int> = emptyMap()

    fun setOffHaveMode(enabled: Boolean, offHave: Map<Pair<String, String>, Int>) {
        showOffHave = enabled
        offHaveMap = offHave
        notifyDataSetChanged()
    }
}
