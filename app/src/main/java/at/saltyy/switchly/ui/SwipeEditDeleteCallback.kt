/*
 * Switchly
 * Copyright (C) 2025-2026 Saltyy
 * Copyright (C) 2026 Switchly Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package at.saltyy.switchly.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.AccentColor
import kotlin.math.abs
import kotlin.math.roundToInt

fun RecyclerView.attachEditDeleteSwipe(
    canSwipe: (position: Int) -> Boolean = { true },
    editIconRes: Int = R.drawable.edit_24,
    onEdit: (position: Int) -> Unit,
    onDelete: (position: Int) -> Unit
) {
    ItemTouchHelper(
        SwipeEditDeleteCallback(
            context = context,
            canSwipe = canSwipe,
            editIconRes = editIconRes,
            onEdit = onEdit,
            onDelete = onDelete
        )
    ).attachToRecyclerView(this)
}

private class SwipeEditDeleteCallback(
    context: Context,
    private val canSwipe: (position: Int) -> Boolean,
    editIconRes: Int,
    private val onEdit: (position: Int) -> Unit,
    private val onDelete: (position: Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    private val density = context.resources.displayMetrics.density
    private val iconSize = (24 * density).roundToInt()
    private val iconMargin = (20 * density).roundToInt()
    private val editColor = AccentColor.getAccentColorInt(context)
    private val deleteColor = ContextCompat.getColor(context, R.color.status_error)
    private val editIcon = ContextCompat.getDrawable(context, editIconRes)?.mutate()
    private val deleteIcon = ContextCompat.getDrawable(context, R.drawable.delete_24)?.mutate()
    private val editBackground = editColor.toDrawable()
    private val deleteBackground = deleteColor.toDrawable()

    init {
        val editIconColor =
            if (ColorUtils.calculateLuminance(editColor) > 0.5) Color.BLACK else Color.WHITE
        editIcon?.let { DrawableCompat.setTintList(it, ColorStateList.valueOf(editIconColor)) }
        deleteIcon?.let { DrawableCompat.setTintList(it, ColorStateList.valueOf(Color.WHITE)) }
    }

    override fun getSwipeDirs(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        val position = viewHolder.bindingAdapterPosition
        return if (position != RecyclerView.NO_POSITION && canSwipe(position)) {
            super.getSwipeDirs(recyclerView, viewHolder)
        } else {
            0
        }
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) {
            return
        }

        viewHolder.bindingAdapter?.notifyItemChanged(position)
        when (direction) {
            ItemTouchHelper.RIGHT -> onEdit(position)
            ItemTouchHelper.LEFT -> onDelete(position)
        }
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.32f

    override fun onChildDraw(
        canvas: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            val item = viewHolder.itemView
            val iconTop = item.top + (item.height - iconSize) / 2
            val iconBottom = iconTop + iconSize

            if (dX > 0f) {
                editBackground.setBounds(item.left, item.top, item.left + dX.roundToInt(), item.bottom)
                editBackground.draw(canvas)
                if (abs(dX) >= iconMargin + iconSize) {
                    editIcon?.setBounds(
                        item.left + iconMargin,
                        iconTop,
                        item.left + iconMargin + iconSize,
                        iconBottom
                    )
                    editIcon?.draw(canvas)
                }
            } else if (dX < 0f) {
                deleteBackground.setBounds(item.right + dX.roundToInt(), item.top, item.right, item.bottom)
                deleteBackground.draw(canvas)
                if (abs(dX) >= iconMargin + iconSize) {
                    deleteIcon?.setBounds(
                        item.right - iconMargin - iconSize,
                        iconTop,
                        item.right - iconMargin,
                        iconBottom
                    )
                    deleteIcon?.draw(canvas)
                }
            }
        }

        super.onChildDraw(
            canvas,
            recyclerView,
            viewHolder,
            dX,
            dY,
            actionState,
            isCurrentlyActive
        )
    }
}
