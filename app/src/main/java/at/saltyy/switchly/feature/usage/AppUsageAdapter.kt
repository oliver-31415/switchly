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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.feature.stats.StatsFormat
import at.saltyy.switchly.theme.AccentColor

class AppUsageAdapter(
    private val onClick: ((AppUsage) -> Unit)? = null,
    private val onEditLimits: ((AppUsage) -> Unit)? = null,
    private val limitBadgeProvider: ((AppUsage) -> String?)? = null
) : RecyclerView.Adapter<AppUsageAdapter.VH>() {

    private var items: List<AppUsage> = emptyList()
    private var detailsCtaEnabled: Boolean = true

    fun submit(list: List<AppUsage>) {
        val oldSize = items.size
        items = list
        if (oldSize == 0 && list.isNotEmpty()) {
            notifyItemRangeInserted(0, list.size)
        } else if (list.isEmpty() && oldSize > 0) {
            notifyItemRangeRemoved(0, oldSize)
        } else {
            notifyItemRangeChanged(0, minOf(oldSize, list.size))
            if (list.size > oldSize) notifyItemRangeInserted(oldSize, list.size - oldSize)
            if (oldSize > list.size) notifyItemRangeRemoved(list.size, oldSize - list.size)
        }
    }

    fun setDetailsCtaEnabled(enabled: Boolean) {
        if (detailsCtaEnabled == enabled) return
        detailsCtaEnabled = enabled
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_usage_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], onClick, onEditLimits, detailsCtaEnabled, limitBadgeProvider)
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val icon = v.findViewById<ImageView>(R.id.icon)
        private val name = v.findViewById<TextView>(R.id.name)
        private val time = v.findViewById<TextView>(R.id.time)
        private val percent = v.findViewById<TextView>(R.id.percent)
        private val details = v.findViewById<TextView>(R.id.details)
        private val progress = v.findViewById<ProgressBar>(R.id.progress)
        private val btnEditLimits = v.findViewById<ImageView>(R.id.btnEditLimits)
        private val limitBadge = v.findViewById<TextView>(R.id.limitBadge)

        fun bind(
            item: AppUsage,
            onClick: ((AppUsage) -> Unit)?,
            onEditLimits: ((AppUsage) -> Unit)?,
            detailsCtaEnabled: Boolean,
            limitBadgeProvider: ((AppUsage) -> String?)?
        ) {
            val ctx = itemView.context

            name.text = item.label
            time.text = StatsFormat.prettyMsWithSeconds(item.timeMs)
            percent.text = StatsFormat.prettyPercent(item.percent)
            icon.setImageDrawable(item.icon)
            progress.progress = (item.percent * 100f).toInt().coerceIn(0, 100)

            // Keep usage bars/details CTA in sync with active accent (incl. custom colors)
            val accent = AccentColor.getAccentColorInt(ctx)
            progress.progressTintList = ColorStateList.valueOf(accent)
            details.setTextColor(accent)

            val badge = limitBadgeProvider?.invoke(item)?.trim().orEmpty()
            limitBadge.isVisible = badge.isNotBlank()
            limitBadge.text = badge
            if (badge.isNotBlank()) {
                limitBadge.setTextColor(accent)
            }

            val clickable = detailsCtaEnabled && onClick != null
            details.isVisible = clickable

            // Limit editor icon: always visible if callback provided.
            btnEditLimits.isVisible = onEditLimits != null
            btnEditLimits.setOnClickListener {
                onEditLimits?.invoke(item)
            }

            itemView.setOnClickListener(if (clickable) {
                View.OnClickListener { onClick?.invoke(item) }
            } else null)
            itemView.isClickable = clickable
            itemView.isFocusable = clickable

            itemView.contentDescription = if (clickable) {
                ctx.getString(
                    R.string.usage_row_open_details_a11y,
                    item.label,
                    StatsFormat.prettyMsWithSeconds(item.timeMs),
                    StatsFormat.prettyPercent(item.percent)
                )
            } else {
                ctx.getString(
                    R.string.usage_row_basic_a11y,
                    item.label,
                    StatsFormat.prettyMsWithSeconds(item.timeMs),
                    StatsFormat.prettyPercent(item.percent)
                )
            }
        }
    }
}
