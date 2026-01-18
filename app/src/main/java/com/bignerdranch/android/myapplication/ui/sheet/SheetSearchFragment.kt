package com.bignerdranch.android.myapplication.ui.sheet

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import com.bignerdranch.android.myapplication.data.local.entity.ItemSearchRow
import kotlinx.coroutines.launch

class SheetSearchFragment : Fragment(R.layout.fragment_sheet_search) {

    private val vm: ItemCountryViewModel by activityViewModels()
    private lateinit var adapter: SheetSearchAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<RecyclerView>(R.id.rvSearch)
        val etSearch = view.findViewById<EditText>(R.id.etSearch)

        val initialQuery = arguments?.getString("initialQuery").orEmpty()
        if (initialQuery.isNotBlank()) {
            etSearch.setText(initialQuery)
            etSearch.setSelection(initialQuery.length)
            vm.updateSearchQuery(initialQuery)
        }


        adapter = SheetSearchAdapter { row ->
            navigateToDetail(row)
        }
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        etSearch.addTextChangedListener (object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                vm.updateSearchQuery(s?.toString().orEmpty())
            }
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) {}
            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {}
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.sheetItems.collect { list ->
                    adapter.submit(list)
                }
            }
        }
    }

    private class SheetSearchAdapter(
        val onClick: (ItemSearchRow) -> Unit
    ) : RecyclerView.Adapter<SheetSearchAdapter.VH>() {

        private val data = mutableListOf<ItemSearchRow>()

        fun submit(list: List<ItemSearchRow>) {
            data.apply { clear(); addAll(list) }
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private  val tv: TextView = v.findViewById(android.R.id.text1)
            fun bind(row: ItemSearchRow) {
                tv.text = "${row.sheetTitle} / ${row.country} / ${row.item} / ${row.price} / ${row.weight}"

                itemView.setOnClickListener {
                    onClick(row)
                }
            }

        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: SheetSearchAdapter.VH, position: Int) {
           holder.bind(data[position])
        }

        override fun getItemCount() = data.size

    }

    private fun navigateToDetail(row: ItemSearchRow) {
        val args = bundleOf(
            "sheetId" to row.sheetId,
            "title" to row.sheetTitle,
            "country" to row.country,
            "highlightItem" to row.item
        )
        findNavController().navigate(R.id.sheetDetailFragment, args)
    }

}