package com.bignerdranch.android.myapplication.ui.sheet

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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
import kotlinx.coroutines.launch

class TrashFragment : Fragment(R.layout.fragment_trash) {

    private val vm: ItemCountryViewModel by activityViewModels()
    private lateinit var rv: RecyclerView
    private lateinit var adapter: TrashLinesAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val sheetId = requireArguments().getLong("sheetId")

        rv = view.findViewById(R.id.rvTrash)
        rv.layoutManager = LinearLayoutManager(requireContext())

        adapter = TrashLinesAdapter(
            onRestore = { line ->
                vm.restoreSheetLine(line)
                Toast.makeText(requireContext(), "복원했습니다", Toast.LENGTH_SHORT).show()
            },
            onHardDelete = { line ->
                AlertDialog.Builder(requireContext())
                    .setTitle("완전 삭제")
                    .setMessage("정말로 '${line.item}' 을(를) 완전히 삭제할까요? (복구 불가)")
                    .setPositiveButton("삭제") { _, _ ->
                        vm.hardDeleteSheetLine(line)
                        Toast.makeText(requireContext(), "완전 삭제했습니다", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        )
        rv.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.observeDeletedLines(sheetId).collect { deleted ->
                    adapter.submit(deleted)
                }
            }
        }
    }

    private class TrashLinesAdapter(
        val onRestore: (SheetLineEntity) -> Unit,
        val onHardDelete: (SheetLineEntity) -> Unit
    ) : RecyclerView.Adapter<VH>() {

        private val data = mutableListOf<SheetLineEntity>()

        fun submit(list: List<SheetLineEntity>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(p: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_sheet_line, p, false)
            return VH(v)
        }

        override fun getItemCount() = data.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val ln = data[pos]
            h.main.text = "${ln.item} (${ln.country})"
            h.sub.text = "필요:${ln.needed} 보유:${ln.have} 무게:${ln.weight} 가격:${ln.price}"

            // 기존 버튼 재활용
            h.btnEdit.text = "복원"
            h.btnDelete.text = "완전삭제"

            h.btnEdit.setOnClickListener { onRestore(ln) }
            h.btnDelete.setOnClickListener { onHardDelete(ln) }
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val main: TextView = v.findViewById(R.id.tvMain)
        val sub: TextView = v.findViewById(R.id.tvSub)
        val btnEdit: Button = v.findViewById(R.id.btnEdit)
        val btnDelete: Button = v.findViewById(R.id.btnDelete)
    }

    companion object {
        fun new(sheetId: Long): TrashFragment = TrashFragment().apply {
            arguments = Bundle().apply { putLong("sheetId", sheetId) }
        }
    }
}