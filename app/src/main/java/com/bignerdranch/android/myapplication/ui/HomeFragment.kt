package com.bignerdranch.android.myapplication.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.EntryAdapter
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.Row
import com.bignerdranch.android.myapplication.StickyHeaderDecoration
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class HomeFragment : Fragment(R.layout.fragment_home) {

    private val vm: ItemCountryViewModel by activityViewModels()

    private val isCountryMode = MutableStateFlow(false)

    private val pending = mutableMapOf<Pair<String, String>, Pair<Int, Int>>()

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: EntryAdapter
    private lateinit var indexBar: TextView
    private lateinit var tvMode: TextView
    private lateinit var switchMode: SwitchCompat

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        // 1) 뷰 찾기 (반드시 onViewCreated에서!)
        val toolbar = view.findViewById<androidx.appcompat.widget.Toolbar>(R.id.topAppBar)
        tvMode = view.findViewById(R.id.tvMode)
        switchMode = view.findViewById(R.id.switchMode)
        recyclerView = view.findViewById(R.id.recyclerView)
        indexBar = view.findViewById(R.id.indexBar)
        val fabAdd = view.findViewById<FloatingActionButton>(R.id.fabAdd)
        val fabHistory = view.findViewById<FloatingActionButton>(R.id.fabHistory)
        val fabSave = view.findViewById< FloatingActionButton>(R.id.fabSave)
        fabSave.setOnClickListener {
            if (pending.isEmpty()) {
                Toast.makeText(requireContext(), "변경된 내용이 없습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val batchId = System.currentTimeMillis()
            viewLifecycleOwner.lifecycleScope.launch {
                pending.forEach { (key, pair) ->
                    val (item, country) = key
                    val (needed, have) = pair
                    vm.updateQuantity(item, country, needed, have, batchId)
                }
                pending.clear()
                adapter.setTempSnapshot(emptyMap(), isCountryMode.value)
                Toast.makeText(requireContext(), "저정 완료! (이번 저장이 새 '차수'가 됩니다.", Toast.LENGTH_SHORT).show()
            }
        }

        // 2) 어댑터/리사이클러뷰 셋업 (클래스 프로퍼티 사용, 지역 변수 만들지 말 것!)
        adapter = EntryAdapter(
            onItemLongClick = { head, list -> showDeleteDialog(head, list) },
            onPickRows = { head, rows ->
                when (rows.size) {
                    0 -> Unit
                    1 -> {
                        val r = rows.first()
                        val countryMode = isCountryMode.value
                        val item = if (!countryMode) head else r.name
                        val country = if (!countryMode) r.name else head
                        showQuantityDialog(item, country, r.needed, r.have)
                    }
                    else -> showRowPickerBottomSheet(head, rows)
                }
            },
            onDelta = { head, row, delta ->
                applyLocalDelta(
                    head = head,
                    rowName = row.name,
                    isCountryMode = isCountryMode.value,
                    delta = delta,
                    currentNeeded = row.needed,
                    currentHave = row.have
                )
                // (선택) 화면 갱신 표시: tvLabel에 보이는 보유수를 임시로 +1/-1 반영하고 싶으면
                // adapter 쪽에 "임시 표시값" 지원을 더해도 됨
                // 👇 현재 pending 스냅샷을 어댑터에 넘겨 라벨 즉시 반영
                adapter.setTempSnapshot(pending, isCountryMode.value)
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        recyclerView.addItemDecoration(
            StickyHeaderDecoration(
                isHeader = { pos -> adapter.isHeader(pos) },
                bindHeaderView = { headerView, pos -> adapter.bindHeaderView(headerView, pos) },
                headerLayoutRes = R.layout.item_section_header
            )
        )

        // 3) 인덱스 바
        fitIndexBarLineSpacing()

        // 4) 툴바 메뉴 + 검색
        toolbar.inflateMenu(R.menu.menu)
        val searchItem = toolbar.menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(newText: String?): Boolean {
                adapter.filter(newText.orEmpty())
                return true
            }
            override fun onQueryTextChange(newText: String?) = false
        })

        // 5) 스위치/플로팅버튼
        switchMode.setOnCheckedChangeListener { _, isChecked ->
            isCountryMode.value = isChecked
            tvMode.text = if (isChecked) "나라 기준" else "아이템 기준"
            adapter.setTempSnapshot(pending, isChecked) // 모드 바뀌면 키 계산 기준도 바뀌니 재적용
        }
        fabAdd.setOnClickListener { showAddDialog() }
        fabHistory.setOnClickListener {
            startActivity(Intent(requireContext(), AddedActivity::class.java))
        }

        // 6) Flow 수집 (중첩 collect 금지 → combine으로 한 번에)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    vm.uiStateItemQty,     // 아이템 → 나라(수량 포함)
                    vm.uiStateCountryQty,  // 나라 → 아이템(수량 포함)
                    isCountryMode
                ) { itemMap, countryMap, countryMode ->
                    if (countryMode) countryMap else itemMap
                }.collect { map ->
                    adapter.submitData(map)
                    adapter.setTempSnapshot(pending, isCountryMode.value)
                }
            }
        }


    }

    private fun fitIndexBarLineSpacing() {
        indexBar.doOnLayout {
            val lines = indexBar.text.split("\n")
            if (lines.size <= 1) return@doOnLayout

            // 글꼴 기본 글자 높이
            val fm = indexBar.paint.fontMetricsInt
            val charHeight = (fm.bottom - fm.top)

            // 전체 높이에 맞게 각 줄 사이 여백 계산
            val totalCharsHeight = charHeight * lines.size
            val extra = ((indexBar.height - totalCharsHeight).toFloat() / (lines.size - 1))
                .coerceAtLeast(0f)

            indexBar.setLineSpacing(extra, 1f)  // 줄간격 추가 적용
        }
    }
    private fun applyLocalDelta(head: String, rowName: String, isCountryMode: Boolean, delta: Int, currentNeeded: Int, currentHave: Int) {
        // 화면 모드에 따라 (item,country) 정리
        val item = if (!isCountryMode) head else rowName
        val country = if (!isCountryMode) rowName else head

        // 현재 표시값 + 누적 delta → 임시 have 계산
        val key = item to country
        val baseNeeded = currentNeeded
        val baseHave = currentHave
        val now = pending[key] ?: (baseNeeded to baseHave)
        val newHave = (now.second + delta).coerceAtLeast(0)
        pending[key] = (baseNeeded to newHave)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupIndexBar() {
        // 접근성: performClick 연동
        indexBar.setOnClickListener { /* no-op */ }

        indexBar.setOnTouchListener { v, event ->
            val tv = v as TextView
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val lines = tv.text.toString().split("\n")
                    if (lines.isEmpty()) return@setOnTouchListener false

                    val y = event.y.coerceIn(0f, tv.height.toFloat())
                    val lineHeight = (tv.height.toFloat() / lines.size.coerceAtLeast(1))
                    val idx = (y / lineHeight).toInt().coerceIn(0, lines.lastIndex)
                    val sec = lines[idx]

                    adapter.positionOfSection(sec)?.let { pos ->
                        (recyclerView.layoutManager as LinearLayoutManager)
                            .scrollToPositionWithOffset(pos, 0)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    // --- 이하 다이얼로그/보조 함수들은 그대로 사용 (작은 안정성만 보강) ---

    private fun parseListWithNumbers(input: String): List<Pair<String, Int>> {
        val tokens = input.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val result = mutableListOf<Pair<String, Int>>()
        var lastName: String? = null

        tokens.forEach { token ->
            val num = token.toIntOrNull()
            if (num != null && lastName != null) {
                val idx = result.indexOfLast { it.first == lastName }
                if (idx >= 0) result[idx] = lastName!! to num
            } else {
                result.add(token to 0)
                lastName = token
            }
        }
        return result
    }

    private fun showQuantityDialog(
        presetItem: String? = null,
        presetCountry: String? = null,
        presetNeeded: Int? = null,
        presetHave: Int? = null
    ) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_quantity, null)
        val etItem = dialogView.findViewById<EditText>(R.id.etItem)
        val etCountry = dialogView.findViewById<EditText>(R.id.etCountry)
        val etNeeded = dialogView.findViewById<EditText>(R.id.etNeeded)
        val etHave = dialogView.findViewById<EditText>(R.id.etHave)

        val btnNeededPlus = dialogView.findViewById<Button>(R.id.btnNeededPlus)
        val btnNeededMinus = dialogView.findViewById<Button>(R.id.btnNeededMinus)
        val btnHavePlus = dialogView.findViewById<Button>(R.id.btnHavePlus)
        val btnHaveMinus = dialogView.findViewById<Button>(R.id.btnHaveMinus)

        presetItem?.let { etItem.setText(it) }
        presetCountry?.let { etCountry.setText(it) }
        presetNeeded?.let { etNeeded.setText(it.toString()) }
        presetHave?.let { etHave.setText(it.toString()) }

        fun adjust(edit: EditText, delta: Int) {
            val current = edit.text.toString().toIntOrNull() ?: 0
            edit.setText((current + delta).coerceAtLeast(0).toString())
        }
        btnNeededPlus.setOnClickListener { adjust(etNeeded, +1) }
        btnNeededMinus.setOnClickListener { adjust(etNeeded, -1) }
        btnHavePlus.setOnClickListener { adjust(etHave, +1) }
        btnHaveMinus.setOnClickListener { adjust(etHave, -1) }

        AlertDialog.Builder(requireContext())
            .setTitle("수량 수정")
            .setView(dialogView)
            .setPositiveButton("저장") { dialog, _ ->
                val item = etItem.text.toString().trim()
                val country = etCountry.text.toString().trim()
                val needed = etNeeded.text.toString().toIntOrNull() ?: 0
                val have = etHave.text.toString().toIntOrNull() ?: 0
                if (item.isEmpty() || country.isEmpty()) {
                    Toast.makeText(requireContext(), "아이템/나라를 입력하세요.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                vm.updateQuantity(item, country, needed, have)
                Toast.makeText(requireContext(), "저장했습니다.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showDeleteDialog(head: String, list: List<String>) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_delete, null)
        val rg = dialogView.findViewById<RadioGroup>(R.id.rgDeleteMode)
        val rbLink = dialogView.findViewById<RadioButton>(R.id.rbDeleteLink)
        val rbItem = dialogView.findViewById<RadioButton>(R.id.rbDeleteItem)
        val rbCountry = dialogView.findViewById<RadioButton>(R.id.rbDeleteCountry)
        val etItem = dialogView.findViewById<EditText>(R.id.etItem)
        val etCountry = dialogView.findViewById<EditText>(R.id.etCountry)

        etItem.setText(head)
        etCountry.setText(list.joinToString(", "))

        AlertDialog.Builder(requireContext())
            .setTitle("삭제")
            .setView(dialogView)
            .setPositiveButton("삭제") { dialog, _ ->
                val item = etItem.text.toString().trim()
                val country = etCountry.text.toString().trim()

                when {
                    rbLink.isChecked -> {
                        if (item.isEmpty() || country.isEmpty()) {
                            Toast.makeText(requireContext(), "아이템과 나라를 입력하세요.", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        vm.deleteLink(item, country)
                    }
                    rbItem.isChecked -> {
                        if (item.isEmpty()) {
                            Toast.makeText(requireContext(), "아이템명을 입력하세요.", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        vm.deleteItem(item)
                    }
                    rbCountry.isChecked -> {

                        // 현재 화면 모드에 따라 country 이름을 결정
                        val countryNames: List<String> = if (isCountryMode.value) {
                            // 나라 기준 모드면 head 자체가 나라 이름
                            listOf(head)
                        } else {
                            // 아이템 기준 모드면 etCountry에 "한국, 일본, ..." 처럼 들어있음 → 분리
                            country.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        }

                        if (country.isEmpty()) {
                            Toast.makeText(requireContext(), "나라명을 입력하세요.", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }

                        // 여러 개도 모두 삭제
                        countryNames.forEach { name ->
                            vm.deleteCountry(name)
                        }
                    }
                }
                Toast.makeText(requireContext(), "삭제 완료!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showAddDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add, null)
        val rbItemToCountries = dialogView.findViewById<RadioButton>(R.id.rbItemToCountries)
        val rbCountryToItems = dialogView.findViewById<RadioButton>(R.id.rbCountryToItems)
        val etHead = dialogView.findViewById<EditText>(R.id.etHead)
        val etList = dialogView.findViewById<EditText>(R.id.etList)

        AlertDialog.Builder(requireContext())
            .setTitle("추가하기")
            .setView(dialogView)
            .setPositiveButton("추가") { dialog, _ ->
                val head = etHead.text.toString().trim()
                val parsed = parseListWithNumbers(etList.text.toString())

                if (head.isEmpty() || parsed.isEmpty()) {
                    Toast.makeText(requireContext(), "머리와 목록을 입력하세요.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (rbItemToCountries.isChecked) {
                    // 아이템 → 나라들
                    val countryNames = parsed.map { it.first }
                    vm.addItems(mapOf(head to countryNames)) // 링크 0으로 생성
                    parsed.forEach { (country, have) ->
                        vm.updateQuantity(head, country, needed = 0, have = have)
                    }
                } else {
                    // 나라 → 아이템들
                    val itemNames = parsed.map { it.first }
                    val map = itemNames.associateWith { listOf(head) }
                    vm.addItems(map) // 링크 0으로 생성
                    parsed.forEach { (item, have) ->
                        vm.updateQuantity(item, head, needed = 0, have = have)
                    }
                }

                Toast.makeText(requireContext(), "추가 완료!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showRowPickerBottomSheet(head: String, rows: List<Row>) {
        val dialog = BottomSheetDialog(requireContext())
        val v = layoutInflater.inflate(R.layout.bottom_sheet_row_picker, null)
        dialog.setContentView(v)

        val listView = v.findViewById<ListView>(R.id.listRows)
        val labels = rows.map { r -> "${r.name} (필요: ${r.needed}, 보유: ${r.have})" }
        listView.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels)

        listView.setOnItemClickListener { _, _, pos, _ ->
            val selected = rows[pos]
            val countryMode = isCountryMode.value
            val item = if (!countryMode) head else selected.name
            val country = if (!countryMode) selected.name else head
            dialog.dismiss()
            showQuantityDialog(item, country, selected.needed, selected.have)
        }
        dialog.show()
    }
}
