package kr.co.addresslens

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/** Buttons wrap rather than truncate at large font sizes. GONE children occupy no space. */
class ActionRow(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private val gap = (8 * resources.displayMetrics.density).toInt()
    private val rows = mutableListOf<List<View>>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        rows.clear()
        var row = mutableListOf<View>()
        var used = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val next = child.measuredWidth + if (row.isEmpty()) 0 else gap
            if (row.isNotEmpty() && used + next > available) {
                rows.add(row)
                row = mutableListOf()
                used = 0
            }
            used += child.measuredWidth + if (row.isEmpty()) 0 else gap
            row.add(child)
        }
        if (row.isNotEmpty()) rows.add(row)
        var height = paddingTop + paddingBottom
        for ((index, children) in rows.withIndex()) {
            val extra = (available - children.sumOf { it.measuredWidth } - gap * (children.size - 1))
                .coerceAtLeast(0)
            children.forEachIndexed { childIndex, child ->
                val share = extra / children.size + if (childIndex < extra % children.size) 1 else 0
                child.measure(MeasureSpec.makeMeasureSpec(child.measuredWidth + share, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            }
            height += children.maxOf { it.measuredHeight } + if (index == 0) 0 else gap
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = paddingTop
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        for (row in rows) {
            val height = row.maxOf { it.measuredHeight }
            var x = if (rtl) width - paddingRight else paddingLeft
            for (child in row) {
                val top = y + (height - child.measuredHeight) / 2
                val left = if (rtl) x - child.measuredWidth else x
                child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
                x += (child.measuredWidth + gap) * if (rtl) -1 else 1
            }
            y += height + gap
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(attrs: AttributeSet?) = LayoutParams(context, attrs)
    override fun generateLayoutParams(p: LayoutParams?) = LayoutParams(p)
}
