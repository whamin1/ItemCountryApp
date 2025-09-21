package com.bignerdranch.android.myapplication.ui.catalog

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.bignerdranch.android.myapplication.data.local.entity.CountryEntity

class CountryPagerAdapter(f: Fragment) : FragmentStateAdapter(f) {
    private var countries: List<String> = emptyList()

    fun submitCountries(newList: List<String>) {
        countries = newList
        notifyDataSetChanged()
    }

    override fun getItemCount() = countries.size

    override fun createFragment(pos: Int): Fragment {
        val countryName = countries[pos]
        return CountryFragment.newInstance(countryName)
    }
    override fun getItemId(position: Int): Long =countries[position].hashCode().toLong()
    override fun containsItem(itemId: Long): Boolean = countries.any { it.hashCode().toLong() == itemId }
}
