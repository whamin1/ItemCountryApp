package com.bignerdranch.android.myapplication.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AlertDialog
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.text.TextWatcher
import android.util.Log
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import androidx.room.util.query
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import com.bignerdranch.android.myapplication.data.local.entity.ItemSearchRow
import com.bignerdranch.android.myapplication.data.local.entity.SaveSessionEntity
import com.bignerdranch.android.myapplication.data.local.entity.SaveSessionLineEntity
import com.bignerdranch.android.myapplication.data.local.entity.SheetEntity
import com.bignerdranch.android.myapplication.data.local.entity.SheetLineEntity
import com.bignerdranch.android.myapplication.repository.ItemCountryRepository
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment(R.layout.fragment_home) {

    private val vm: ItemCountryViewModel by activityViewModels()

    private val isCountryMode = MutableStateFlow(false)

    private data class Pending(
        val baseNeeded: Int,
        val baseHave: Int,
        var newHave: Int
    )
    private val pending = mutableMapOf<Pair<String, String>, Pending>()
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: EntryAdapter
    private lateinit var indexBar: TextView
    private lateinit var tvMode: TextView
    private lateinit var switchMode: SwitchCompat
    private val isCountMode = MutableStateFlow(false)
    private lateinit var switchCountMode: SwitchCompat

    private val db by lazy { AppDatabase.get(requireContext()) }
    private val itemDao by lazy { db.itemCountryDao() }
    private val archiveDao by lazy { db.saveArchiveDao() }
    private val offHaveMapFlow = MutableStateFlow<Map<Pair<String, String>, Int>>(emptyMap())
    private var lastSearchIndex: Int = -1
    private lateinit var fabHistory: FloatingActionButton
    private lateinit var fabSave: ExtendedFloatingActionButton
    private lateinit var fabReset: FloatingActionButton
    private lateinit var fabArchive: FloatingActionButton
    private val PREFS_NAME = "pred_prefs"
    private val KEY_EXCLUDED_IDS = "excluded_item_ids"

    private fun predPrefs() =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getExcludedPredIds(): Set<Long> {
        val set = predPrefs().getStringSet(KEY_EXCLUDED_IDS, emptySet()) ?: emptySet()
        return set.mapNotNull { it.toLongOrNull() }.toSet()
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fabHistory = view.findViewById(R.id.fabHistory)
        fabSave = view.findViewById(R.id.fabSave)
        fabArchive = view.findViewById(R.id.fabArchive)
        fabReset = view.findViewById(R.id.fabReset)

        // 1) 뷰 찾기 (반드시 onViewCreated에서!)
        val toolbar = view.findViewById<Toolbar>(R.id.topAppBar)
        tvMode = view.findViewById(R.id.tvMode)
        switchMode = view.findViewById(R.id.switchMode)
        switchCountMode = view.findViewById(R.id.switchCountMode)
        recyclerView = view.findViewById(R.id.recyclerView)
        indexBar = view.findViewById(R.id.indexBar)
//        val fabAdd = view.findViewById<FloatingActionButton>(R.id.fabAdd)
//        val fabHistory = view.findViewById<FloatingActionButton>(R.id.fabHistory)
//        val fabSave = view.findViewById< ExtendedFloatingActionButton>(R.id.fabSave)
//        val fabArchive = view.findViewById<FloatingActionButton>(R.id.fabArchive)
//        val fabReset = view.findViewById<FloatingActionButton>(R.id.fabReset)


        viewLifecycleOwner.lifecycleScope.launch {
            val dao = AppDatabase.get(requireActivity()).itemCountryDao()
            dao.observeItemsWithOff().collect { list ->
                val m = buildMap {
                    list.forEach { w ->
                        put(w.item to w.country, w.offHave)
                    }
                }
                offHaveMapFlow.value = m
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            AppDatabase.get(requireContext())
                .itemCountryDao()
                .backfillItemCountryWeightPriceFromSheets()
        }
        switchCountMode.isChecked = false
        switchCountMode.text = "기록"
        updateSaveModeUi()

        switchCountMode.setOnCheckedChangeListener { _, isChecked ->
            isCountMode.value = isChecked
            switchCountMode.text = if (isChecked) "저장" else "기록"
            updateSaveModeUi()
            updateFabSaveLabel()
            adapter.setTempSnapshot(pendingSnapshotForAdapter(), isCountryMode.value)
            // ✅ 즉시 표기 전환
            adapter.setOffHaveMode(enabled = !isCountMode.value, offHave = offHaveMapFlow.value)
        }

        fabReset.setOnClickListener {
            pending.clear()
            adapter.setTempSnapshot(emptyMap(), isCountryMode.value)
            updateFabSaveLabel()
            Toast.makeText(requireContext(), "임시 변경 내용을 모두 초기화했습니다.", Toast.LENGTH_SHORT).show()
        }

        fabArchive.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val countries = itemDao.getAllCountryNames().first().toTypedArray()
                if (countries.isEmpty()) {
                    Toast.makeText(requireContext(), "등록된 나라가 없습니다.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val checked = BooleanArray(countries.size)
                AlertDialog.Builder(requireContext())
                    .setTitle("보관/초기화할 나라 선택")
                    .setMultiChoiceItems(countries, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton("실행") { _, _ ->
                        val selected = countries.filterIndexed { i, _ -> checked[i] }
                        saveSelectedCountries(selected) // ⬇ 아래 함수 그대로 사용
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }


        fabSave.setOnClickListener {
            if (pending.isEmpty()) {
                Toast.makeText(requireContext(), "변경된 내용이 없습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val batchId = System.currentTimeMillis() // 이번 저장 묶음
            viewLifecycleOwner.lifecycleScope.launch {
                pending.forEach { (key, p) ->
                    val (item, country) = key
                    val needed = p.baseNeeded
                    val have = p.newHave
                    vm.updateQuantity(item, country, needed, have, batchId)
                }
                pending.clear()
                adapter.setTempSnapshot(emptyMap(), isCountryMode.value)
                updateFabSaveLabel()
                Toast.makeText(requireContext(), "저정 완료! (이번 저장이 새 '차수'가 됩니다.", Toast.LENGTH_SHORT).show()
            }
        }

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    highlightedHead = null
                    adapter.highlightHead(null)
                    clearHighlightJob?.cancel()
                }
            }
        })

        // 2) 어댑터/리사이클러뷰 셋업 (클래스 프로퍼티 사용, 지역 변수 만들지 말 것!)
        adapter = EntryAdapter(
            onItemLongClick = { head, r ->
                val countryMode = isCountryMode.value
                val item = if (!countryMode) head else r.name
                val country = if (!countryMode) r.name else head

                viewLifecycleOwner.lifecycleScope.launch {
                    val row = vm.findOneItemRowForJump(item, country)

                    if (row != null) {
                        navigateToDetail(row)
                    } else {
                        Toast.makeText(requireContext(), "시트에서 못 찾음: $item / $country", Toast.LENGTH_SHORT).show()
                        // ✅ row == null 이면: 시트에도 없으니 "정리(삭제)" 제안
                        AlertDialog.Builder(requireContext())
                            .setTitle("정리할까요?")
                            .setMessage("이 아이템은 어떤 시트에도 없습니다.\n홈 목록에서 삭제하시겠습니까?\n\n$item")
                            .setPositiveButton("삭제") { _, _ ->
                                vm.deleteItem(item) // ✅ 아이템 전체 삭제 (links -> items)
                                Toast.makeText(requireContext(), "삭제되었습니다.", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("취소", null)
                            .show()

                    }


                }
            }
        ,
            onPickRows = { head, rows ->
                if (!isCountMode.value) {
                    showOffDialog(head, rows)
                    return@EntryAdapter
                }
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
                adapter.setTempSnapshot(pendingSnapshotForAdapter(), isCountryMode.value)
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
        indexBar.bringToFront()
        setupIndexBar()

        // 4) 툴바 메뉴 + 검색
        toolbar.inflateMenu(R.menu.menu)
        val searchItem = toolbar.menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                moveToNextMatch(query)
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.setHighlightQuery(newText)
                moveToFirstMatch(newText)
                return true
            }
        })

        // 5) 스위치/플로팅버튼
        switchMode.setOnCheckedChangeListener { _, isChecked ->
            isCountryMode.value = isChecked
            tvMode.text = if (isChecked) "나라 기준" else "아이템 기준"
            adapter.setTempSnapshot(pendingSnapshotForAdapter(), isChecked) // 모드 바뀌면 키 계산 기준도 바뀌니 재적용
        }
//
//        fabAdd.setOnClickListener { showAddDialog() }
        fabHistory.setOnClickListener {
            startActivity(Intent(requireContext(), AddedActivity::class.java))
        }

        // 6) Flow 수집 (중첩 collect 금지 → combine으로 한 번에)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch{
                    combine(
                        vm.uiStateItemQty,     // 아이템→나라
                        vm.uiStateCountryQty,  // 나라→아이템
                        isCountryMode,
                        isCountMode,
                        offHaveMapFlow
                    ) { itemMap, countryMap, countryMode, countMode, offMap ->
                        // 👇 5개를 한 묶음으로 반환할 수 없으니 Triple 안에 Pair를 넣자
                        Triple(
                            countryMode,
                            if (countryMode) countryMap else itemMap,
                            Pair(countMode, offMap)
                        )
                    }.collect { triple ->
                        val countryMode = triple.first
                        val map = triple.second
                        val countMode = triple.third.first
                        val offMap = triple.third.second

                        adapter.submitData(map)
                        adapter.setTempSnapshot(pendingSnapshotForAdapter(), countryMode)

                        // ✅ 저장 OFF 모드일 땐 offHave 표기
                        adapter.setOffHaveMode(enabled = !countMode, offHave = offMap)


                        updateFabSaveLabel()
                        val sections = adapter.availableSections()
                        indexBar.text = sections.joinToString("\n")
                        fitIndexBarLineSpacing()
                    }
                }
                launch {
                    vm.recentTouched.collect { list ->
                        updateRecentUI(list)
                    }
                }
                launch {
                    vm.pendingJumpItem.collect { item ->
                        if (item.isNullOrBlank()) return@collect

                        if (isCountryMode.value) {
                            vm.consumeJumpRequest()
                        }

                        recyclerView.post {
                            jumpToHeadExact(item)
                            adapter.setHighlightQuery(item)
                            vm.consumeJumpRequest()
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.get(requireContext())
            val sheetDao = db.sheetDao()

            // 이미 시트가 있으면 중복생성 안 함
            val count = sheetDao.observeAllSheets().firstOrNull()?.size ?: 0
            if (count == 0) {
                val asiaLines = listOf(
                    SheetLineEntity(item="티셔츠", country="한국", needed=100, have=0, weight=0.2f, price=5000),
                    SheetLineEntity(item="티셔츠", country="중국", needed=120, have=0, weight=0.18f, price=4500),
                    SheetLineEntity(item="양말", country="한국", needed=300, have=0, weight=0.05f, price=800),
                    SheetLineEntity(item="모자", country="인도네시아", needed=80, have=0, weight=0.1f, price=2500),
                    SheetLineEntity(item="후드티", country="베트남", needed=60, have=0, weight=0.5f, price=12000)
                )
                val americaLines = listOf(
                    SheetLineEntity(item="티셔츠", country="미국", needed=90, have=0, weight=0.22f, price=6000),
                    SheetLineEntity(item="후드티", country="캐나다", needed=70, have=0, weight=0.55f, price=15000),
                    SheetLineEntity(item="데님팬츠", country="멕시코", needed=50, have=0, weight=0.7f, price=8000)
                )
                val europeLines = listOf(
                    SheetLineEntity(item="셔츠", country="독일", needed=110, have=0, weight=0.25f, price=9000),
                    SheetLineEntity(item="코트", country="프랑스", needed=40, have=0, weight=1.2f, price=30000),
                    SheetLineEntity(item="신발", country="이탈리아", needed=70, have=0, weight=0.9f, price=20000)
                )

                vm.createSheet("Asia Offer", asiaLines)
                vm.createSheet("America Offer", americaLines)
                vm.createSheet("Europe Offer", europeLines)

                Toast.makeText(requireContext(), "테스트 시트 2개 생성 완료!", Toast.LENGTH_SHORT).show()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val dao = AppDatabase.get(requireContext()).itemCountryDao()
            dao.observeItemsWithOff().collect { list ->
                list.forEach {
                    Log.d("OFF_HAVE", "${it.item} - ${it.country} ▶ have=${it.have}, offHave=${it.offHave}")
                }
            }
        }

        val tvPred = view.findViewById<TextView>(R.id.tvPred)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    vm.predictions,
                    isCountryMode
                ) { preds, countryMode ->
                    preds to countryMode
                }.collect { (list, countryMode) ->
                    if (countryMode) {
                        tvPred.isVisible = false
                    } else {
                        tvPred.isVisible = true

                        val excluded = getExcludedPredIds()
                        val preview = list
                            .filter { it.itemId !in excluded }
                            .take(6)

                        renderPredictions(tvPred, preview)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.refreshPredictions()
        }

        view.findViewById<View>(R.id.btnPredAll).setOnClickListener {
            findNavController().navigate(R.id.predictionsFragment)
        }

    }

    private fun renderPredictions(
        tv: TextView,
        list: List<ItemCountryRepository.PredItem>
    ) {
        if (list.isEmpty()) {
            tv.text = "예상 없음"
            return
        }

        tv.movementMethod =
            android.text.method.LinkMovementMethod.getInstance()

        tv.linksClickable = true
        tv.highlightColor = android.graphics.Color.TRANSPARENT

        val ssb = android.text.SpannableStringBuilder()

        list.forEach { p ->
            val line = "• ${p.item} — ${p.label}\n"
            val start = ssb.length
            ssb.append(line)
            val end = ssb.length

            ssb.setSpan(
                object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        // ✅ 여기서 점프
                        jumpToHeadExact(p.item)
                        highlightHeadTemporarily(p.item)

                        // ❗ 초기화는 다음 단계에서
                    }
                },
                start,
                end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        tv.text = ssb
    }

    private var highlightedHead: String? = null
    private var clearHighlightJob: kotlinx.coroutines.Job? = null

    private fun highlightHeadTemporarily(head: String) {
        highlightedHead = head
        adapter.highlightHead(head)

        clearHighlightJob?.cancel()
        clearHighlightJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(2000)
            // 그 사이 다른 걸 눌렀으면 덮어씌우지 않게 체크
            if (highlightedHead == head) {
                highlightedHead = null
                adapter.highlightHead(null)
            }
        }
    }


    private fun jumpToHeadExact(head: String) {
        val pos = adapter.findHeadPositionExact(head) ?: return
        lastSearchIndex = pos
        (recyclerView.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(pos, 0)
    }
    // 🔍 첫 번째 매칭 위치로 점프
    private fun moveToFirstMatch(query: String?) {
        val q = query?.trim().orEmpty()
        adapter.setHighlightQuery(q)

        if (q.isEmpty()) {
            lastSearchIndex = -1
            return
        }

        val pos = adapter.findMatchPosition(q.orEmpty(), fromIndex = 0) ?: return
        lastSearchIndex = pos

        (recyclerView.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(pos, 0)
    }

    // 🔁 다음 매칭 위치로 점프 (없으면 처음으로 돌아가도 되고)
    private fun moveToNextMatch(query: String?) {
        val q = query?.trim().orEmpty()
        adapter.setHighlightQuery(q)

        if (q.isEmpty()) return

        val start = lastSearchIndex + 1

        val pos =
            adapter.findMatchPosition(q.orEmpty(), fromIndex = start)
                ?: adapter.findMatchPosition(q.orEmpty(), fromIndex = 0)
                ?: return

        lastSearchIndex = pos

        (recyclerView.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(pos, 0)
    }
    private fun showTouchedToast(item: String, country: String, lastTs: Long?) {
        val fmt = SimpleDateFormat("HH:mm", Locale.KOREA)
        val last = lastTs?.let { fmt.format(Date(it)) } ?: "처음"
        val msg = "$item ($country) 클릭! (이전: $last)"
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun updateRecentUI(list: List<ItemCountryDao.RecentRow>) {
        val tvRecent = view?.findViewById<TextView>(R.id.tvRecentItems) ?: return

        if (list.isEmpty()) {
            tvRecent.text = "최근 클릭 없음"
            return
        }

        val fmt = SimpleDateFormat("HH:mm", Locale.KOREA)

        tvRecent.text = list.joinToString("\n") { r ->
            val time = r.ts?.let { fmt.format(Date(it)) } ?: "-"
            "• ${r.item} (${r.country}) — $time"
        }
    }

    private fun saveSelectedCountries(selectedCountries: List<String>) {
        viewLifecycleOwner.lifecycleScope.launch {
            var didAnything = false
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

            for (country in selectedCountries) {
                // 1) 해당 나라 로그/재고 미리 읽기
                val rows = itemDao.getQuantityLogsByCountry(country, 1000)   // 보관할 로그(없을 수도 있음)
                val stocks = itemDao.getHaveByCountry(country)               // 현재 재고(0일 수도 있음)

                // 2) 필요하면 세션(보관본) 만들기: '로그가 있거나' '재고가 있으면' 보관
                if (rows.isNotEmpty() || stocks.any { it.have > 0 }) {
                    val sessionId = archiveDao.insertSession(
                        SaveSessionEntity(
                            title = "$country · ${fmt.format(Date())}",
                            country = country,
                            createdAt = System.currentTimeMillis()
                        )
                    )

                    // (2-1) 로그 라인 백업
                    if (rows.isNotEmpty()) {
                        archiveDao.insertLines(
                            rows.map { r ->
                                SaveSessionLineEntity(
                                    sessionId = sessionId,
                                    batchId = r.batchId,
                                    country = r.country,
                                    item = r.item,
                                    fromHave = r.fromHave,
                                    toHave = r.toHave,
                                    delta = r.delta,
                                    timestamp = r.timestamp
                                )
                            }
                        )
                    }

                    // (2-2) 재고 스냅샷(0으로 내릴 '선적' 라인) 백업
                    val shipLines = stocks
                        .filter { it.have > 0 }
                        .map { s ->
                            SaveSessionLineEntity(
                                id = 0,
                                sessionId = sessionId,
                                batchId = 0L,
                                country = country,
                                item = s.item,
                                fromHave = s.have,
                                toHave = 0,
                                delta = -s.have,
                                timestamp = System.currentTimeMillis()
                            )
                        }
                    if (shipLines.isNotEmpty()) {
                        archiveDao.insertLines(shipLines)
                    }
                }

                // 3) 여기서는 **항상** 초기화 수행 (로그가 없어도 리셋하자)
                val countryId = itemDao.getCountryIdByName(country) ?: continue
                itemDao.markQuantityLogsArchivedByCountry(countryId) // 있으면 archived=1, 없으면 영향 없음
                itemDao.resetHaveAndConsumeOffByCountry(country)                  // ✅ 재고 0으로
                itemDao.deleteQuantityLogsByCountry(country)

                didAnything = true
            }

            if (didAnything) {
                pending.clear()
                adapter.setTempSnapshot(emptyMap(), isCountryMode.value)
                Toast.makeText(requireContext(), "보관/초기화 완료", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "선택한 나라에 처리할 내용이 없습니다.", Toast.LENGTH_SHORT).show()
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
    private fun applyLocalDelta(
        head: String,
        rowName: String,
        isCountryMode: Boolean,
        delta: Int,
        currentNeeded: Int,
        currentHave: Int
    ) {
        // 화면 모드에 따라 (item,country) 정리
        val countryMode = isCountryMode
        val item = if (!isCountryMode) head else rowName
        val country = if (!isCountryMode) rowName else head

        // 현재 표시값 + 누적 delta → 임시 have 계산
        val key = item to country


        // 합계모드 OFF
        if (!isCountMode.value) {
            viewLifecycleOwner.lifecycleScope.launch {
                val itemId = itemDao.getItemIdByName(item) ?: return@launch
                val countryId = itemDao.getCountryIdByName(country) ?: return@launch

                if (delta > 0) {
                    vm.addOffClick(itemId, countryId)      // 이미 있음
                    vm.onOffPlusForPrediction(itemId)
                } else if (delta < 0) {
                    vm.removeOffClick(itemId, countryId)   // 지금 만든 거
                }
            }

            viewLifecycleOwner.lifecycleScope.launch {
                val itemId = itemDao.getItemIdByName(item)
                val countryId = itemDao.getCountryIdByName(country)

                if (itemId != null && countryId != null) {
                    val lastTs = itemDao.getLastClickedAt(itemId, countryId)
                    showTouchedToast(item, country, lastTs)

                    // 🔥 클릭 기록 업데이트도 여기서
                    itemDao.updateLastClickedAt(itemId, countryId, System.currentTimeMillis())
                }
            }

            adapter.setTempSnapshot(pendingSnapshotForAdapter(), countryMode)
            updateFabSaveLabel()
            return
        }

        // 기존 누적로직
        val p = pending[key]
        if (p == null) {
            // 처음 수정하는 항목이면 기준값 저장
            val baseNeeded = currentNeeded
            val baseHave = currentHave
            val newHave = (currentHave + delta).coerceAtLeast(0)
            pending[key] = Pending(baseNeeded, baseHave, newHave)
        } else {
            // 이미 있으면 newHave만 갱신
            p.newHave = (p.newHave + delta).coerceAtLeast(0)
        }
        // 리스트 임시값 반영(이미 어댑터에 함수 만들었을 거야)
        adapter.setTempSnapshot(pendingSnapshotForAdapter(), countryMode)

        // ✅ FAB 라벨 갱신
        updateFabSaveLabel()

    }

    private fun pendingSnapshotForAdapter(): Map<Pair<String, String>, Pair<Int, Int>> =
        pending.mapValues { (_, p) -> p.baseNeeded to p.newHave }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupIndexBar() {
        // 접근성: performClick 연동
        indexBar.setOnClickListener { /* no-op */ }

        indexBar.setOnTouchListener { v, event ->
            val tv = v as TextView
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    val lines = tv.text.toString().split("\n")
                    if (lines.isEmpty()) return@setOnTouchListener false

                    val totalHeight = tv.height.toFloat().coerceAtLeast(1f)
                    val y = event.y.coerceIn(0f, totalHeight)
                    val lineHeight = totalHeight / lines.size.coerceAtLeast(1)

                    // 1) 현재 어떤 섹션인지 (A, B, L, M ...)
                    val idx = (y / lineHeight).toInt().coerceIn(0, lines.lastIndex)
                    val sec = lines[idx]

                    // 2) 해당 섹션 안에서의 비율 (0.0 ~ 1.0)
                    val topY = idx * lineHeight
                    val localY = (y - topY).coerceIn(0f, lineHeight)
                    val ratioInSection =
                        if (lineHeight > 0f) localY / lineHeight else 0f

                    val lm = recyclerView.layoutManager as LinearLayoutManager

                    // 3) 먼저 "섹션 안 비율"로 위치 찾아보고, 안 나오면 기존 방식으로 헤더로 점프
                    val pos = adapter.positionOfSection(sec, ratioInSection)
                        ?: adapter.positionOfSection(sec)

                    pos?.let {
                        lm.scrollToPositionWithOffset(it, 0)
                    }
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
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

    private fun showOffDialog(head: String, rows: List<Row>) {
        // 1) rows가 1개일 때 → 즉시 다이얼로그
        if (rows.size == 1) {
            val r = rows.first()

            val countryMode = isCountryMode.value
            val item = if (!countryMode) head else r.name
            val country = if (!countryMode) r.name else head

            showOffAmountDialog(item, country)
            return
        }

        // 2) rows가 여러 개일 때 → 선택 바텀시트
        val dialog = BottomSheetDialog(requireContext())
        val v = layoutInflater.inflate(R.layout.bottom_sheet_row_picker, null)
        dialog.setContentView(v)

        val listView = v.findViewById<ListView>(R.id.listRows)
        val labels = rows.map { r -> "${r.name} (필요: ${r.needed}, 보유: ${r.have})" }
        listView.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels)

        listView.setOnItemClickListener { _, _, pos, _ ->
            val r = rows[pos]

            val countryMode = isCountryMode.value
            val item = if (!countryMode) head else r.name
            val country = if (!countryMode) r.name else head

            dialog.dismiss()
            showOffAmountDialog(item, country)
        }

        dialog.show()
    }
    private fun showOffAmountDialog(item: String, country: String) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_offmode, null)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)
        val etOffCount = dialogView.findViewById<EditText>(R.id.etOffCount)
        val btnMinus10 = dialogView.findViewById<Button>(R.id.btnMinus10)
        val btnMinus1  = dialogView.findViewById<Button>(R.id.btnMinus1)
        val btnPlus1   = dialogView.findViewById<Button>(R.id.btnPlus1)
        val btnPlus10  = dialogView.findViewById<Button>(R.id.btnPlus10)

        tvTitle.text = "$item ($country)"

        val dlg = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Off 모드 수량 설정")
            .setView(dialogView)
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null)
            .create()

        dlg.setOnShowListener {
            val btnSave = dlg.getButton(AlertDialog.BUTTON_POSITIVE)

            var baseOff = 0  // 현재 offHave

            // 1) 현재 offHave 읽어서 기본값 세팅
            viewLifecycleOwner.lifecycleScope.launch {
                val itemId = itemDao.getItemIdByName(item) ?: return@launch
                val countryId = itemDao.getCountryIdByName(country) ?: return@launch

                baseOff = itemDao.getOffHave(itemId, countryId)  // 이미 있는 함수
                etOffCount.setText(baseOff.toString())
            }

            fun adjust(delta: Int) {
                val cur = etOffCount.text.toString().toIntOrNull() ?: 0
                val next = (cur + delta).coerceAtLeast(0)
                etOffCount.setText(next.toString())
            }

            btnMinus10.setOnClickListener { adjust(-10) }
            btnMinus1.setOnClickListener  { adjust(-1) }
            btnPlus1.setOnClickListener   { adjust(+1) }
            btnPlus10.setOnClickListener  { adjust(+10) }

            // 2) 저장 버튼: "목표값 - 현재값" 만큼 한 번에 로그 반영
            btnSave.setOnClickListener {
                val target = etOffCount.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                val delta = target - baseOff   // +면 증가, -면 감소

                if (delta == 0) {
                    Toast.makeText(requireContext(), "변경된 수량이 없습니다.", Toast.LENGTH_SHORT).show()
                    dlg.dismiss()
                    return@setOnClickListener
                }

                viewLifecycleOwner.lifecycleScope.launch {
                    val itemId = itemDao.getItemIdByName(item) ?: return@launch
                    val countryId = itemDao.getCountryIdByName(country) ?: return@launch

                    itemDao.addOffDelta(itemId, countryId, delta)  // ✅ 한 번에 처리

                    Toast.makeText(
                        requireContext(),
                        "Off 수량 ${baseOff} → $target (Δ $delta) 반영",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                dlg.dismiss()
            }
        }

        dlg.show()
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

        etItem.setText(presetItem ?: "")
        etCountry.setText(presetCountry ?: "")
        etNeeded.setText((presetNeeded ?: 0).toString())
        etHave.setText((presetHave ?: 0).toString())

        val baseHave = presetHave ?: 0

        fun adjust(edit: EditText, delta: Int) {
            val current = edit.text.toString().toIntOrNull() ?: 0
            edit.setText((current + delta).coerceAtLeast(0).toString())
        }
        btnNeededPlus.setOnClickListener { adjust(etNeeded, +1) }
        btnNeededMinus.setOnClickListener { adjust(etNeeded, -1) }
        btnHavePlus.setOnClickListener { adjust(etHave, +1) }
        btnHaveMinus.setOnClickListener { adjust(etHave, -1) }

        val dlg: AlertDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("수량 수정")
            .setView(dialogView)
            .setPositiveButton("저장", null)   // ← onShow에서 커스텀 처리
            .setNegativeButton("취소", null)
            .create()

        dlg.setOnShowListener {
            val btnSave = dlg.getButton(AlertDialog.BUTTON_POSITIVE)

            // (중요) 이 다이얼로그의 타겟 키
            val item = etItem.text.toString().trim()
            val country = etCountry.text.toString().trim()
            val key = item to country

            // 이 다이얼로그가 떴을 때의 '기준값'
            val baseNeeded = etNeeded.text.toString().toIntOrNull() ?: (presetNeeded ?: 0)
            val baseHave   = presetHave ?: 0

            fun predictedPendingSize(): Int {
                val newNeeded = etNeeded.text.toString().toIntOrNull() ?: 0
                val newHave   = etHave.text.toString().toIntOrNull() ?: 0

                // 현재 입력이 '변경'인지 판정 (needed/have 중 하나라도 달라지면 변경으로 간주)
                val changed = (newNeeded != baseNeeded) || (newHave != baseHave)

                // 현재 pending을 복사해서 가상 적용
                val copy = pending.toMutableMap()
                if (changed) {
                    // 이 키를 대기목록에 넣는다고 가정
                    val p = copy[key]
                    if (p == null) {
                        copy[key] = Pending(baseNeeded, baseHave, newHave)
                    } else {
                        p.newHave = newHave
                    }
                } else {
                    // 변경이 없다면 이 키는 대기목록에서 제거한다고 가정
                    copy.remove(key)
                }
                return copy.size
            }

            fun updateSaveLabel() {
                val newHave = etHave.text.toString().toIntOrNull() ?: 0
                val diff = newHave - baseHave
                btnSave.text = "저장 (${if (diff > 0) "+$diff" else "$diff"})"
                val color = when {
                    diff > 0 -> 0xFF2E7D32.toInt()   // 초록
                    diff < 0 -> 0xFFC62828.toInt()   // 빨강
                    else -> 0xFF616161.toInt()       // 회색
                }
                btnSave.setTextColor(color)
            }

            // 처음 1회 갱신
            updateSaveLabel()

            // 보유값이 바뀔 때마다 갱신
            etHave.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updateSaveLabel()
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            // 저장 클릭: pending에 진짜 반영(즉시 DB 저장이 아니라 '대기목록' 업데이트)
            btnSave.setOnClickListener {
                val newNeeded = etNeeded.text.toString().toIntOrNull() ?: 0
                val newHave   = etHave.text.toString().toIntOrNull() ?: 0
                if (item.isEmpty() || country.isEmpty()) {
                    Toast.makeText(requireContext(), "아이템/나라를 입력하세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val changed = (newNeeded != baseNeeded) || (newHave != baseHave)
                if (changed) {
                    val p = pending[key]
                    if (p == null) {
                        pending[key] = Pending(baseNeeded, baseHave, newHave)
                    } else {
                        p.newHave = newHave
                    }
                } else {
                    pending.remove(key)
                }

                // 리스트에 임시값 반영(이미 구현해둔 스냅샷 변환기를 사용)
                adapter.setTempSnapshot(pendingSnapshotForAdapter(), isCountryMode.value)
                updateFabSaveLabel() // 메인 FAB에도 총 변경건/수량 반영 원하면

                Toast.makeText(requireContext(), "대기목록에 반영했습니다.", Toast.LENGTH_SHORT).show()
                dlg.dismiss()
            }
        }

        dlg.show()
    }

    private fun navigateToDetail(row: ItemSearchRow) {
        val args = bundleOf(
            "sheetId" to row.sheetId,
            "title" to row.sheetTitle,
            "country" to row.country,
            "highlightItem" to row.item
        )
        findNavController().navigate(R.id.sheetDetailFragment, args)
    }
    private fun updateSaveModeUi() {
        val saveMode = isCountMode.value

        fabHistory.isVisible = saveMode
        fabSave.isVisible = saveMode
        fabReset.isVisible = saveMode
        fabArchive.isVisible = saveMode
    }

    private fun updateFabSaveLabel() {

        // 뷰에서 fabSave 찾기
        val view = view ?: return
        val fab = view.findViewById<View>(R.id.fabSave)

        val effectiveDelta = if (isCountMode.value) {
            pending.values.sumOf { it.newHave - it.baseHave }
        } else {
            0
        }
        // 1) ExtendedFloatingActionButton 인지 체크
        if (fab is ExtendedFloatingActionButton) {
            if (effectiveDelta == 0) {
                fab.text = "저장"
                fab.shrink() // 아이콘만
            } else {
                fab.text = "저장 (+" + effectiveDelta + ")"
                fab.extend() // 텍스트 보이게
            }
            return
        }

        // 2) 일반 FloatingActionButton 인 경우(텍스트가 원래 안 보임)
        if (fab is FloatingActionButton) {
            // 접근성용 설명 갱신
            fab.contentDescription = if (effectiveDelta == 0) "저장" else "저장 (+" + effectiveDelta + ")"
            // 필요하면 아래 스낵바 한 줄로 시각 피드백도 줄 수 있어요 (원치 않으면 주석 처리)
            // Snackbar.make(requireView(), if (totalDelta==0) "변경 없음" else "변경 합계: +$totalDelta", Snackbar.LENGTH_SHORT).show()
        }
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

//       추가 버튼
//    private fun showAddDialog() {
//        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add, null)
//        val rbItemToCountries = dialogView.findViewById<RadioButton>(R.id.rbItemToCountries)
//        val rbCountryToItems = dialogView.findViewById<RadioButton>(R.id.rbCountryToItems)
//        val etHead = dialogView.findViewById<EditText>(R.id.etHead)
//        val etList = dialogView.findViewById<EditText>(R.id.etList)
//        val etWeight = dialogView.findViewById<EditText>(R.id.etWeight)
//        val etPrice = dialogView.findViewById<EditText>(R.id.etPrice)
//
//        AlertDialog.Builder(requireContext())
//            .setTitle("추가하기")
//            .setView(dialogView)
//            .setPositiveButton("추가") { dialog, _ ->
//                val head = etHead.text.toString().trim()
//                val parsed = parseListWithNumbers(etList.text.toString())
//                val weight = etWeight.text.toString().toFloatOrNull() ?: 0f
//                val price = etPrice.text.toString().toFloatOrNull() ?: 0f
//
//                if (head.isEmpty() || parsed.isEmpty()) {
//                    Toast.makeText(requireContext(), "머리와 목록을 입력하세요.", Toast.LENGTH_SHORT).show()
//                    return@setPositiveButton
//                }
//
//                viewLifecycleOwner.lifecycleScope.launch {
//                    if (rbItemToCountries.isChecked) {
//                        // 아이템 → 나라들
//                        val countryNames = parsed.map { it.first }
//                        vm.addItemsSuspend(mapOf(head to countryNames))
//                        parsed.forEach { (country, have) ->
//                            vm.updateQuantity(head, country, needed = 0, have = have)
//                            vm.updateWeightAndPrice(head, country, weight, price)
//                        }
//                    } else {
//                        // 나라 → 아이템들
//                        val itemNames = parsed.map { it.first }
//                        val map = itemNames.associateWith { listOf(head) }
//                        vm.addItemsSuspend(map) // 링크 0으로 생성
//                        parsed.forEach { (item, have) ->
//                            vm.updateQuantity(item, head, needed = 0, have = have)
//                            vm.updateWeightAndPrice(item, head, weight, price)
//                        }
//                    }
//                }
//
//
//                Toast.makeText(requireContext(), "추가 완료!", Toast.LENGTH_SHORT).show()
//                Log.d("AddDialog", "🧾 저장 요청: $head weight=$weight price=$price")
//                dialog.dismiss()
//            }
//            .setNegativeButton("취소", null)
//            .show()
//    }

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
