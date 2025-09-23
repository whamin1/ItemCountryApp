package com.bignerdranch.android.myapplication.ui.catalog

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.R

class NameListAdapter(
    private var items: List<String> = emptyList(),
    private val onClick: (String) -> Unit = {},
    private val onLongClick: (String) -> Unit = {}
) : RecyclerView.Adapter<NameListAdapter.VH>() {

    class VH(v: View): RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.tvName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_simple_name, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val name = items[position]
        holder.tv.text = name
        holder.itemView.setOnClickListener { onClick(name) }
    }

    override fun getItemCount() = items.size

    fun submit(list: List<String>) {
        items = list
        notifyDataSetChanged()
    }
}
