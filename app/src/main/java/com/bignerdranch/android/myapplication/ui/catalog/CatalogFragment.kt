package com.bignerdranch.android.myapplication.ui.catalog

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.bignerdranch.android.myapplication.R
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class CatalogFragment : Fragment(R.layout.fragment_catalog) {
    private val vm: ItemCountryViewModel by activityViewModels()

    private lateinit var tabLayout: TabLayout
    private lateinit var pager: ViewPager2
    private lateinit var pagerAdapter: CountryPagerAdapter
    private var mediator: TabLayoutMediator? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tabLayout = view.findViewById(R.id.tabLayout)
        pager = view.findViewById(R.id.pager)
        pagerAdapter = CountryPagerAdapter(this)
        pager.adapter = pagerAdapter

        // 🔁 나라 목록 Flow -> 탭 자동 갱신 (uiStateCountryQty: Map<Country, List<Row>> 를 사용)

        viewLifecycleOwner.lifecycleScope.launch {
            vm.allCountryNames.collect { countries: List<String> ->
                mediator?.detach()
                mediator = null
                pagerAdapter.submitCountries(countries)
                mediator = TabLayoutMediator(tabLayout, pager) { tab, pos ->
                    tab.text = pagerAdapter.getTitle(pos)
                }.also { it.attach() }
            }
        }

        // ✅ 탭 롱클릭 삭제
        for (i in 0 until tabLayout.tabCount) {
            val tab = tabLayout.getTabAt(i) ?: continue
            // tab.view 는 MaterialComponents에서 제공하는 뷰
            tab.view.setOnLongClickListener {
                val country = tab.text?.toString().orEmpty()
                if (country.isNotEmpty()) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("나라 삭제")
                        .setMessage("‘$country’을(를) 삭제할까요?")
                        .setPositiveButton("삭제") { _, _ ->
                            vm.deleteCountry(country)
                        }
                        .setNegativeButton("취소", null)
                        .show()
                }
                true
            }
        }

        view.findViewById<FloatingActionButton>(R.id.fabAddCountry).setOnClickListener {
            showAddCountryDialog()
        }
    }
    private fun showAddCountryDialog() {
        val et = EditText(requireContext())
        AlertDialog.Builder(requireContext())
            .setTitle("나라 추가")
            .setView(et)
            .setPositiveButton("추가") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotEmpty()) {
                    vm.addCountry(name)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
