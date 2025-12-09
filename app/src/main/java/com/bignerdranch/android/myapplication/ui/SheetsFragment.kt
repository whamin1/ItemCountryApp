package com.bignerdranch.android.myapplication.ui

import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.entity.SheetEntity
import com.bignerdranch.android.myapplication.data.local.entity.SheetLineEntity
import com.bignerdranch.android.myapplication.repository.ItemCountryRepository
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

class SheetsFragment : Fragment(R.layout.fragment_sheets) {

    private val vm: ItemCountryViewModel by activityViewModels()
    private lateinit var adapter: SheetAdapter
    private var sortMode: SheetSortMode = SheetSortMode.CREATE_DESC


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.rvSheets)
        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = SheetAdapter(
            onApply = { sheet -> applySheet(sheet) },
            onToggle = { sheet -> toggleSheet(sheet) },
            onDelete = { sheet -> deleteSheet(sheet) },
            onOpen = { sheet -> openSheetDetail(sheet) },
            onEdit = { sheet -> showEditSheetDialog(sheet) }
        )
        rv.adapter = adapter

        val fab = view.findViewById<FloatingActionButton>(R.id.fabAddSheet)

        fab.setOnClickListener {
            Toast.makeText(requireContext(), "FAB 클릭됨!", Toast.LENGTH_SHORT).show()
            showAddSheetDialog()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.sheetWithLines.collect { list ->
                    val sorted = when (sortMode) {
                        SheetSortMode.CREATE_DESC -> list
                        SheetSortMode.TITLE_ASC -> list.sortedBy { it.sheet.title }
                        SheetSortMode.LINE_COUNT_DESC -> list.sortedByDescending { it.lines.size }
                    }
                    adapter.submit(list)
                }
            }
        }
        requireActivity().addMenuProvider(object : androidx.core.view.MenuProvider {
            override fun onCreateMenu(
                menu: Menu,
                menuInflater: MenuInflater
            ) {
                menuInflater.inflate(R.menu.menu_sheets, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                sortMode = when (menuItem.itemId) {
                    R.id.action_sort_created -> SheetSortMode.CREATE_DESC
                    R.id.action_sort_title -> SheetSortMode.TITLE_ASC
                    R.id.action_sort_lines -> SheetSortMode.LINE_COUNT_DESC
                    else -> return false
                }
                return true
            }
        }, viewLifecycleOwner, Lifecycle.State.STARTED)

    }

    private fun showEditSheetDialog(s: SheetEntity) {
        val v = layoutInflater.inflate(R.layout.dialog_add_sheet, null)
        val etTitle = v.findViewById<EditText>(R.id.etTitle)
        val etCountry = v.findViewById<EditText>(R.id.etCountry)
        val etLines = v.findViewById<EditText>(R.id.etLines)

        etTitle.setText(s.title)
        etCountry.visibility = View.GONE
        etLines.visibility = View.GONE

        AlertDialog.Builder(requireContext())
            .setTitle("시트 수정")
            .setView(v)
            .setPositiveButton("저장") { _, _ ->
                val newTitle = etTitle.text.toString().trim()
                if (newTitle.isEmpty()) {
                    Toast.makeText(requireContext(), "제목은 반드시 입력해주세요.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    vm.renameSheet(s.id, newTitle)
                    Toast.makeText(requireContext(), "시트 저장 완료!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
    private fun openSheetDetail(s: SheetEntity) {
        val args = bundleOf(
            "sheetId" to s.id,
            "title" to s.title
        )
        findNavController().navigate(R.id.sheetContentFragment, args)
    }


    private fun applySheet(s: SheetEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.applySheet(s.id)
            val msg = if (s.hidden)
                "‘${s.title}’ 비활성화 완료 (아이템 제거됨)"
            else
                "‘${s.title}’ 적용 완료!"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleSheet(s: SheetEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.toggleSheetHidden(s.id, !s.hidden)
        }
    }

    private fun deleteSheet(s: SheetEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.deleteSheet(s.id)
        }
    }

    private class SheetAdapter(
        val onApply: (SheetEntity) -> Unit,
        val onToggle: (SheetEntity) -> Unit,
        val onDelete: (SheetEntity) -> Unit,
        val onOpen: (SheetEntity) -> Unit,
        val onEdit: (SheetEntity) -> Unit
    ) : RecyclerView.Adapter<VH>() {
        private val data = mutableListOf<ItemCountryDao.SheetWithLines>()
        fun submit(list: List<ItemCountryDao.SheetWithLines>) { data.apply { clear(); addAll(list) }; notifyDataSetChanged() }
        override fun onCreateViewHolder(p: android.view.ViewGroup, v: Int) =
            VH(android.view.LayoutInflater.from(p.context).inflate(R.layout.item_sheet, p, false))
        override fun getItemCount() = data.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val swl = data[pos]
            val s = swl.sheet
            h.itemView.setOnClickListener { onOpen(s) }
            h.title.text = if (s.hidden) "🔕 ${s.title}" else s.title
            h.btnApply.setOnClickListener { onApply(s) }
            h.btnEdit.setOnClickListener { onEdit(s) }
            h.btnToggle.text = if (s.hidden) "활성화" else "비활성화"
            h.btnToggle.setOnClickListener { onToggle(s) }
            h.btnDelete.setOnClickListener {
                AlertDialog.Builder(h.itemView.context)
                    .setTitle("삭제 확인")
                    .setMessage("${s.title}  시트를 정말 삭제하시겠습니까?")
                    .setPositiveButton("삭제") { _, _ ->
                        onDelete(s)
                        Toast.makeText(h.itemView.context, "삭제되었습니다.", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
            h.preview.removeAllViews()
            swl.lines.take(5).forEach { ln ->
                h.preview.addView(TextView(h.itemView.context).apply {
                    text = "- ${ln.country}"
                    textSize = 14f
                    setTextColor(0xff333333.toInt())
                    setPadding(0, 4, 0, 4)
                })
            }
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvTitle)
        val btnApply: Button = v.findViewById(R.id.btnApply)
        val btnToggle: Button = v.findViewById(R.id.btnToggle)
        val btnDelete: Button = v.findViewById(R.id.btnDelete)
        val preview: LinearLayout = v.findViewById(R.id.llPreview)
        val btnEdit: Button = v.findViewById(R.id.btnEdit)
    }

    private fun showAddSheetDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_add_sheet, null)
        val etTitle = v.findViewById<EditText>(R.id.etTitle)
        val etCountry = v.findViewById<EditText>(R.id.etCountry)
        val etLines = v.findViewById<EditText>(R.id.etLines)

        AlertDialog.Builder(requireContext())
            .setTitle("새 시트 만들기")
            .setView(v)
            .setPositiveButton("저장") { _, _ ->
                val title = etTitle.text.toString().trim()
                val country = etCountry.text.toString().trim()
                val linesText = etLines.text.toString()

                if (title.isEmpty()) {
                    Toast.makeText(requireContext(), "제목은 반드시 입력해주세요.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val lines = linesText.split("\n").mapNotNull { line ->
                    val p = line.split(",").map { it.trim() }
                    if (p.size >= 4) SheetLineEntity(
                        item = p[0],
                        country = country,  // ✅ 한 번 입력한 나라를 자동으로 채움
                        needed = p[1].toIntOrNull() ?: 0,
                        have = 0,
                        weight = p[2].toFloatOrNull() ?: 0f,
                        price = p[3].toIntOrNull() ?: 0
                    ) else null
                }
//                if (lines.isEmpty()) {
//                    Toast.makeText(requireContext(), "라인 형식이 맞는지 확인해주세요. (예: 사과,10,1.2,5000)", Toast.LENGTH_SHORT).show()
//                    return@setPositiveButton
//                }

                lifecycleScope.launch {
                    vm.createSheet(title, lines)
                    Toast.makeText(requireContext(), "시트 저장 완료!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private enum class SheetSortMode{
        CREATE_DESC,
        TITLE_ASC,
        LINE_COUNT_DESC
    }

}
