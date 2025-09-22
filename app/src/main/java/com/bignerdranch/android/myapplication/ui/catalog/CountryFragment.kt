package com.bignerdranch.android.myapplication.ui.catalog

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch

class CountryFragment : Fragment(R.layout.fragment_country) {
    private val vm: ItemCountryViewModel by activityViewModels()
    private lateinit var adapter: NameListAdapter
    private var mediator: TabLayoutMediator? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val countryName = requireArguments().getString(ARG_COUNTRY)!!
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        adapter = NameListAdapter()
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter


        // 아이템 목록 관찰
        viewLifecycleOwner.lifecycleScope.launch {
            vm.uiStateCountryQty.collect { map ->
                val rows = map[countryName].orEmpty()
                adapter.submit(rows.map { it.name })
            }
        }

        // 아이템 추가 버튼
        view.findViewById<FloatingActionButton>(R.id.fabAddItem).setOnClickListener {
            showAddItemDialog(countryName)
        }
    }

    private fun showAddItemDialog(country: String) {
        val et = EditText(requireContext())
        AlertDialog.Builder(requireContext())
            .setTitle("아이템 추가")
            .setView(et)
            .setPositiveButton("추가") { _, _ ->
                val item = et.text.toString().trim()
                if (item.isNotEmpty()) vm.addItems(mapOf(item to listOf(country)))
            }
            .setNegativeButton("취소", null)
            .show()
    }

    companion object {
        private const val ARG_COUNTRY = "country"
        fun newInstance(country: String) =
            CountryFragment().apply {
                arguments = bundleOf(ARG_COUNTRY to country)
            }
    }
}
