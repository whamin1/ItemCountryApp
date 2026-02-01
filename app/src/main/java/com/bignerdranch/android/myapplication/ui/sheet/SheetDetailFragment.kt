package com.bignerdranch.android.myapplication.ui.sheet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
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
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val sheetId = requireArguments().getLong("sheetId")


        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        if (toolbar != null) {
            toolbar.title = titleStr
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
                    else -> false
                }
            }
        } else {
            requireActivity().title = titleStr
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
                        else -> false
                    }

            }, viewLifecycleOwner, Lifecycle.State.STARTED)
        }

        rv = view.findViewById(R.id.rv)
        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = LinesAdapter(
            onEdit = { openEditDialog(it) },
            onDelete = { confirmDeleteLine(it) },
            onToggleEnabled = { line, newEnabled ->
                vm.toggleEnabled(line.item, line.country, newEnabled)
                Toast.makeText(
                    requireContext(),
                    if (newEnabled) "활성화" else "비활성화",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
        rv.adapter = adapter
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

    private fun confirmDeleteLine(line: SheetLineEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle("삭제 확인")
            .setMessage("정말로 '${line.item}' 을(를) 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                vm.softDeleteSheetLine(line)
                Toast.makeText(requireContext(), "휴지통으로 이동", Toast.LENGTH_SHORT).show()
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
        val onEdit: (SheetLineEntity) -> Unit,
        val onDelete: (SheetLineEntity) -> Unit,
        val onToggleEnabled: (SheetLineEntity, Boolean) -> Unit,
    ) : RecyclerView.Adapter<VH>() {

        var highlightItem: String? = null
        private val data = mutableListOf<ItemCountryDao.SheetLineUi>()

        fun submit(list: List<ItemCountryDao.SheetLineUi>) {
            data.apply { clear(); addAll(list) }
            notifyDataSetChanged()
        }

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

            h.main.text = "${ln.item} (${ln.country})"
            h.sub.text = "필요:${ln.needed} 보유:${ln.have} 무게:${ln.weight} 가격:${ln.price}"

            h.btnEdit.setOnClickListener { onEdit(ln) }
            h.btnDelete.setOnClickListener { onDelete(ln) }

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
        val main: TextView = v.findViewById(R.id.tvMain)
        val sub: TextView = v.findViewById(R.id.tvSub)
        val btnEdit: Button = v.findViewById(R.id.btnEdit)
        val btnDelete: Button = v.findViewById(R.id.btnDelete)

        // ✅ 새 버튼
        val btnToggleEnabled: Button = v.findViewById(R.id.btnToggleEnabled)
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
