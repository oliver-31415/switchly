package at.saltyy.switchly.feature.stats

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.databinding.RowUsageStatBinding

/**
 * Uses the same pretty row as Usage (icon + progress bar), but for per-app blocked time.
 */
class RuntimeBlockedAdapter : ListAdapter<RuntimeBlockedRow, RuntimeBlockedAdapter.VH>(DIFF) {

    private var maxMs: Long = 0L

    fun submit(rows: List<RuntimeBlockedRow>) {
        // Use scoreMs so we still render meaningful bars even if blockedMs is 0
        // (e.g. because we redirect to home very fast).
        maxMs = rows.maxOfOrNull { it.blockedMs } ?: 0L
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
        fun bind(row: RuntimeBlockedRow, maxMs: Long) {
            val ctx = b.root.context
            b.tvApp.text = row.appName
            b.tvMeta.text = if (row.blockedMs > 0L) {
                // Real blocked time (no fake minutes)
                buildString {
                    append(ctx.getString(R.string.stats_blocked_time_fmt, StatsFormat.prettyMs(row.blockedMs)))
                    if (row.attemptCount > 0) {
                        append(" • Attempts ")
                        append(row.attemptCount)
                    }
                    if (row.blockedCount > 0) {
                        append(" • Blocks ")
                        append(row.blockedCount)
                    }
                }
            } else {
                // No blocked time recorded (very fast redirect), but still show counts
                buildString {
                    append("0 min blocked")
                    if (row.attemptCount > 0) {
                        append(" • Attempts ")
                        append(row.attemptCount)
                    }
                    if (row.blockedCount > 0) {
                        append(" • Blocks ")
                        append(row.blockedCount)
                    }
                }
            }

            val rel = if (maxMs <= 0L) 0 else ((row.blockedMs.toDouble() / maxMs.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            b.progress.progress = rel

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
