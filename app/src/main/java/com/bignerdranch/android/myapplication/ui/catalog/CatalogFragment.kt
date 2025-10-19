package com.bignerdranch.android.myapplication.ui.catalog

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.bignerdranch.android.myapplication.R
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class CatalogFragment : Fragment(R.layout.fragment_catalog) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tab = view.findViewById<TabLayout>(R.id.tabLayout)
        val pager = view.findViewById<ViewPager2>(R.id.pager)

        pager.adapter = CatalogPagerAdapter(this)

        TabLayoutMediator(tab, pager) { t, pos ->
            t.text = when (pos) {
                0 -> "나라"
                else -> "아이템"
            }
        }.attach()
    }
}
