package at.saltyy.switchly.feature.stats

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.databinding.RowUsageStatBinding
import at.saltyy.switchly.theme.AccentColor

class BlockStatsAdapter : ListAdapter<BlockStatsRow, BlockStatsAdapter.VH>(DIFF) {

    private var maxBlockedMs: Long = 0L
    private var totalBlockedMs: Long = 0L

    fun submit(rows: List<BlockStatsRow>) {
        maxBlockedMs = rows.maxOfOrNull { it.blockedMs.coerceAtLeast(0L) } ?: 0L
        totalBlockedMs = rows.sumOf { it.blockedMs.coerceAtLeast(0L) }
        submitList(rows)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = RowUsageStatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), maxBlockedMs, totalBlockedMs)
    }

    class VH(private val b: RowUsageStatBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: BlockStatsRow, maxBlockedMs: Long, totalBlockedMs: Long) {
            val ctx = itemView.context

            b.tvApp.text = row.appName

            val blockedPretty = formatMsPretty(row.blockedMs)
            b.tvMeta.text = ctx.getString(R.string.stats_blocked_time_fmt, blockedPretty)
            val blocksStr = ctx.resources.getQuantityString(
                R.plurals.stats_blocks_qty,
                row.blockedCount,
                row.blockedCount
            )
            val attemptsStr = ctx.resources.getQuantityString(
                R.plurals.stats_attempts_qty,
                row.attemptCount,
                row.attemptCount
            )
            b.tvPercent.text = ctx.getString(R.string.stats_blocked_counts_join, blocksStr, attemptsStr)

            b.progress.visibility = View.VISIBLE
            b.progress.max = 100
            val relPercent = if (maxBlockedMs > 0L) {
                ((row.blockedMs.toDouble() / maxBlockedMs.toDouble()) * 100).toInt()
            } else 0
            b.progress.progress = relPercent.coerceIn(0, 100)
            b.progress.progressTintList = AccentColor.getActiveColor(ctx)

            // Share percent appended if there is meaningful total
            if (totalBlockedMs > 0L) {
                val share = ((row.blockedMs.toDouble() / totalBlockedMs.toDouble()) * 100).toInt()
                b.tvPercent.append("  •  ${share.coerceIn(0, 100)}%")
            }
        }

        private fun formatMsPretty(ms: Long): String {
            if (ms <= 0L) return "0m"
            val totalSec = (ms / 1000L).toInt()
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return when {
                h == 0 && m == 0 -> "${s}s"
                h == 0 -> if (s > 0) "${m}m ${s}s" else "${m}m"
                else -> "%dh %02dm".format(h, m)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BlockStatsRow>() {
            override fun areItemsTheSame(oldItem: BlockStatsRow, newItem: BlockStatsRow): Boolean {
                return oldItem.packageName == newItem.packageName
            }

            override fun areContentsTheSame(oldItem: BlockStatsRow, newItem: BlockStatsRow): Boolean {
                return oldItem == newItem
            }
        }
    }
}
