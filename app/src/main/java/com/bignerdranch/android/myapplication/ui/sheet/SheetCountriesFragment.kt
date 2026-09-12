package com.bignerdranch.android.myapplication.ui.sheet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import com.bignerdranch.android.myapplication.data.local.entity.SheetLineEntity
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class SheetCountriesFragment : Fragment(R.layout.fragment_country_list) {

    private lateinit var adapter: CountryAdapter
    private var sheetId: Long = 0L
    private var sheetTitle: String = ""
    private val vm: ItemCountryViewModel by activityViewModels()
    private var sortMode: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sheetId = requireArguments().getLong("sheetId")
        sheetTitle = requireArguments().getString("title").orEmpty()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sheetId = requireArguments().getLong("sheetId")
        val sheetTitle = requireArguments().getString("title").orEmpty()

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        val rv = view.findViewById<RecyclerView>(R.id.rvCountries)
        val fab = view.findViewById<View>(R.id.fabAddCountry)

        toolbar.title = "$sheetTitle - 나라 선택"
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        //정령 메뉴
        toolbar.inflateMenu(R.menu.menu_sheet_countries)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort_asc -> {
                    sortMode = 0
                    loadCountries()
                    true
                }
                R.id.action_sort_desc -> {
                    sortMode = 1
                    loadCountries()
                    true
                }
                else -> false
            }
        }

        adapter = CountryAdapter(
            onOpen = { countryName ->
                openSheetDetailForCountry(countryName)
            },
            onToggle = { countryName, currentHidden ->
                // 🔥 나라 hidden 토글
                viewLifecycleOwner.lifecycleScope.launch {
                    vm.toggleCountryHidden(sheetId, countryName, !currentHidden)

                    // 토글 후 리스트 다시 불러오기
                    loadCountries()
                }
            },
            onEdit = { countryName -> showRenameCountryDialog(countryName) },
            onDelete = { countryName -> confirmDeleteCountry(countryName) }
        )

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter


        // 나라+라인 추가
        fab.setOnClickListener {
            showAddCountryDialog()
        }

        loadCountries()
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
        val onOpen: (String) -> Unit,
        val onToggle: (String, Boolean) -> Unit,
        val onEdit: (String) -> Unit,
        val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<CountryAdapter.VH>() {

        private val data = mutableListOf<ItemCountryDao.CountryRow>()

        fun submit(list: List<ItemCountryDao.CountryRow>) {
            data.apply {
                clear()
                addAll(list)
            }
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tvTitle)
            val btnApply: Button = v.findViewById(R.id.btnApply)
            val btnToggle: Button = v.findViewById(R.id.btnToggle)
            val btnDelete: Button = v.findViewById(R.id.btnDelete)
            val preview: LinearLayout = v.findViewById(R.id.llPreview)
            val btnEdit: Button = v.findViewById(R.id.btnEdit)


            fun bind(row: ItemCountryDao.CountryRow) {
                // 제목: 나라 이름 + 숨김 아이콘
                title.text = if (row.hidden) "🔕 ${row.name}" else row.name

                // 나라 클릭 → 해당 나라 아이템 화면으로
                itemView.setOnClickListener { onOpen(row.name) }

                // 시트용 "적용" 버튼은 여기선 쓰지 않음
                btnApply.visibility = View.GONE

                // 🔥 비활성화 / 활성화 토글 버튼
                btnToggle.text = if (row.hidden) "활성화" else "비활성화"

                btnToggle.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener

                    val newHidden = !row.hidden

                    // ✅ 즉시 UI 반영 (리스트에서 제거 X)
                    data[pos] = row.copy(hidden = newHidden)
                    notifyItemChanged(pos)

                    // ✅ DB 반영
                    onToggle(row.name, row.hidden) // row.hidden은 "이전값"이니까 콜백에서 !currentHidden로 뒤집는 구조면 OK
                }
                btnEdit.setOnClickListener {
                    onEdit(row.name)
                }

                // 나라 삭제 기능 아직 안 쓸 거면 숨겨두기
                btnDelete.visibility = View.VISIBLE
                btnDelete.text = "삭제"
                btnDelete.setOnClickListener {
                    onDelete(row.name)
                }

                // 미리보기: "아이템 N개"
                preview.visibility = View.VISIBLE
                preview.removeAllViews()

                val itemNames = row.items
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.distinct()
                    ?: emptyList()

                val show = itemNames.take(5)

                if (show.isEmpty()) {
                    preview.addView(TextView(itemView.context).apply {
                        text = "아이템 없음"
                        textSize = 14f
                        setPadding(0, 4, 0, 4)
                    })
                } else {
                    // ✅ 아이템 목록 먼저
                    show.forEach { name ->
                        preview.addView(TextView(itemView.context).apply {
                            text = "- $name"
                            textSize = 14f
                            setPadding(0, 4, 0, 4)
                        })
                    }

                    // ✅ 남은 개수 표시
                    val extraCount = (itemNames.size - show.size).coerceAtLeast(0)
                    if (extraCount > 0) {
                        preview.addView(TextView(itemView.context).apply {
                            text = "+ ${extraCount}개 더"
                            textSize = 14f
                            setTextColor(0xFF666666.toInt())
                            setPadding(0, 4, 0, 4)
                        })
                    }
                }


            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sheet, parent, false) // ✅ 시트 카드 레이아웃 재사용
            return VH(v)
        }

        override fun getItemCount() = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(data[position])
        }
    }
    //정령 함수
    private fun loadCountries() {
        val dao = AppDatabase.Companion.get(requireContext()).itemCountryDao()

        viewLifecycleOwner.lifecycleScope.launch {
            val rows: List<ItemCountryDao.CountryRow> = dao.getCountriesBySheet(sheetId)
            val sorted = when (sortMode) {
                1 -> rows.sortedByDescending { it.name }
                else -> rows.sortedBy { it.name }
            }
            adapter.submit(sorted)
        }
    }

    //나라 이름 수정
    private fun showRenameCountryDialog(oldName: String) {
        val sheetId = requireArguments().getLong("sheetId")
        val v = layoutInflater.inflate(R.layout.dialog_add_sheet, null)
        val etTitle = v.findViewById<EditText>(R.id.etTitle)
        val etCountry = v.findViewById<EditText>(R.id.etCountry)
        val etLines = v.findViewById<EditText>(R.id.etLines)

        etTitle.visibility = View.GONE
        etCountry.setText(oldName)
        etLines.visibility = View.GONE

        AlertDialog.Builder(requireContext())
            .setTitle("나라 이름 수정")
            .setView(v)
            .setPositiveButton("저장") { _, _ ->
                val newName = etCountry.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(requireContext(), "나라 이름을 입력해주세요.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                viewLifecycleOwner.lifecycleScope.launch {
                    vm.renameCountryInSheet(sheetId, oldName, newName)
                    Toast.makeText(requireContext(), "나라 이름 변경 완료!", Toast.LENGTH_SHORT).show()
                    loadCountries()
                }
            }
            .setNegativeButton("취소", null)
            .show()

    }

    private fun confirmDeleteCountry(country: String) {
        val sheetId = requireArguments().getLong("sheetId")

        AlertDialog.Builder(requireContext())
            .setTitle("나라 삭제")
            .setMessage("'$country'해당 나라를 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    vm.deleteCountryInSheet(sheetId, country)
                    Toast.makeText(requireContext(), "나라 삭제 완료!", Toast.LENGTH_SHORT).show()
                    loadCountries()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }


    // 나라 + 라인 추가 다이얼로그
    private fun showAddCountryDialog() {
        val sheetId = requireArguments().getLong("sheetId")
        val v = layoutInflater.inflate(R.layout.dialog_add_sheet, null)
        val etTitle = v.findViewById<EditText>(R.id.etTitle) // 여기선 안 씀
        val etCountry = v.findViewById<EditText>(R.id.etCountry)
        val etLines = v.findViewById<EditText>(R.id.etLines)

        etTitle.visibility = View.GONE // 이 화면에서는 시트 제목 필요 없음

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

                // 예: "CPT,10,1.2,5000"
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
                    Toast.makeText(
                        requireContext(),
                        "라인 형식을 확인해주세요. (예: CPT,10,1.2,5000)",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                viewLifecycleOwner.lifecycleScope.launch {
                    // VM에 insertSheetLine 함수 이미 있다면 그거 사용
                    lines.forEach { line ->
                        vm.insertSheetLine(sheetId, line)
                    }

                    Toast.makeText(requireContext(), "나라/라인 추가 완료!", Toast.LENGTH_SHORT).show()

                    loadCountries()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
