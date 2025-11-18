package com.bignerdranch.android.myapplication.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.navigation.fragment.findNavController
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import com.bignerdranch.android.myapplication.data.local.entity.SheetLineEntity
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import kotlin.getValue

class SheetCountriesFragment : Fragment(R.layout.fragment_country_list) {

    private lateinit var adapter: CountryAdapter
    private var sheetId: Long = 0L
    private var sheetTitle: String = ""
    private val vm: ItemCountryViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sheetId = requireArguments().getLong("sheetId")
        sheetTitle = requireArguments().getString("title").orEmpty()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        val rv = view.findViewById<RecyclerView>(R.id.rvCountries)

        toolbar.title = "$sheetTitle - 나라 선택"
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back) // 있으면
        toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        adapter = CountryAdapter { countryName ->
            // 👉 나라 클릭하면 SheetDetailFragment로 이동
            openSheetDetailForCountry(countryName)
        }
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        // DB에서 나라 목록 불러오기
        val dao = AppDatabase.get(requireContext()).itemCountryDao()

        viewLifecycleOwner.lifecycleScope.launch {
            val rows: List<ItemCountryDao.CountryRow> = dao.getCountriesBySheet(sheetId)
            adapter.submit(rows)
        }

        val fab = view.findViewById<View>(R.id.fabAddCountry)
        fab.setOnClickListener {
            showAddCountryDialog()
        }
    }

    private fun openSheetDetailForCountry(country: String) {
        val args = bundleOf(
            "sheetId" to sheetId,
            "title" to sheetTitle,
            "country" to country
        )
        findNavController().navigate(R.id.sheetDetailFragment, args)
    }

    // ───────── 어댑터 ─────────
    private class CountryAdapter(
        val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<CountryAdapter.VH>() {

        private val data = mutableListOf<ItemCountryDao.CountryRow>()

        fun submit(list: List<ItemCountryDao.CountryRow>) {
            data.apply { clear(); addAll(list) }
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val tv: TextView = v.findViewById(R.id.tvCountry)
            fun bind(row: ItemCountryDao.CountryRow) {
                tv.text = row.name
                itemView.setOnClickListener { onClick(row.name) }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_country_row, parent, false)
            return VH(v)
        }

        override fun getItemCount() = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(data[position])
        }
    }

    private fun showAddCountryDialog() {
        val sheetId = requireArguments().getLong("sheetId")
        val v = layoutInflater.inflate(R.layout.dialog_add_sheet, null)
        val etTitle = v.findViewById<EditText>(R.id.etTitle)      // 여기선 안 씀
        val etCountry = v.findViewById<EditText>(R.id.etCountry)
        val etLines = v.findViewById<EditText>(R.id.etLines)

        etTitle.visibility = View.GONE  // 이 화면에서는 시트 제목 필요 없음

        AlertDialog.Builder(requireContext())
            .setTitle("나라 추가")
            .setView(v)
            .setPositiveButton("저장") { _, _ ->
                val country = etCountry.text.toString().trim()
                val linesText = etLines.text.toString()

                if (country.isEmpty() || linesText.isEmpty()) {
                    Toast.makeText(requireContext(), "나라와 라인을 입력해주세요.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // 예: "CPT,10,1.2,5000" 형식 재사용
                val lines = linesText.split("\n").mapNotNull { line ->
                    val p = line.split(",").map { it.trim() }
                    if (p.size >= 4) SheetLineEntity(
                        sheetId = sheetId,
                        item = p[0],
                        country = country,
                        needed = p[1].toIntOrNull() ?: 0,
                        have = 0,
                        weight = p[2].toFloatOrNull() ?: 0f,
                        price = p[3].toIntOrNull() ?: 0
                    ) else null
                }

                if (lines.isEmpty()) {
                    Toast.makeText(requireContext(), "라인 형식을 확인해주세요. (예: CPT,10,1.2,5000)", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                viewLifecycleOwner.lifecycleScope.launch {
                    // VM에 insertSheetLines(sheetId, lines) 같은 함수 있으면 그거 사용
                    // 없으면 하나 추가해도 되고, forEach로 insertSheetLine 호출해도 됨
                    lines.forEach { vm.insertSheetLine(sheetId, it) }

                    Toast.makeText(requireContext(), "나라/라인 추가 완료!", Toast.LENGTH_SHORT).show()

                    // 새 나라가 추가되었으니 리스트 갱신
                    val dao = AppDatabase.get(requireContext()).itemCountryDao()
                    val rows = dao.getCountriesBySheet(sheetId)
                    adapter.submit(rows)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}