package at.saltyy.switchly.feature.stats

import android.view.LayoutInflater
import android.view.View
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

class BlockStatsAdapter(private val onRowClick: (BlockStatsRow) -> Unit) : ListAdapter<BlockStatsRow, BlockStatsAdapter.VH>(DIFF) {

    private var maxAttempts: Int = 0
    private var totalAttempts: Int = 0

    fun submit(rows: List<BlockStatsRow>) {
        maxAttempts = rows.maxOfOrNull { it.attemptCount.coerceAtLeast(0) } ?: 0
        totalAttempts = rows.sumOf { it.attemptCount.coerceAtLeast(0) }
        submitList(rows)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = RowUsageStatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), maxAttempts, totalAttempts, onRowClick)
    }

    class VH(private val b: RowUsageStatBinding) : RecyclerView.ViewHolder(b.root) {

        companion object {
            private val iconCache = LruCache<String, Drawable>(120)
        }
        fun bind(row: BlockStatsRow, maxAttempts: Int, totalAttempts: Int, onRowClick: (BlockStatsRow) -> Unit) {
            val ctx = itemView.context

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

            itemView.setOnClickListener { onRowClick(row) }

            b.tvApp.text = row.appName

            // Blocking: show Attempts only (no blocks/blocked time).
            b.tvMeta.text = ctx.getString(R.string.stats_attempts_line, row.attemptCount.coerceAtLeast(0))

            val rel = if (maxAttempts <= 0) 0 else ((row.attemptCount.toDouble()/maxAttempts.toDouble()) * 100.0)
                .toInt()
                .coerceIn(0, 100)
            b.progress.visibility = View.VISIBLE
            b.progress.progress = rel
            val accent = AccentColor.getAccentColorInt(ctx)
            b.progress.setIndicatorColor(accent)
            b.progress.trackColor = ColorUtils.setAlphaComponent(
                MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, 0),
                0x22
            )

            val share = if (totalAttempts <= 0) 0 else ((row.attemptCount.toDouble()/totalAttempts.toDouble()) * 100.0)
                .toInt()
                .coerceIn(0, 100)
            b.tvPercent.text = ctx.getString(R.string.percent_fmt, share)
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
