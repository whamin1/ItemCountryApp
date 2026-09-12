package com.bignerdranch.android.myapplication.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.data.local.dao.ItemCountryDao
import com.bignerdranch.android.myapplication.data.local.db.AppDatabase
import com.bignerdranch.android.myapplication.data.local.entity.CategoryEntity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val dao by lazy { AppDatabase.get(requireContext()).itemCountryDao() }
    private val pending = linkedMapOf<String, Long?>()
    private var rows: List<ItemCountryDao.ItemCategoryRow> = emptyList()
    private var categories: List<CategoryEntity> = emptyList()
    private lateinit var adapter: CategoryItemAdapter
    private lateinit var saveButton: ExtendedFloatingActionButton
    private lateinit var emptyView: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var indexBar: TextView
    private lateinit var countSummary: TextView
    private lateinit var filterGroup: ChipGroup
    private var categoryFilterEnabled = false
    private var selectedCategoryId: Long? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recycler = view.findViewById(R.id.categoryRecycler)
        indexBar = view.findViewById(R.id.categoryIndexBar)
        countSummary = view.findViewById(R.id.tvCategoryCountSummary)
        filterGroup = view.findViewById(R.id.categoryFilterGroup)
        emptyView = view.findViewById(R.id.tvCategoryEmpty)
        saveButton = view.findViewById(R.id.fabSaveCategories)

        adapter = CategoryItemAdapter { row -> showCategoryPicker(row) }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        setupIndexBar()

        view.findViewById<MaterialToolbar>(R.id.categoryToolbar).setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_add_category) {
                showAddCategoryDialog()
                true
            } else false
        }

        view.findViewById<SearchView>(R.id.categorySearch)
            .setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false
                override fun onQueryTextChange(newText: String?): Boolean {
                    adapter.setQuery(newText.orEmpty())
                    updateEmptyState()
                    updateIndexBar()
                    return true
                }
            })

        saveButton.setOnClickListener { saveChanges() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    dao.observeItemCategoryRows(),
                    dao.observeItemCategories()
                ) { itemRows, categoryRows -> itemRows to categoryRows }
                    .collect { (itemRows, categoryRows) ->
                        rows = itemRows
                        categories = categoryRows
                        adapter.submit(rows, categories, pending)
                        renderCategoryFilters()
                        updateEmptyState()
                        updateIndexBar()
                    }
            }
        }
    }

    private fun showCategoryPicker(row: ItemCountryDao.ItemCategoryRow) {
        val selectedId = if (pending.containsKey(row.itemName)) pending[row.itemName] else row.categoryId
        val labels = listOf("미분류") + categories.map { it.name }
        val checked = categories.indexOfFirst { it.id == selectedId }.let { if (it < 0) 0 else it + 1 }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(row.itemName)
            .setSingleChoiceItems(labels.toTypedArray(), checked, null)
            .setNegativeButton("취소", null)
            .create()

        dialog.setOnShowListener {
            dialog.listView.setOnItemClickListener { _, _, position, _ ->
                val newCategoryId = if (position == 0) null else categories[position - 1].id
                if (newCategoryId == row.categoryId) pending.remove(row.itemName)
                else pending[row.itemName] = newCategoryId
                adapter.submit(rows, categories, pending)
                renderCategoryFilters()
                updateSaveButton()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showAddCategoryDialog() {
        val input = EditText(requireContext()).apply {
            hint = "분류 이름"
            isSingleLine = true
        }
        val margin = (24 * resources.displayMetrics.density).toInt()
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("분류 추가")
            .setView(input, margin, 0, margin, 0)
            .setPositiveButton("추가", null)
            .setNegativeButton("취소", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    input.error = "이름을 입력하세요."
                    return@setOnClickListener
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = dao.insertItemCategory(CategoryEntity(name = name))
                    if (result == -1L) Toast.makeText(requireContext(), "이미 있는 분류입니다.", Toast.LENGTH_SHORT).show()
                    else dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun saveChanges() {
        if (pending.isEmpty()) return
        val changes = pending.toMap()
        viewLifecycleOwner.lifecycleScope.launch {
            dao.applyItemCategoryChanges(changes)
            pending.clear()
            adapter.submit(rows, categories, pending)
            renderCategoryFilters()
            updateSaveButton()
            Toast.makeText(requireContext(), "${changes.size}개 아이템의 분류를 수정했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSaveButton() {
        saveButton.isEnabled = pending.isNotEmpty()
        saveButton.text = if (pending.isEmpty()) "수정하기" else "수정하기 (${pending.size})"
    }

    private fun updateEmptyState() {
        emptyView.isVisible = adapter.itemCount == 0
    }

    private fun renderCategoryFilters() {
        val effectiveIds = rows.associate { row ->
            row.itemName to if (pending.containsKey(row.itemName)) pending[row.itemName] else row.categoryId
        }
        val classifiedCount = effectiveIds.values.count { it != null }
        val unclassifiedCount = effectiveIds.size - classifiedCount
        countSummary.text =
            "전체 ${rows.size}개 · 분류 ${classifiedCount}개 · 미분류 ${unclassifiedCount}개"

        filterGroup.removeAllViews()
        addFilterChip("전체 ${rows.size}", !categoryFilterEnabled) {
            categoryFilterEnabled = false
            selectedCategoryId = null
        }
        addFilterChip("미분류 $unclassifiedCount", categoryFilterEnabled && selectedCategoryId == null) {
            categoryFilterEnabled = true
            selectedCategoryId = null
        }
        categories.forEach { category ->
            val count = effectiveIds.values.count { it == category.id }
            addFilterChip(
                "${category.name} $count",
                categoryFilterEnabled && selectedCategoryId == category.id
            ) {
                categoryFilterEnabled = true
                selectedCategoryId = category.id
            }
        }
    }

    private fun addFilterChip(label: String, checked: Boolean, onSelected: () -> Unit) {
        val chip = Chip(requireContext()).apply {
            id = View.generateViewId()
            text = label
            isCheckable = true
            isChecked = checked
            setOnClickListener {
                onSelected()
                adapter.setCategoryFilter(categoryFilterEnabled, selectedCategoryId)
                updateEmptyState()
                updateIndexBar()
            }
        }
        filterGroup.addView(chip)
    }

    private fun updateIndexBar() {
        indexBar.text = adapter.availableSections().joinToString("\n")
        indexBar.isVisible = adapter.itemCount > 0
        fitIndexBarLineSpacing()
    }

    private fun fitIndexBarLineSpacing() {
        indexBar.doOnLayout {
            val lineCount = indexBar.text.toString()
                .split("\n")
                .count { it.isNotEmpty() }
            if (lineCount <= 1) {
                indexBar.setLineSpacing(0f, 1f)
                return@doOnLayout
            }

            val fontMetrics = indexBar.paint.fontMetricsInt
            val characterHeight = fontMetrics.bottom - fontMetrics.top
            val availableHeight = indexBar.height - indexBar.paddingTop - indexBar.paddingBottom
            val extraSpacing = (
                    (availableHeight - characterHeight * lineCount).toFloat() /
                            (lineCount - 1)
                    ).coerceAtLeast(0f)
            indexBar.setLineSpacing(extraSpacing, 1f)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupIndexBar() {
        indexBar.setOnClickListener { }
        indexBar.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val sections = adapter.availableSections()
                    if (sections.isEmpty()) return@setOnTouchListener false
                    val height = view.height.toFloat().coerceAtLeast(1f)
                    val sectionHeight = height / sections.size
                    val index = (event.y.coerceIn(0f, height - 1f) / sectionHeight)
                        .toInt().coerceIn(sections.indices)
                    val localRatio = ((event.y - index * sectionHeight) / sectionHeight)
                        .coerceIn(0f, 1f)
                    adapter.positionOfSection(sections[index], localRatio)?.let { position ->
                        (recycler.layoutManager as LinearLayoutManager)
                            .scrollToPositionWithOffset(position, 0)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private class CategoryItemAdapter(
        private val onChoose: (ItemCountryDao.ItemCategoryRow) -> Unit
    ) : RecyclerView.Adapter<CategoryItemAdapter.VH>() {
        private var allRows: List<ItemCountryDao.ItemCategoryRow> = emptyList()
        private var shownRows: List<ItemCountryDao.ItemCategoryRow> = emptyList()
        private var categoryNames: Map<Long, String> = emptyMap()
        private var pending: Map<String, Long?> = emptyMap()
        private var query: String = ""
        private var categoryFilterEnabled = false
        private var selectedCategoryId: Long? = null

        fun submit(
            rows: List<ItemCountryDao.ItemCategoryRow>,
            categories: List<CategoryEntity>,
            pendingValues: Map<String, Long?>
        ) {
            allRows = rows
            categoryNames = categories.associate { it.id to it.name }
            pending = pendingValues.toMap()
            filter()
        }

        fun setQuery(value: String) {
            query = value.trim()
            filter()
        }

        fun setCategoryFilter(enabled: Boolean, categoryId: Long?) {
            categoryFilterEnabled = enabled
            selectedCategoryId = categoryId
            filter()
        }

        private fun filter() {
            shownRows = allRows.filter { row ->
                val matchesQuery = query.isBlank() || row.itemName.contains(query, ignoreCase = true)
                val effectiveCategoryId =
                    if (pending.containsKey(row.itemName)) pending[row.itemName] else row.categoryId
                val matchesCategory = !categoryFilterEnabled || effectiveCategoryId == selectedCategoryId
                matchesQuery && matchesCategory
            }
            notifyDataSetChanged()
        }

        fun availableSections(): List<String> = shownRows
            .map { sectionKeyOf(it.itemName) }
            .distinct()

        fun positionOfSection(section: String, ratio: Float): Int? {
            val positions = shownRows.indices.filter {
                sectionKeyOf(shownRows[it].itemName) == section
            }
            if (positions.isEmpty()) return null
            val index = ((positions.size - 1) * ratio.coerceIn(0f, 1f)).toInt()
            return positions[index]
        }

        private fun sectionKeyOf(text: String): String {
            if (text.isBlank()) return "#"
            val char = text.first()
            return when {
                char.isLetter() && char.code < 128 -> char.uppercaseChar().toString()
                char.isDigit() -> char.toString()
                char in '\uAC00'..'\uD7A3' -> CHO[((char.code - 0xAC00) / (21 * 28))].toString()
                else -> "#"
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_category_assignment, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = shownRows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = shownRows[position]
            val selectedId = if (pending.containsKey(row.itemName)) pending[row.itemName] else row.categoryId
            holder.itemName.text = row.itemName
            holder.choose.text = selectedId?.let { categoryNames[it] } ?: "미분류"
            holder.choose.setOnClickListener { onChoose(row) }
            holder.itemView.setOnClickListener { onChoose(row) }
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val itemName: TextView = view.findViewById(R.id.tvCategoryItemName)
            val choose: MaterialButton = view.findViewById(R.id.btnChooseCategory)
        }

        companion object {
            private val CHO = charArrayOf(
                'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
                'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
            )
        }
    }
}
