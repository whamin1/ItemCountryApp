package com.bignerdranch.android.myapplication.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.repository.ItemCountryRepository
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import kotlinx.coroutines.launch

class PredictionsFragment : Fragment(R.layout.fragment_predictions) {

    private val vm: ItemCountryViewModel by activityViewModels()
    private lateinit var adapter: PredictionsAdapter

    private var raw: List<ItemCountryRepository.PredItem> = emptyList()
    private var query: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val rv = view.findViewById<RecyclerView>(R.id.rvPred)
        val sv = view.findViewById<SearchView>(R.id.searchPred)

        adapter = PredictionsAdapter { item ->
            vm.requestJumpToItem(item)
            findNavController().popBackStack() // Home으로
        }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean {
                query = q.orEmpty()
                render()
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                query = newText.orEmpty()
                render()
                return true
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            raw = vm.buildPredictionsAll()
            render()
        }



        // 들어올 때 한번 갱신
        viewLifecycleOwner.lifecycleScope.launch {
            vm.refreshPredictions()
        }
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val q = query.trim()

        val filtered = if (q.isBlank()) raw else {
            raw.filter { it.item.contains(q, ignoreCase = true) }
        }

        val ui = filtered
            .map {
                val eta = it.predictedAt - now
                PredictionsAdapter.Ui(
                    item = it.item,
                    etaMs = eta,
                    label = it.label,
                    predictedAt = it.predictedAt
                )
            }
            // ETA 짧은 순 (지나간 건 0 취급 → “지금 필요” 느낌으로 맨 위)
            .sortedBy { it.etaMs.coerceAtLeast(0) }

        adapter.submit(ui)
    }
}