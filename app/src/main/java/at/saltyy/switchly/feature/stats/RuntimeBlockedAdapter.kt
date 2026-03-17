package at.saltyy.switchly.feature.stats

import android.view.LayoutInflater
import android.view.ViewGroup
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.graphics.ColorUtils
import at.saltyy.switchly.R
import at.saltyy.switchly.databinding.RowUsageStatBinding
import at.saltyy.switchly.theme.AccentColor
import com.google.android.material.color.MaterialColors

// Uses the same pretty row as Usage (icon + progress bar), but for Runtime insights.
class RuntimeBlockedAdapter : ListAdapter<RuntimeBlockedRow, RuntimeBlockedAdapter.VH>(DIFF) {

    private var maxMs: Long = 0L

    fun submit(rows: List<RuntimeBlockedRow>) {
        // scoreMs is our sorting/progress metric (currently: attempts as a Long)
        maxMs = rows.maxOfOrNull { it.scoreMs } ?: 0L
        submitList(rows)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = RowUsageStatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), maxMs)
    }

    class VH(private val b: RowUsageStatBinding) : RecyclerView.ViewHolder(b.root) {

        companion object {
            private val iconCache = LruCache<String, Drawable>(120)
        }
        fun bind(row: RuntimeBlockedRow, maxMs: Long) {
            val ctx = b.root.context

            // App icon
            val cached = iconCache.get(row.packageName)
            if (cached != null) {
                b.ivAppIcon.setImageDrawable(cached)
            } else {
                runCatching {
                    val icon = ctx.packageManager.getApplicationIcon(row.packageName)
                    iconCache.put(row.packageName, icon)
                    b.ivAppIcon.setImageDrawable(icon)
                }.onFailure {
                    b.ivAppIcon.setImageResource(R.mipmap.ic_launcher_round)
                }
            }

            b.tvApp.text = row.appName
            // Runtime: blocked time is unreliable/noisy, so we show attempts only.
            b.tvMeta.text = ctx.getString(R.string.stats_attempts_line, row.attemptCount)

            val rel = if (maxMs <= 0L) 0 else ((row.scoreMs.toDouble()/maxMs.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            b.progress.progress = rel
            val accent = AccentColor.getAccentColorInt(ctx)
            b.progress.setIndicatorColor(accent)
            b.progress.trackColor = ColorUtils.setAlphaComponent(
                MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, 0),
                0x22
            )

            // Share of total isn't necessary here; keep it simple but consistent.
            b.tvPercent.text = ctx.getString(R.string.percent_fmt, rel)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RuntimeBlockedRow>() {
            override fun areItemsTheSame(oldItem: RuntimeBlockedRow, newItem: RuntimeBlockedRow): Boolean =
                oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: RuntimeBlockedRow, newItem: RuntimeBlockedRow): Boolean =
                oldItem == newItem
        }
    }
}
