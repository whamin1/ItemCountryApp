package com.bignerdranch.android.myapplication.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.entity.SheetEntity
import com.bignerdranch.android.myapplication.data.local.entity.SheetLineEntity
import com.bignerdranch.android.myapplication.repository.ItemCountryRepository
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import kotlinx.coroutines.launch

class SheetsFragment : Fragment(R.layout.fragment_sheets) {

    private val vm: ItemCountryViewModel by activityViewModels()
    private lateinit var adapter: SheetAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.rvSheets)
        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = SheetAdapter(
            onApply = { sheet -> applySheet(sheet) },
            onToggle = { sheet -> toggleSheet(sheet) },
            onDelete = { sheet -> deleteSheet(sheet) }
        )
        rv.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.sheetWithLines.collect { sheets ->
                    adapter.submit(sheets)
                }
            }



            view.findViewById<View?>(R.id.fabAddSheet)?.setOnClickListener {
                showAddSheetDialog()
            }
        }
    }

    private fun applySheet(s: SheetEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.repository.applySheet(s.id)
            Toast.makeText(requireContext(), "‘${s.title}’ 적용 완료!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleSheet(s: SheetEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.repository.toggleSheetHidden(s.id, !s.hidden)
        }
    }

    private fun deleteSheet(s: SheetEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.repository.deleteSheet(s.id)
        }
    }

    private class SheetAdapter(
        val onApply: (SheetEntity) -> Unit,
        val onToggle: (SheetEntity) -> Unit,
        val onDelete: (SheetEntity) -> Unit
    ) : RecyclerView.Adapter<VH>() {
        private val data = mutableListOf<ItemCountryDao.SheetWithLines>()
        fun submit(list: List<ItemCountryDao.SheetWithLines>) { data.apply { clear(); addAll(list) }; notifyDataSetChanged() }
        override fun onCreateViewHolder(p: android.view.ViewGroup, v: Int) =
            VH(android.view.LayoutInflater.from(p.context).inflate(R.layout.item_sheet, p, false))
        override fun getItemCount() = data.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val swl = data[pos]
            val s = swl.sheet
            h.title.text = if (s.hidden) "🔕 ${s.title}" else s.title
            h.btnApply.setOnClickListener { onApply(s) }
            h.btnToggle.text = if (s.hidden) "활성화" else "비활성화"
            h.btnToggle.setOnClickListener { onToggle(s) }
            h.btnDelete.setOnClickListener { onDelete(s) }
            h.preview.removeAllViews()
            swl.lines.forEach { ln ->
                h.preview.addView(makeLineView(h.itemView, ln))
            }
        }

        private fun makeLineView(p: View, ln: SheetLineEntity): View{
            val tv = TextView(p.context).apply {
                text = "- ${ln.item}/${ln.needed}"
            }
            return tv
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvTitle)
        val btnApply: Button = v.findViewById(R.id.btnApply)
        val btnToggle: Button = v.findViewById(R.id.btnToggle)
        val btnDelete: Button = v.findViewById(R.id.btnDelete)
        val preview: LinearLayout = v.findViewById(R.id.llPreview)
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

                if (title.isEmpty() || country.isEmpty() || linesText.isEmpty()) {
                    Toast.makeText(requireContext(), "모두 입력해주세요.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val lines = linesText.split("\n").mapNotNull { line ->
                    val p = line.split(",").map { it.trim() }
                    if (p.size >= 5) SheetLineEntity(
                        item = p[0],
                        country = country,  // ✅ 한 번 입력한 나라를 자동으로 채움
                        needed = p[1].toIntOrNull() ?: 0,
                        have = p[2].toIntOrNull() ?: 0,
                        weight = p[3].toFloatOrNull() ?: 0f,
                        price = p[4].toIntOrNull() ?: 0
                    ) else null
                }

                lifecycleScope.launch {
                    vm.repository.createSheet(title, lines)
                    Toast.makeText(requireContext(), "시트 저장 완료!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

}
