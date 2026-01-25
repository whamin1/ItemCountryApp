package com.bignerdranch.android.myapplication.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.repository.ItemCountryRepository
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import kotlinx.coroutines.launch

class HiddenPredictionsFragment : Fragment(R.layout.fragment_predictions) {

    private val vm: ItemCountryViewModel by activityViewModels()
    private lateinit var adapter: PredictionsAdapter

    private var raw: List<ItemCountryRepository.PredItem> = emptyList()
    private var query: String = ""

    private val PREFS_NAME = "pred_prefs"
    private val KEY_EXCLUDED_IDS = "excluded_item_ids"

    private fun prefs() = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getExcludedIds(): Set<Long> {
        val set = prefs().getStringSet(KEY_EXCLUDED_IDS, emptySet()) ?: emptySet()
        return set.mapNotNull { it.toLongOrNull() }.toSet()
    }

    private fun setExcludedIds(ids: Set<Long>) {
        val set = ids.map { it.toString() }.toSet()
        prefs().edit().putStringSet(KEY_EXCLUDED_IDS, set).apply()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val rv = view.findViewById<RecyclerView>(R.id.rvPred)
        val sv = view.findViewById<SearchView>(R.id.searchPred)

        adapter = PredictionsAdapter(
            onClick = { item ->
                vm.requestJumpToItem(item)
                findNavController().popBackStack() // 숨김 화면 닫고 Home 가고 싶으면 이대로
            },
            onLongClick = { ui ->
                // 숨김 화면에서는 롱클릭 = 숨김 해제 (복구)
                unhide(ui.itemId, ui.item)
            },
            onReportClick = { ui ->
                findNavController().navigate(
                    R.id.itemReportFragment,
                    bundleOf("itemId" to ui.itemId)
                )
            }
        )

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
    }

    private fun unhide(itemId: Long, name: String) {
        val excluded = getExcludedIds().toMutableSet()
        excluded.remove(itemId)
        setExcludedIds(excluded)
        Toast.makeText(requireContext(), "숨김 해제: $name", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val q = query.trim()
        val excluded = getExcludedIds()

        val base = raw.filter { it.itemId in excluded } // ✅ 숨김만

        val filtered = if (q.isBlank()) base else {
            base.filter { it.item.contains(q, ignoreCase = true) }
        }

        val ui = filtered
            .map {
                val eta = it.predictedAt - now
                PredictionsAdapter.Ui(
                    itemId = it.itemId,
                    item = it.item,
                    etaMs = eta,
                    label = it.label,
                    predictedAt = it.predictedAt
                )
            }
            .sortedBy { it.item } // 숨김 목록은 이름순이 더 자연스러움

        adapter.submit(ui)
    }
}