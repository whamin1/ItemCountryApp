package com.bignerdranch.android.myapplication.ui.catalog

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.data.local.entity.ItemSearchRow

class SheetPickAdapter(
    private val onClick: (ItemSearchRow) -> Unit
) : RecyclerView.Adapter<SheetPickAdapter.VH>() {

    private val data = mutableListOf<ItemSearchRow>()

    fun submit(list: List<ItemSearchRow>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position])

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tv: TextView = v.findViewById(android.R.id.text1)
        fun bind(row: ItemSearchRow) {
            tv.text = "${row.sheetTitle} / ${row.country} / ${row.item} / ${row.price} / ${row.weight}"
            itemView.setOnClickListener { onClick(row) }
        }
    }
}