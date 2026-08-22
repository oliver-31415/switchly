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

package at.saltyy.switchly.feature.usage

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.AccentColor
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import java.util.Locale

data class IgnoredUsageAppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val suggested: Boolean,
)

class IgnoredUsageAppsAdapter(
    initialSelection: Set<String>,
    private val onSelectionChanged: (Set<String>) -> Unit,
) : ListAdapter<IgnoredUsageAppItem, IgnoredUsageAppsAdapter.ViewHolder>(DIFF) {
    private var allItems: List<IgnoredUsageAppItem> = emptyList()
    private var query: String = ""
    private val selectedPackages = initialSelection.toMutableSet()

    init {
        setHasStableIds(true)
    }

    fun setItems(items: List<IgnoredUsageAppItem>) {
        allItems = items
        applyFilter()
    }

    fun setQuery(value: String?) {
        query = value.orEmpty().trim().lowercase(Locale.getDefault())
        applyFilter()
    }

    fun selectedPackages(): Set<String> = selectedPackages.toSet()

    fun replaceSelection(packages: Set<String>) {
        val previousSelection = selectedPackages.toSet()
        selectedPackages.clear()
        selectedPackages += packages
        currentList.forEachIndexed { index, item ->
            val wasSelected = item.packageName in previousSelection
            val isSelected = item.packageName in selectedPackages
            if (wasSelected != isSelected) {
                notifyItemChanged(index, PAYLOAD_SELECTION)
            }
        }
        onSelectionChanged(selectedPackages())
    }

    override fun getItemId(position: Int): Long = getItem(position).packageName.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.row_ignored_usage_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (PAYLOAD_SELECTION in payloads) {
            holder.bindSelection(getItem(position).packageName in selectedPackages)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun applyFilter() {
        val visible = if (query.isBlank()) {
            allItems
        } else {
            allItems.filter { item ->
                item.label.lowercase(Locale.getDefault()).contains(query) ||
                    item.packageName.lowercase(Locale.getDefault()).contains(query)
            }
        }
        submitList(visible)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view.findViewById(R.id.cardIgnoredUsageApp)
        private val icon: ImageView = view.findViewById(R.id.ivIgnoredUsageAppIcon)
        private val name: TextView = view.findViewById(R.id.tvIgnoredUsageAppName)
        private val packageName: TextView = view.findViewById(R.id.tvIgnoredUsageAppPackage)
        private val suggested: TextView = view.findViewById(R.id.tvIgnoredUsageSuggested)
        private val checkBox: MaterialCheckBox = view.findViewById(R.id.cbIgnoredUsageApp)

        fun bind(item: IgnoredUsageAppItem) {
            icon.setImageDrawable(item.icon)
            name.text = item.label
            packageName.text = item.packageName
            suggested.isVisible = item.suggested
            suggested.setTextColor(AccentColor.getAccentColorInt(suggested.context))

            bindSelection(item.packageName in selectedPackages)

            val toggle = {
                val isNowChecked = item.packageName !in selectedPackages
                if (isNowChecked) {
                    selectedPackages += item.packageName
                } else {
                    selectedPackages -= item.packageName
                }
                checkBox.isChecked = isNowChecked
                updateCardState(isNowChecked)
                onSelectionChanged(selectedPackages())
            }
            card.setOnClickListener { toggle() }
            checkBox.setOnClickListener { toggle() }
        }

        fun bindSelection(selected: Boolean) {
            checkBox.setOnCheckedChangeListener(null)
            checkBox.setUseMaterialThemeColors(false)
            val accent = AccentColor.getAccentColorInt(checkBox.context)
            val unchecked = MaterialColors.getColor(
                checkBox.context,
                com.google.android.material.R.attr.colorOutline,
                checkBox.resources.getColor(R.color.switchly_card_stroke, checkBox.context.theme),
            )
            checkBox.buttonTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(),
                ),
                intArrayOf(accent, unchecked),
            )
            checkBox.isChecked = selected
            updateCardState(selected)
        }

        private fun updateCardState(selected: Boolean) {
            card.strokeWidth = if (selected) dp(2) else dp(1)
            card.strokeColor = if (selected) {
                AccentColor.getAccentColorInt(card.context)
            } else {
                card.resources.getColor(R.color.switchly_card_stroke, card.context.theme)
            }
        }

        private fun dp(value: Int): Int {
            return (value * itemView.resources.displayMetrics.density).toInt()
        }
    }

    private companion object {
        const val PAYLOAD_SELECTION = "selection"

        val DIFF = object : DiffUtil.ItemCallback<IgnoredUsageAppItem>() {
            override fun areItemsTheSame(oldItem: IgnoredUsageAppItem, newItem: IgnoredUsageAppItem): Boolean {
                return oldItem.packageName == newItem.packageName
            }

            override fun areContentsTheSame(oldItem: IgnoredUsageAppItem, newItem: IgnoredUsageAppItem): Boolean {
                return oldItem.packageName == newItem.packageName &&
                    oldItem.label == newItem.label &&
                    oldItem.suggested == newItem.suggested
            }
        }
    }
}
