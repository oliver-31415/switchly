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

package at.saltyy.switchly.feature.picker

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AttemptLimitStore
import at.saltyy.switchly.data.prefs.OpenCountStore
import at.saltyy.switchly.data.prefs.SessionLimitStore
import at.saltyy.switchly.data.prefs.UsageLimitStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.util.AppBlockSafety
import java.util.Locale

class AppListAdapter(
    private val allApps: List<AppEntry>,
    preselectedManaged: Set<String>,
    private val currentProfileProvider: () -> String?,
    private val onSetLimitClicked: (app: AppEntry) -> Unit,
    private val onSetSessionLimitClicked: ((app: AppEntry) -> Unit)? = null
) : ListAdapter<AppEntry, AppListAdapter.VH>(DIFF) {

    private val managed = preselectedManaged.toMutableSet()

    fun getManagedPackages(): Set<String> = managed.toSet()

    fun selectAllVisible(): Int {
        var skipped = 0
        currentList.forEachIndexed { index, item ->
            if (item.blockSafety.level != AppBlockSafety.Level.NONE) {
                skipped++
                return@forEachIndexed
            }
            if (managed.add(item.packageName)) {
                notifyItemChanged(index)
            }
        }
        return skipped
    }

    fun clearAllVisible(context: Context) {
        val profile = currentProfileProvider.invoke()
        currentList.forEachIndexed { index, item ->
            if (hasPinnedLimit(context, profile, item)) return@forEachIndexed

            val wasManaged = managed.remove(item.packageName)

            if (!item.isAvailable && !profile.isNullOrBlank()) {
                UsageLimitStore.setLimitMinutes(context, profile, item.packageName, 0)
                SessionLimitStore.setLimitMinutes(context, profile, item.packageName, 0)
                AttemptLimitStore.setLimitAttempts(context, profile, item.packageName, 0)
                OpenCountStore.setToday(context, profile, item.packageName, 0)
            }

            if (wasManaged) {
                notifyItemChanged(index)
            }
        }
    }

    private fun hasPinnedLimit(context: Context, profile: String?, item: AppEntry): Boolean {
        if (profile.isNullOrBlank() || !item.isAvailable || item.blockSafety.level == AppBlockSafety.Level.HARD_EXCLUDED) {
            return false
        }
        return UsageLimitStore.getLimitMinutes(context, profile, item.packageName) > 0 ||
            SessionLimitStore.getLimitMinutes(context, profile, item.packageName) > 0 ||
            AttemptLimitStore.getLimitAttempts(context, profile, item.packageName) > 0
    }

    init {
        submitList(allApps)
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).packageName.hashCode().toLong()
    }

    fun notifyPkgChanged(pkg: String) {
        val idx = currentList.indexOfFirst { it.packageName == pkg }
        if (idx >= 0) notifyItemChanged(idx)
    }

    fun filter(query: String?) {
        val q = query?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val newList =
            if (q.isBlank()) allApps
            else allApps.filter { it.labelLower.contains(q) || it.pkgLower.contains(q) }

        submitList(newList)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.row_app_picker, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val cb: CheckBox = v.findViewById(R.id.cbSelect)
        private val ivAppIcon: ImageView = v.findViewById(R.id.ivAppIcon)
        private val tvLabel: TextView = v.findViewById(R.id.tvLabel)
        private val tvPkg: TextView = v.findViewById(R.id.tvPkg)
        private val tvStateChip: TextView = v.findViewById(R.id.tvUnavailableChip)
        private val tvHint: TextView = v.findViewById(R.id.tvUnavailableHint)

        private val limitRow: LinearLayout = v.findViewById(R.id.limitRow)
        private val ivTimer: ImageView = v.findViewById(R.id.ivTimer)
        private val tvState: TextView = v.findViewById(R.id.tvState)
        private val tvLimit: TextView = v.findViewById(R.id.tvLimit)
        private val tvMetaSeparator: TextView = v.findViewById(R.id.tvMetaSeparator)

        private val btnLimit: ImageButton = v.findViewById(R.id.btnLimit)

        private fun dp(value: Float): Int =
            (value * itemView.resources.displayMetrics.density).toInt()

        private fun applyStateChipStyle() {
            val ctx = itemView.context
            val chipBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999f).toFloat()
                setColor(ContextCompat.getColor(ctx, R.color.unavailable_chip_bg))
            }
            tvStateChip.background = chipBg
            tvStateChip.setTextColor(ContextCompat.getColor(ctx, R.color.unavailable_chip_text))
        }

        private fun applyUnavailableRowStyle() {
            val ctx = itemView.context
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12f).toFloat()
                setColor(ContextCompat.getColor(ctx, R.color.unavailable_row_bg))
                setStroke(dp(1f), ContextCompat.getColor(ctx, R.color.unavailable_row_stroke))
            }
            itemView.background = bg
        }

        fun bind(item: AppEntry) {
            val ctx = itemView.context
            val profile = currentProfileProvider.invoke()
            val hardExcluded = item.blockSafety.level == AppBlockSafety.Level.HARD_EXCLUDED
            val softWarning = item.blockSafety.level == AppBlockSafety.Level.SOFT_WARNING

            ivAppIcon.setImageDrawable(AppIconCache.get(ctx, item.packageName))

            tvLabel.text = item.label
            tvPkg.text = item.packageName

            val limitMin = if (!profile.isNullOrBlank()) {
                UsageLimitStore.getLimitMinutes(ctx, profile, item.packageName)
            } else 0

            val sessionLimitMin = if (!profile.isNullOrBlank()) {
                SessionLimitStore.getLimitMinutes(ctx, profile, item.packageName)
            } else 0

            val attemptLimit = if (!profile.isNullOrBlank()) {
                AttemptLimitStore.getLimitAttempts(ctx, profile, item.packageName)
            } else 0

            val hasDailyLimit = limitMin > 0
            val hasSessionLimit = sessionLimitMin > 0
            val hasAttemptLimit = attemptLimit > 0
            val hasLimit = hasDailyLimit || hasSessionLimit || hasAttemptLimit
            val effectiveHasLimit = hasLimit && !hardExcluded
            val accent = AccentColor.getAccentColorInt(ctx)

            cb.buttonTintList = AccentColor.getActiveColor(ctx)
            applyStateChipStyle()

            if (item.isAvailable) {
                itemView.background = null

                if (!item.blockSafety.hint.isNullOrBlank()) {
                    tvStateChip.visibility = View.VISIBLE
                    tvHint.visibility = View.VISIBLE
                    tvStateChip.text = ctx.getString(
                        if (hardExcluded) R.string.app_picker_protected_chip else R.string.app_picker_caution_chip
                    )
                    tvHint.text = item.blockSafety.hint
                } else {
                    tvStateChip.text = ""
                    tvHint.text = ""
                    tvStateChip.visibility = View.GONE
                    tvHint.visibility = View.GONE
                }

                limitRow.orientation = LinearLayout.HORIZONTAL
                limitRow.gravity = android.view.Gravity.CENTER_VERTICAL
                ivTimer.visibility = View.VISIBLE
                tvMetaSeparator.visibility = View.VISIBLE

                val limitParams = tvLimit.layoutParams as ViewGroup.MarginLayoutParams
                limitParams.marginStart = 0
                tvLimit.layoutParams = limitParams

                if (effectiveHasLimit) {
                    limitRow.visibility = View.VISIBLE
                    ivTimer.setColorFilter(accent)
                    tvState.text = ctx.getString(R.string.limit_set)
                    tvLimit.text = buildString {
                        if (hasDailyLimit) append(ctx.getString(R.string.daily_limit_label, limitMin))
                        if (hasSessionLimit) {
                            if (isNotEmpty()) append("  •  ")
                            append(ctx.getString(R.string.session_limit_label, sessionLimitMin))
                        }
                        if (hasAttemptLimit) {
                            if (isNotEmpty()) append("  •  ")
                            append(ctx.getString(R.string.attempt_limit_label, attemptLimit))
                        }
                    }
                    tvState.setTextColor(accent)
                    tvLimit.setTextColor(accent)
                } else {
                    limitRow.visibility = View.GONE
                }
            } else {
                applyUnavailableRowStyle()
                tvStateChip.visibility = View.VISIBLE
                tvHint.visibility = View.VISIBLE
                tvStateChip.text = ctx.getString(R.string.unavailable_app_state)
                tvHint.text = ctx.getString(R.string.unavailable_app_remove_hint)
                limitRow.visibility = View.GONE
            }

            if (effectiveHasLimit && item.isAvailable) {
                managed.add(item.packageName)
            }
            if (hardExcluded) {
                managed.remove(item.packageName)
            }

            cb.setOnCheckedChangeListener(null)

            val lockSelectionDueToLimit = effectiveHasLimit && item.isAvailable
            cb.isChecked = effectiveHasLimit || (!hardExcluded && managed.contains(item.packageName))
            cb.isEnabled = !lockSelectionDueToLimit && !hardExcluded
            cb.alpha = if (lockSelectionDueToLimit || hardExcluded) 0.65f else 1f

            lateinit var listener: CompoundButton.OnCheckedChangeListener
            fun setCheckedSilently(value: Boolean) {
                cb.setOnCheckedChangeListener(null)
                cb.isChecked = value
                cb.setOnCheckedChangeListener(listener)
            }

            listener = CompoundButton.OnCheckedChangeListener { _, checked ->
                if (checked) {
                    if (softWarning) {
                        setCheckedSilently(false)
                        AlertDialog.Builder(ctx)
                            .setTitle(item.blockSafety.warningTitle ?: ctx.getString(R.string.app_picker_protected_caution_title))
                            .setMessage(item.blockSafety.warningMessage ?: item.blockSafety.hint ?: ctx.getString(R.string.app_picker_protected_generic_hint))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.continue_label) { _, _ ->
                                managed.add(item.packageName)
                                notifyPkgChanged(item.packageName)
                            }
                            .showAccented()
                    } else {
                        managed.add(item.packageName)
                    }
                } else {
                    managed.remove(item.packageName)

                    if (!item.isAvailable && !profile.isNullOrBlank()) {
                        UsageLimitStore.setLimitMinutes(ctx, profile, item.packageName, 0)
                        SessionLimitStore.setLimitMinutes(ctx, profile, item.packageName, 0)
                        AttemptLimitStore.setLimitAttempts(ctx, profile, item.packageName, 0)
                        OpenCountStore.setToday(ctx, profile, item.packageName, 0)
                        notifyPkgChanged(item.packageName)
                    }
                }
            }
            cb.setOnCheckedChangeListener(listener)

            if (item.isAvailable && !hardExcluded) {
                btnLimit.visibility = View.VISIBLE
                btnLimit.isEnabled = true
                btnLimit.alpha = 1f
                btnLimit.setOnClickListener { onSetLimitClicked(item) }
                btnLimit.setOnLongClickListener {
                    onSetSessionLimitClicked?.invoke(item)
                    true
                }
            } else if (item.isAvailable) {
                btnLimit.visibility = View.VISIBLE
                btnLimit.isEnabled = false
                btnLimit.alpha = 0.45f
                btnLimit.setOnClickListener {
                    Toast.makeText(ctx, item.blockSafety.hint ?: ctx.getString(R.string.app_picker_protected_generic_hint), Toast.LENGTH_LONG).show()
                }
                btnLimit.setOnLongClickListener {
                    Toast.makeText(ctx, item.blockSafety.hint ?: ctx.getString(R.string.app_picker_protected_generic_hint), Toast.LENGTH_LONG).show()
                    true
                }
            } else {
                btnLimit.visibility = View.GONE
                btnLimit.isEnabled = false
                btnLimit.alpha = 0.45f
                btnLimit.setOnClickListener {
                    Toast.makeText(ctx, R.string.cannot_set_limit_unavailable, Toast.LENGTH_SHORT).show()
                }
                btnLimit.setOnLongClickListener {
                    Toast.makeText(ctx, R.string.cannot_set_limit_unavailable, Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppEntry>() {
            override fun areItemsTheSame(oldItem: AppEntry, newItem: AppEntry): Boolean {
                return oldItem.packageName == newItem.packageName
            }

            override fun areContentsTheSame(oldItem: AppEntry, newItem: AppEntry): Boolean {
                return oldItem == newItem
            }
        }
    }
}
