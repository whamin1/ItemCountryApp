package com.bignerdranch.android.myapplication.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.entity.SheetLineEntity
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

class SheetDetailFragment : Fragment(R.layout.fragment_sheet_detail) {

    private val vm: ItemCountryViewModel by activityViewModels()
    private lateinit var adapter: LinesAdapter
    private var titleStr: String = ""
    private lateinit var rv: RecyclerView
    private var countryFilter: String? = null
    private val country: String by lazy { requireArguments().getString("country").orEmpty() }
    private val highlightItem: String by lazy { requireArguments().getString("highlightItem").orEmpty() }


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
                        requireContext().copyToClipboard("시트 내용", buildSheetText())
                        true
                    }
                    else -> false
                }
            }
        } else {
            requireActivity().title = titleStr
            requireActivity().addMenuProvider(object : androidx.core.view.MenuProvider {
                override fun onCreateMenu(
                    menu: Menu,
                    menuInflater: MenuInflater
                ) {
                    menuInflater.inflate(R.menu.menu_sheet, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean =
                    when (menuItem.itemId) {
                        R.id.action_copy_sheet -> {
                            requireContext().copyToClipboard("시트 내용", buildSheetText()); true
                        }
                        else -> false
                    }

            }, viewLifecycleOwner, androidx.lifecycle.Lifecycle.State.STARTED)
        }

        rv = view.findViewById(R.id.rv)
        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = LinesAdapter(
            onEdit = { openEditDialog(it) },
            onDelete = { confirmDeleteLine(it) }
        )
        rv.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.observeSheetLines(sheetId).collect { allLines ->
                    val filtered = countryFilter?.let { c ->
                    allLines.filter { it.country == c }
                    } ?: allLines
                    adapter.highlightItem = highlightItem.takeIf { it.isNotBlank() }
                    adapter.submit(filtered)

                    rv.postDelayed({
                        adapter.highlightItem = null
                        adapter.notifyDataSetChanged()
                    }, 2000)

                    val target = highlightItem.takeIf { it.isNotBlank() }
                    target?.let { t->
                        val idx = filtered.indexOfFirst { it.item.equals(t, ignoreCase = true) }
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
        val rows = adapter.currentList()
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

    private fun confirmDeleteLine(line: SheetLineEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle("삭제 확인")
            .setMessage("정말로 '${line.item}' 을(를) 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                vm.deleteSheetLine(line)
                Toast.makeText(requireContext(), "삭제되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun openAddDialog() {
        val sheetId = requireArguments().getLong("sheetId")
        var currentCountry = countryFilter ?: ""

        if (currentCountry.isEmpty()) {
            lifecycleScope.launch {
                vm.observeSheetLines(sheetId).collect { lines ->
                    currentCountry = lines.firstOrNull()?.country ?: currentCountry
                }
        }

        }
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_line_simple, null)
        val etItem = v.findViewById<EditText>(R.id.etItem)
        val etNeeded = v.findViewById<EditText>(R.id.etNeeded)
        val etWeight = v.findViewById<EditText>(R.id.etWeight)
        val etPrice = v.findViewById<EditText>(R.id.etPrice)

        AlertDialog.Builder(requireContext())
            .setTitle("라인 추가")
            .setView(v)
            .setPositiveButton("추가") { _,_ ->
                val newLine = SheetLineEntity(
                    sheetId = sheetId,
                    item = etItem.text.toString().trim(),
                    country = currentCountry,
                    needed = etNeeded.text.toString().toIntOrNull() ?: 0,
                    have = 0,
                    weight = etWeight.text.toString().toFloatOrNull() ?: 0f,
                    price = etPrice.text.toString().toIntOrNull() ?: 0
                )
                vm.insertSheetLine(sheetId, newLine)
                Toast.makeText(requireContext(), "라인 추가 완료!", Toast.LENGTH_SHORT).show()
                this.rv.scrollToPosition(adapter.itemCount - 1)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun openEditDialog(line: SheetLineEntity) {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_line, null)
        val etItem = v.findViewById<EditText>(R.id.etItem)
        val etCountry = v.findViewById<EditText>(R.id.etCountry)
        val etNeeded = v.findViewById<EditText>(R.id.etNeeded)
        val etHave = v.findViewById<EditText>(R.id.etHave)
        val etWeight = v.findViewById<EditText>(R.id.etWeight)
        val etPrice = v.findViewById<EditText>(R.id.etPrice)

        // 초기값 채우기
        etItem.setText(line.item)
        etCountry.setText(line.country)
        etNeeded.setText(line.needed.toString())
        etHave.setText(line.have.toString())
        etWeight.setText(line.weight.toString())
        etPrice.setText(line.price.toString())

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("라인 수정")
            .setView(v)
            .setPositiveButton("저장") { _, _ ->
                val updated = line.copy(
                    item = etItem.text.toString().trim(),
                    country = etCountry.text.toString().trim(),
                    needed = etNeeded.text.toString().toIntOrNull() ?: 0,
                    have = etHave.text.toString().toIntOrNull() ?: 0,
                    weight = etWeight.text.toString().toFloatOrNull() ?: 0f,
                    price = etPrice.text.toString().toIntOrNull() ?: 0
                )
                vm.updateSheetLine(updated)
                Toast.makeText(requireContext(), "저장되었습니다", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // 간단 어댑터
    private class LinesAdapter(
        val onEdit: (SheetLineEntity) -> Unit,
        val onDelete: (SheetLineEntity) -> Unit
    ) : RecyclerView.Adapter<VH>() {
        var highlightItem: String? = null
        private val data = mutableListOf<SheetLineEntity>()
        fun submit(list: List<SheetLineEntity>) { data.apply { clear(); addAll(list) }; notifyDataSetChanged() }

        fun currentList(): List<SheetLineEntity> = data.toList()

        override fun onCreateViewHolder(p: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_sheet_line, p, false)
            return VH(v)
        }

        override fun getItemCount() = data.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val ln = data[pos]
            h.main.text = "${ln.item} (${ln.country})"
            h.sub.text = "필요:${ln.needed}  보유:${ln.have}  무게:${ln.weight}  가격:${ln.price}"
            h.btnEdit.setOnClickListener { onEdit(ln) }
            h.btnDelete.setOnClickListener { onDelete(ln) }

            val isHighlight = highlightItem != null && ln.item.equals(highlightItem, true)
            h.itemView.setBackgroundResource(if (isHighlight) R.drawable.bg_highlight else 0)
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val main: TextView = v.findViewById(R.id.tvMain)
        val sub: TextView = v.findViewById(R.id.tvSub)
        val btnEdit: Button = v.findViewById(R.id.btnEdit)
        val btnDelete: Button = v.findViewById(R.id.btnDelete)
    }

    companion object {
        fun new(sheetId: Long, title: String) = SheetDetailFragment().apply {
            arguments = Bundle().apply {
                putLong("sheetId", sheetId)
                putString("title", title)
            }
        }
    }
}

private fun Context.copyToClipboard(label: String, text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(this, "복사되었습니다!", Toast.LENGTH_SHORT).show()
}
