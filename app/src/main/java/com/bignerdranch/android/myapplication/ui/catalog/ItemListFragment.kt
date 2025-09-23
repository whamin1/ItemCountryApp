package com.bignerdranch.android.myapplication.ui.catalog

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import kotlinx.coroutines.launch

class ItemListFragment : Fragment(R.layout.fragment_catalog_list) {

    private val vm: ItemCountryViewModel by activityViewModels()

    private lateinit var adapter: NameListAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler)

        adapter = NameListAdapter(
            onClick = { item ->
            // TODO: 아이템 클릭 시 액션 (예: 해당 아이템의 나라 목록으로 이동)
        },
            onLongClick = { item ->
                AlertDialog.Builder(requireContext())
                    .setTitle("아이템 삭제")
                    .setMessage("‘$item’을(를) 모든 나라에서 삭제할까요?")
                    .setPositiveButton("삭제") { _, _ -> vm.deleteItem(item) }
                    .setNegativeButton("취소", null)
                    .show()
            }
        )

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            vm.uiStateItemQty.collect { map ->
                val names = map.keys.toList().sorted()
                adapter.submit(names)
            }
        }
    }
}
