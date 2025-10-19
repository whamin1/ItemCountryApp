package com.bignerdranch.android.myapplication.ui.catalog


import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class CatalogPagerAdapter(parent: Fragment) : FragmentStateAdapter(parent) {
    override fun getItemCount() = 2
    override fun createFragment(position: Int): Fragment =
        when (position) {
            0 -> CountryTabFragment() // 나라 탭
            else -> ItemTabFragment() // 아이템 탭
        }
}
