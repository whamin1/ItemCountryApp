package com.bignerdranch.android.myapplication

import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class StickyHeaderDecoration(
    private val isHeader: (position: Int) -> Boolean,
    private val bindHeaderView: (View, position: Int) -> Unit,
    private val headerLayoutRes: Int
) : RecyclerView.ItemDecoration() {
    private var headerView: View? = null
    private var headerHeight = 0

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val topChild = parent.getChildAt(0) ?: return
        val topPos = parent.getChildAdapterPosition(topChild)
        val headerPos = findCurrentHeaderPosition(topPos) ?: return

        if (headerView == null) {
            headerView = LayoutInflater.from(parent.context).inflate(headerLayoutRes, parent, false).apply {
                measure(
                    View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(parent.height, View.MeasureSpec.UNSPECIFIED)
                )
                layout(0, 0, measuredWidth, measuredHeight)
                headerHeight = measuredHeight
            }
        }
        headerView?.let { hv ->
            bindHeaderView(hv, headerPos)
            // 다음 헤더와 겹칠 때 밀어 올리기
            var y = 0
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val pos = parent.getChildAdapterPosition(child)
                if (isHeader(pos) && child.top in 1..headerHeight) {
                    y = child.top - headerHeight
                    break
                }
            }
            c.save()
            c.translate(0f, y.toFloat())
            hv.draw(c)
            c.restore()
        }
    }

    private fun findCurrentHeaderPosition(from: Int): Int? {
        var pos = from
        while (pos >= 0) {
            if (isHeader(pos)) return pos
            pos--
        }
        return null
    }
}
