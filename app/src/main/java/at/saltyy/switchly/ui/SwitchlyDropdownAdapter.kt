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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.google.android.material.color.MaterialColors

class SwitchlyDropdownAdapter(
    context: Context,
    items: List<String>
) : ArrayAdapter<String>(context, 0, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        row(position, convertView, parent)

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        row(position, convertView, parent)

    private fun row(position: Int, convertView: View?, parent: ViewGroup): TextView {
        val textView = convertView as? TextView ?: TextView(context).apply {
            val density = resources.displayMetrics.density
            minHeight = (48 * density).toInt()
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isSingleLine = false
            val out = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                setBackgroundResource(out.resourceId)
            }
        }
        textView.setTextColor(
            MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurface)
        )
        textView.text = getItem(position).orEmpty()
        return textView
    }
}
