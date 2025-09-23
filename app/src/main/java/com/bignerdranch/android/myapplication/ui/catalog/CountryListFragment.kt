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

class CountryListFragment : Fragment(R.layout.fragment_catalog_list) {

    // HomeFragment와 같은 데이터를 쓰고 싶으면 activityViewModels로 공유
    private val vm: ItemCountryViewModel by activityViewModels()

    private lateinit var adapter: NameListAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler)

        adapter = NameListAdapter(
            onClick = { country ->
            // TODO: 나라 클릭 시 액션 (예: 해당 나라의 아이템 목록 화면으로 이동)
            // findNavController().navigate(...)
        },
            onLongClick = { country ->
                AlertDialog.Builder(requireContext())
                    .setTitle("나라 삭제")
                    .setMessage("‘$country’을(를) 삭제할까요? (연결된 아이템 링크도 함께 삭제)")
                    .setPositiveButton("삭제") { _, _ -> vm.deleteCountry(country) }
                    .setNegativeButton("취소", null)
                    .show()
        }
        )

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        // vm에서 나라 목록을 가져오는 흐름 (예: uiStateCountryQty의 keySet)
        viewLifecycleOwner.lifecycleScope.launch {
            vm.uiStateCountryQty.collect { map ->
                val names = map.keys.toList().sorted()
                adapter.submit(names)
            }
        }
    }
}
