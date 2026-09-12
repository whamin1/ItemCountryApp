package com.bignerdranch.android.myapplication.ui.sheet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.entity.SheetLineEntity
import com.bignerdranch.android.myapplication.repository.BulkSheetLineInput
import com.bignerdranch.android.myapplication.repository.BulkSheetSyncSummary
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SheetDetailFragment : Fragment(R.layout.fragment_sheet_detail) {

    private val vm: ItemCountryViewModel by activityViewModels()
    private lateinit var adapter: LinesAdapter
    private var titleStr: String = ""
    private lateinit var rv: RecyclerView
    private var countryFilter: String? = null
    private val country: String by lazy { requireArguments().getString("country").orEmpty() }
    private val highlightItem: String by lazy { requireArguments().getString("highlightItem").orEmpty() }

    private var cachedItems: Set<String> = emptySet()
    private var cachedCountries: Set<String> = emptySet()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        titleStr = requireArguments().getString("title").orEmpty()
        countryFilter = arguments?.getString("country")
    }

    private fun screenTitle(): String {
        val countryName = countryFilter?.takeIf { it.isNotBlank() }
        return if (countryName != null && titleStr.isNotBlank()) "$titleStr ($countryName)"
        else countryName ?: titleStr
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val sheetId = requireArguments().getLong("sheetId")


        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        if (toolbar != null) {
            toolbar.title = screenTitle()
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_copy_sheet -> {
                        requireContext().copyToClipboard("시트 내용", buildSheetCopyTextForPaste())
                        true
                    }
                    R.id.action_trash -> {
                        val sheetId = requireArguments().getLong("sheetId")
                        val title = requireArguments().getString("title").orEmpty()
                        val country = arguments?.getString("country")

                        findNavController().navigate(
                            R.id.trashFragment,
                            Bundle().apply {
                                putLong("sheetId", sheetId)
                                putString("title", title)
                                putString("country", country)
                            }
                        )
                        true
                    }
                    R.id.action_bulk_sync -> {
                        showBulkSyncDialog()
                        true
                    }
                    R.id.action_delete_items -> {
                        showMoveLinesToTrashDialog()
                        true
                    }
                    else -> false
                }
            }
        } else {
            requireActivity().title = screenTitle()
            requireActivity().addMenuProvider(object : MenuProvider {
                override fun onCreateMenu(
                    menu: Menu,
                    menuInflater: MenuInflater
                ) {
                    menuInflater.inflate(R.menu.menu_sheet, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean =
                    when (menuItem.itemId) {
                        R.id.action_copy_sheet -> {
                            requireContext().copyToClipboard("시트 내용", buildSheetCopyTextForPaste()); true
                        }
                        R.id.action_bulk_sync -> {
                            showBulkSyncDialog(); true
                        }
                        else -> false
                    }

            }, viewLifecycleOwner, Lifecycle.State.STARTED)
        }

        rv = view.findViewById(R.id.rv)
        rv.layoutManager = LinearLayoutManager(requireContext())
        val fabSaveChanges = view.findViewById<ExtendedFloatingActionButton>(R.id.fabSaveChanges)
        adapter = LinesAdapter(
            onPendingCountChanged = { count ->
                fabSaveChanges.isEnabled = count > 0
                fabSaveChanges.text = if (count > 0) "변경 저장 ($count)" else "변경 저장"
            },
            onToggleEnabled = { line, newEnabled ->
                vm.toggleEnabled(line.item, line.country, newEnabled)
                Toast.makeText(
                    requireContext(),
                    if (newEnabled) "활성화" else "비활성화",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
        adapter.showCountryInRow = countryFilter.isNullOrBlank()
        rv.adapter = adapter
        fabSaveChanges.setOnClickListener {
            val changes = adapter.pendingChanges()
            if (changes.isEmpty()) return@setOnClickListener
            changes.forEach { (old, updated) ->
                vm.updateSheetLineAndApplyHome(old = old, new = updated)
            }
            adapter.clearPendingChanges()
            Toast.makeText(requireContext(), "${changes.size}개 항목을 수정했습니다.", Toast.LENGTH_SHORT).show()
        }
        attachLineReorder(sheetId)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.observeActiveLinesUi(sheetId).collect { rows ->
                    val filtered = countryFilter?.let { c ->
                        rows.filter { it.line.country == c }
                    } ?: rows

                    adapter.highlightItem = highlightItem.takeIf { it.isNotBlank() }
                    adapter.submit(filtered)

                    rv.postDelayed({
                        adapter.highlightItem = null
                        adapter.notifyDataSetChanged()
                    }, 2000)

                    val target = highlightItem.takeIf { it.isNotBlank() }
                    target?.let { t ->
                        val idx = filtered.indexOfFirst { it.line.item.equals(t, ignoreCase = true) }
                        if (idx != -1) {
                            rv.post {
                                (rv.layoutManager as LinearLayoutManager)
                                    .scrollToPositionWithOffset(idx, 0)
                            }
                        }
                    }
                }
            }
        }


        val fab = view.findViewById<FloatingActionButton>(R.id.fabAddLine)
        fab.setOnClickListener { openAddDialog() }
    }

    private fun buildSheetText(): String {
        val rows = adapter.currentLines()
        if (rows.isEmpty()) return "비어 있음"

        val title = if (titleStr.isNotBlank()) titleStr
        else view?.findViewById<MaterialToolbar>(R.id.toolbar)?.title?.toString().orEmpty()

        return buildString {
            if (title.isNotBlank()) appendLine("[$title]")
            rows.forEach { ln ->
                val w = ln.weight.takeIf { it > 0f }?.let { "${it}kg" } ?: "-"
                val p = ln.price.takeIf { it > 0 }?.let { "${it}원" } ?: "-"
                appendLine("${ln.item} (${ln.country}) / 필요:${ln.needed} / 보유:${ln.have} / 무게:$w / 가격:$p")
            }
        }
    }

    private fun buildSheetCopyTextForPaste(): String {
        val rows = adapter.currentLines()
        if (rows.isEmpty()) return ""

        return buildString {
            // 원하면 헤더도 빼버리자 (지금은 안 넣음)
            rows.forEach { ln ->
                val item = ln.item.trim()
                val needed = ln.needed
                val weight = ln.weight
                val price = ln.price
                appendLine("$item,$needed,$weight,$price")
            }
        }.trimEnd()
    }

    private fun showBulkSyncDialog() {
        val sheetId = requireArguments().getLong("sheetId")
        val currentCountry = countryFilter?.trim().orEmpty()
        if (currentCountry.isBlank()) {
            Toast.makeText(
                requireContext(),
                "나라별 상세 화면에서 사용할 수 있어.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (adapter.hasPendingChanges()) {
            Toast.makeText(
                requireContext(),
                "먼저 화면의 변경 저장 버튼을 눌러줘.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val content = layoutInflater.inflate(R.layout.dialog_bulk_sheet_sync, null)
        val etLines = content.findViewById<EditText>(R.id.etLines).apply {
            hint = "아이템, 필요, 무게, 가격"
            setText(buildSheetCopyTextForPaste())
            setSelection(text.length)
            clearFocus()
        }

        val inputDialog = AlertDialog.Builder(requireContext())
            .setTitle("$currentCountry 아이템 일괄 동기화")
            .setMessage("한 줄에 아이템, 필요, 무게, 가격을 입력해줘.\n목록에서 빠진 기존 아이템은 비활성화돼.")
            .setView(content)
            .setPositiveButton("변경 확인", null)
            .setNegativeButton("취소", null)
            .create()

        inputDialog.setOnShowListener {
            inputDialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            )
            inputDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val inputs = try {
                    parseBulkSheetLines(etLines.text.toString())
                } catch (e: IllegalArgumentException) {
                    etLines.error = e.message
                    return@setOnClickListener
                }

                val checkButton = inputDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                checkButton.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val preview = vm.previewBulkSheetLines(
                            sheetId = sheetId,
                            country = currentCountry,
                            inputs = inputs
                        )
                        showBulkSyncConfirmation(
                            inputDialog = inputDialog,
                            sheetId = sheetId,
                            country = currentCountry,
                            inputs = inputs,
                            preview = preview
                        )
                    } catch (e: Exception) {
                        Toast.makeText(
                            requireContext(),
                            e.message ?: "변경 내용을 확인하지 못했어.",
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        checkButton.isEnabled = true
                    }
                }
            }
        }
        inputDialog.show()
    }

    private fun parseBulkSheetLines(text: String): List<BulkSheetLineInput> {
        val parsed = mutableListOf<BulkSheetLineInput>()
        val seenItems = mutableSetOf<String>()

        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEachIndexed

            val parts = (if ('\t' in rawLine) rawLine.split('\t') else rawLine.split(','))
                .map { it.trim() }
            if (parts.size != 4) {
                throw IllegalArgumentException(
                    "${index + 1}번째 줄 형식을 확인해줘.\n예: CPT,10,1.2,5000"
                )
            }

            val item = parts[0]
            val needed = parts[1].toIntOrNull()
            val weight = parts[2].toFloatOrNull()
            val price = parts[3].toIntOrNull()
            if (item.isBlank() || needed == null || weight == null || price == null ||
                needed < 0 || weight < 0f || price < 0
            ) {
                throw IllegalArgumentException("${index + 1}번째 줄의 이름이나 숫자를 확인해줘.")
            }

            val key = item.lowercase(java.util.Locale.ROOT)
            if (!seenItems.add(key)) {
                throw IllegalArgumentException("${index + 1}번째 줄에 중복 아이템이 있어: $item")
            }
            parsed += BulkSheetLineInput(item, needed, weight, price)
        }

        if (parsed.isEmpty()) {
            throw IllegalArgumentException("아이템을 한 개 이상 입력해줘.")
        }
        return parsed
    }

    private fun showBulkSyncConfirmation(
        inputDialog: AlertDialog,
        sheetId: Long,
        country: String,
        inputs: List<BulkSheetLineInput>,
        preview: BulkSheetSyncSummary
    ) {
        val message = buildString {
            appendLine("입력 ${inputs.size}개")
            appendLine()
            appendLine("추가 ${preview.added}개")
            appendLine("수정 ${preview.modified}개")
            appendLine("다시 활성화 ${preview.reactivated}개")
            appendLine("비활성화 ${preview.deactivated}개")
            append("변경 없음 ${preview.unchanged}개")
        }

        if (!preview.hasChanges) {
            AlertDialog.Builder(requireContext())
                .setTitle("변경 사항 없음")
                .setMessage(message)
                .setPositiveButton("확인", null)
                .show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("이대로 동기화할까?")
            .setMessage(message)
            .setPositiveButton("적용") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val result = vm.applyBulkSheetLines(sheetId, country, inputs)
                        adapter.clearPendingChanges()
                        inputDialog.dismiss()
                        Toast.makeText(
                            requireContext(),
                            "추가 ${result.added} · 수정 ${result.modified} · 비활성 ${result.deactivated}",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (e: Exception) {
                        Toast.makeText(
                            requireContext(),
                            e.message ?: "일괄 동기화에 실패했어.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showMoveLinesToTrashDialog() {
        val lines = adapter.currentLines()
        if (lines.isEmpty()) {
            Toast.makeText(requireContext(), "삭제할 아이템이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = lines.map { "${it.item} (${it.country})" }
        val checked = BooleanArray(lines.size)

        AlertDialog.Builder(requireContext())
            .setTitle("휴지통으로 보낼 아이템 선택")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("휴지통으로") { _, _ ->
                val selected = lines.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) {
                    Toast.makeText(requireContext(), "선택한 아이템이 없습니다.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                selected.forEach(vm::softDeleteSheetLine)
                Toast.makeText(
                    requireContext(),
                    "${selected.size}개 아이템을 휴지통으로 보냈습니다.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun openAddDialog() {
        val sheetId = requireArguments().getLong("sheetId")

        viewLifecycleOwner.lifecycleScope.launch {
            val currentCountry = if (!countryFilter.isNullOrBlank()) {
                countryFilter!!
            } else {
                val lines = vm.observeSheetLines(sheetId).first()
                lines.firstOrNull()?.country.orEmpty()
            }

            showAddDialog(sheetId, currentCountry)
        }
    }

    private fun showAddDialog(sheetId: Long, currentCountry: String) {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_line_simple, null)
        val etItem = v.findViewById<EditText>(R.id.etItem)
        val etNeeded = v.findViewById<EditText>(R.id.etNeeded)
        val etWeight = v.findViewById<EditText>(R.id.etWeight)
        val etPrice = v.findViewById<EditText>(R.id.etPrice)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("라인 추가")
            .setView(v)
            .setPositiveButton("추가", null)
            .setNegativeButton("취소", null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val nextOrder = vm.getNextSortOrder(sheetId)

                val newLine = SheetLineEntity(
                    sheetId = sheetId,
                    item = etItem.text.toString().trim(),
                    country = currentCountry,
                    needed = etNeeded.text.toString().toIntOrNull() ?: 0,
                    have = 0,
                    weight = etWeight.text.toString().toFloatOrNull() ?: 0f,
                    price = etPrice.text.toString().toIntOrNull() ?: 0,
                    sortOrder = nextOrder
                )

                vm.insertSheetLine(sheetId, newLine)
                Toast.makeText(requireContext(), "라인 추가 완료!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
    }

    private fun openEditDialog(line: SheetLineEntity) {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_line, null)
        val etItem = v.findViewById<AutoCompleteTextView>(R.id.etItem)
        val etCountry = v.findViewById<AutoCompleteTextView>(R.id.etCountry)
        val etNeeded = v.findViewById<EditText>(R.id.etNeeded)
        val etHave = v.findViewById<EditText>(R.id.etHave)
        val etWeight = v.findViewById<EditText>(R.id.etWeight)
        val etPrice = v.findViewById<EditText>(R.id.etPrice)

        // 초기값 채우기
        etItem.setText(line.item, false)
        etCountry.setText(line.country, false)
        etNeeded.setText(line.needed.toString())
        etHave.setText(line.have.toString())
        etWeight.setText(line.weight.toString())
        etPrice.setText(line.price.toString())

        // ✅ 목록 로드 + 어댑터 연결
        viewLifecycleOwner.lifecycleScope.launch {
            val items = vm.getAllItemNamesOnce()
            val countries = vm.getAllCountryNamesOnce()

            cachedItems = items.map { it.trim() }.toSet()
            cachedCountries = countries.map { it.trim() }.toSet()

            etItem.setAdapter(
                ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_list_item_1,
                    items
                )
            )
            etCountry.setAdapter(
                ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_list_item_1,
                    countries
                )
            )

            // 눌렀을 때 바로 드롭다운
            etItem.setOnClickListener { etItem.showDropDown() }
            etCountry.setOnClickListener { etCountry.showDropDown() }
        }



        AlertDialog.Builder(requireContext())
            .setTitle("라인 수정")
            .setView(v)
            .setPositiveButton("저장") { _, _ ->
                val newItem = etItem.text.toString().trim()
                val newCountry = etCountry.text.toString().trim()

                val itemOk = cachedItems.contains(newItem)
                val countryOk = cachedCountries.contains(newCountry)

                val updated = line.copy(
                    item = newItem,
                    country = newCountry,
                    needed = etNeeded.text.toString().toIntOrNull() ?: 0,
                    have = etHave.text.toString().toIntOrNull() ?: 0,
                    weight = etWeight.text.toString().toFloatOrNull() ?: 0f,
                    price = etPrice.text.toString().toIntOrNull() ?: 0
                )

                if (!itemOk || !countryOk) {
                    val msg = buildString {
                        if (!itemOk) appendLine("• 아이템이 기존 목록에 없습니다: \"$newItem\"")
                        if (!countryOk) appendLine("• 나라가 기존 목록에 없습니다: \"$newCountry\"")
                        appendLine()
                        append("그래도 저장할까요? (오타일 수 있어요)")
                    }

                    AlertDialog.Builder(requireContext())
                        .setTitle("확인 필요")
                        .setMessage(msg)
                        .setPositiveButton("그래도 저장") { _, _ ->
                            vm.updateSheetLineAndApplyHome(old = line, new = updated)
                            Toast.makeText(requireContext(), "저장되었습니다", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("목록에서 다시 선택", null)
                        .show()

                    return@setPositiveButton
                }

                vm.updateSheetLineAndApplyHome(old = line, new = updated)
                Toast.makeText(requireContext(), "저장되었습니다", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()

    }

    // 간단 어댑터
    private class LinesAdapter(
        val onPendingCountChanged: (Int) -> Unit,
        val onToggleEnabled: (SheetLineEntity, Boolean) -> Unit,
    ) : RecyclerView.Adapter<VH>() {

        var highlightItem: String? = null
        var showCountryInRow: Boolean = true
        private val data = mutableListOf<ItemCountryDao.SheetLineUi>()
        private val drafts = linkedMapOf<Long, Pair<SheetLineEntity, SheetLineEntity>>()

        fun submit(list: List<ItemCountryDao.SheetLineUi>) {
            val visibleIds = list.mapTo(mutableSetOf()) { it.line.id }
            drafts.keys.retainAll(visibleIds)
            data.apply { clear(); addAll(list) }
            onPendingCountChanged(drafts.size)
            notifyDataSetChanged()
        }

        fun pendingChanges(): List<Pair<SheetLineEntity, SheetLineEntity>> = drafts.values.toList()

        fun clearPendingChanges() {
            drafts.clear()
            onPendingCountChanged(0)
            notifyDataSetChanged()
        }

        fun hasPendingChanges(): Boolean = drafts.isNotEmpty()

        fun currentLines(): List<SheetLineEntity> = data.map { it.line }

        fun move(from: Int, to: Int) {
            val item = data.removeAt(from)
            data.add(to, item)
            notifyItemMoved(from, to)
        }

        override fun onCreateViewHolder(p: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_sheet_line, p, false)
            return VH(v)
        }

        override fun getItemCount() = data.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val row = data[pos]
            val ln = row.line
            val enabled = row.enabled == 1

            h.bind(
                original = ln,
                initial = drafts[ln.id]?.second ?: ln,
                showCountry = showCountryInRow
            ) { edited ->
                if (edited == null || edited == ln) drafts.remove(ln.id)
                else drafts[ln.id] = ln to edited
                onPendingCountChanged(drafts.size)
            }
            // ✅ 토글 버튼
            h.btnToggleEnabled.text = if (enabled) "활성" else "비활성"
            h.btnToggleEnabled.setOnClickListener { onToggleEnabled(ln, !enabled) }

            // ✅ 비활성 시 흐리게
            h.itemView.alpha = if (enabled) 1.0f else 0.45f

            val isHighlight = highlightItem != null && ln.item.equals(highlightItem, true)
            h.itemView.setBackgroundResource(if (isHighlight) R.drawable.bg_highlight else 0)
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val etItem: EditText = v.findViewById(R.id.etInlineItem)
        private val tvCountry: TextView = v.findViewById(R.id.tvInlineCountry)
        private val etNeeded: EditText = v.findViewById(R.id.etInlineNeeded)
        private val etHave: EditText = v.findViewById(R.id.etInlineHave)
        private val etWeight: EditText = v.findViewById(R.id.etInlineWeight)
        private val etPrice: EditText = v.findViewById(R.id.etInlinePrice)
        val btnToggleEnabled: Button = v.findViewById(R.id.btnToggleEnabled)

        private val watchers = mutableListOf<Pair<EditText, TextWatcher>>()

        fun bind(
            original: SheetLineEntity,
            initial: SheetLineEntity,
            showCountry: Boolean,
            onDraftChanged: (SheetLineEntity?) -> Unit
        ) {
            watchers.forEach { (edit, watcher) -> edit.removeTextChangedListener(watcher) }
            watchers.clear()

            etItem.setText(initial.item)
            tvCountry.text = original.country
            tvCountry.visibility = if (showCountry) View.VISIBLE else View.GONE
            etNeeded.setText(initial.needed.toString())
            etHave.setText(initial.have.toString())
            etWeight.setText(initial.weight.toString())
            etPrice.setText(initial.price.toString())

            fun editedLineOrNull(): SheetLineEntity? {
                val item = etItem.text.toString().trim()
                val needed = etNeeded.text.toString().toIntOrNull()
                val have = etHave.text.toString().toIntOrNull()
                val weight = etWeight.text.toString().toFloatOrNull()
                val price = etPrice.text.toString().toIntOrNull()

                if (item.isBlank() || needed == null || have == null || weight == null || price == null) {
                    return null
                }
                return original.copy(
                    item = item,
                    needed = needed,
                    have = have,
                    weight = weight,
                    price = price
                )
            }

            listOf(etItem, etNeeded, etHave, etWeight, etPrice).forEach { edit ->
                val watcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) = onDraftChanged(editedLineOrNull())
                }
                edit.addTextChangedListener(watcher)
                watchers += edit to watcher
            }

        }
    }

    companion object {
        fun new(sheetId: Long, title: String) = SheetDetailFragment().apply {
            arguments = Bundle().apply {
                putLong("sheetId", sheetId)
                putString("title", title)
            }
        }
    }

    private fun attachLineReorder(sheetId: Long) {
        val touchHelper = androidx.recyclerview.widget.ItemTouchHelper(
            object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN,
                0
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val from = viewHolder.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    adapter.move(from, to)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    // ✅ 드래그 끝나면 DB 저장
                    vm.saveLineOrder(adapter.currentLines())
                    Toast.makeText(requireContext(), "순서 저장됨", Toast.LENGTH_SHORT).show()
                }
            }
        )
        touchHelper.attachToRecyclerView(rv)
    }
}

private fun Context.copyToClipboard(label: String, text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(this, "복사되었습니다!", Toast.LENGTH_SHORT).show()
}
