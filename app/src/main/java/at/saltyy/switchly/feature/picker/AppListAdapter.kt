package at.saltyy.switchly.feature.picker

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

    fun selectAllVisible() {
        currentList.forEachIndexed { index, item ->
            if (managed.add(item.packageName)) {
                notifyItemChanged(index)
            }
        }
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
        if (profile.isNullOrBlank() || !item.isAvailable) return false
        return UsageLimitStore.getLimitMinutes(context, profile, item.packageName) > 0 ||
            SessionLimitStore.getLimitMinutes(context, profile, item.packageName) > 0 ||
            AttemptLimitStore.getLimitAttempts(context, profile, item.packageName) > 0
    }

    init {
        // initial list
        submitList(allApps)
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        // stable id based on package name
        return getItem(position).packageName.hashCode().toLong()
    }

    fun notifyPkgChanged(pkg: String) {
        // refresh only if currently visible in list
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
        private val tvUnavailableChip: TextView = v.findViewById(R.id.tvUnavailableChip)
        private val tvUnavailableHint: TextView = v.findViewById(R.id.tvUnavailableHint)

        private val limitRow: LinearLayout = v.findViewById(R.id.limitRow)
        private val ivTimer: ImageView = v.findViewById(R.id.ivTimer)
        private val tvState: TextView = v.findViewById(R.id.tvState)
        private val tvLimit: TextView = v.findViewById(R.id.tvLimit)
        private val tvMetaSeparator: TextView = v.findViewById(R.id.tvMetaSeparator)

        private val btnLimit: ImageButton = v.findViewById(R.id.btnLimit)

        private fun dp(value: Float): Int =
            (value * itemView.resources.displayMetrics.density).toInt()

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

        private fun applyUnavailableChipStyle() {
            val ctx = itemView.context
            val chipBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999f).toFloat()
                setColor(ContextCompat.getColor(ctx, R.color.unavailable_chip_bg))
            }
            tvUnavailableChip.background = chipBg
            tvUnavailableChip.setTextColor(ContextCompat.getColor(ctx, R.color.unavailable_chip_text))
        }

        fun bind(item: AppEntry) {
            val ctx = itemView.context
            val profile = currentProfileProvider.invoke()

            // App icon (cached) to keep scrolling smooth
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
            val accent = AccentColor.getAccentColorInt(ctx)

            // Recycler rows are inflated asynchronously; keep checkbox tint in sync with active accent.
            cb.buttonTintList = AccentColor.getActiveColor(ctx)

            if (item.isAvailable) {
                // reset unavailable visuals for recycled holders
                itemView.background = null
                tvUnavailableChip.visibility = View.GONE
                tvUnavailableHint.visibility = View.GONE

                limitRow.orientation = LinearLayout.HORIZONTAL
                limitRow.gravity = android.view.Gravity.CENTER_VERTICAL
                ivTimer.visibility = View.VISIBLE
                tvMetaSeparator.visibility = View.VISIBLE

                val limitParams = tvLimit.layoutParams as ViewGroup.MarginLayoutParams
                limitParams.marginStart = 0
                tvLimit.layoutParams = limitParams

                if (hasLimit) {
                    limitRow.visibility = View.VISIBLE

                    // icon tint
                    ivTimer.setColorFilter(accent)

                    // state + limit text
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
                // unavailable/stale profile entry: show badge + compact remove hint
                applyUnavailableRowStyle()
                applyUnavailableChipStyle()
                tvUnavailableChip.visibility = View.VISIBLE
                tvUnavailableHint.visibility = View.VISIBLE
                tvUnavailableHint.text = ctx.getString(R.string.unavailable_app_remove_hint)

                // do not show limit info row for unavailable entries (keeps layout compact)
                limitRow.visibility = View.GONE
            }

            // If any limit is set on an installed app -> ALWAYS managed.
            if (hasLimit && item.isAvailable) {
                managed.add(item.packageName)
            }

            cb.setOnCheckedChangeListener(null)

            val lockSelectionDueToLimit = hasLimit && item.isAvailable
            cb.isChecked = hasLimit || managed.contains(item.packageName)
            cb.isEnabled = !lockSelectionDueToLimit
            cb.alpha = if (lockSelectionDueToLimit) 0.65f else 1f

            cb.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    managed.add(item.packageName)
                } else {
                    managed.remove(item.packageName)

                    // For unavailable apps, removing should fully clean stale limit state too.
                    if (!item.isAvailable && !profile.isNullOrBlank()) {
                        UsageLimitStore.setLimitMinutes(ctx, profile, item.packageName, 0)
                        SessionLimitStore.setLimitMinutes(ctx, profile, item.packageName, 0)
                        AttemptLimitStore.setLimitAttempts(ctx, profile, item.packageName, 0)
                        OpenCountStore.setToday(ctx, profile ?: "default", item.packageName, 0)
                        notifyPkgChanged(item.packageName)
                    }
                }
            }

            if (item.isAvailable) {
                btnLimit.visibility = View.VISIBLE
                btnLimit.isEnabled = true
                btnLimit.alpha = 1f
                btnLimit.setOnClickListener { onSetLimitClicked(item) }
                btnLimit.setOnLongClickListener {
                    onSetSessionLimitClicked?.invoke(item)
                    true
                }
            } else {
                // For unavailable apps the limit action is irrelevant and only adds UI noise.
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
                // if label/icon can change, include those fields too
                return oldItem == newItem
            }
        }
    }
}
