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
import kotlin.math.roundToInt

class ScreenTimeAdapter : ListAdapter<ScreenTimeRow, ScreenTimeAdapter.VH>(DIFF) {

    private var maxUsedMs: Long = 0L
    private var totalUsedMs: Long = 0L

    fun submit(rows: List<ScreenTimeRow>) {
        maxUsedMs = rows.maxOfOrNull { it.usedMs.coerceAtLeast(0L) } ?: 0L
        totalUsedMs = rows.sumOf { it.usedMs.coerceAtLeast(0L) }
        submitList(rows)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = RowUsageStatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), maxUsedMs, totalUsedMs)
    }

    class VH(private val b: RowUsageStatBinding) : RecyclerView.ViewHolder(b.root) {

        companion object {
            private val iconCache = LruCache<String, Drawable>(120)
        }

        fun bind(row: ScreenTimeRow, maxUsedMs: Long, totalUsedMs: Long) {
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

            b.tvApp.text = row.appName
            b.tvMeta.text = ctx.getString(R.string.screen_time_used_fmt, formatMsPretty(row.usedMs))

            val share = if (totalUsedMs > 0L) {
                ((row.usedMs.toDouble()/totalUsedMs.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
            } else 0
            b.tvPercent.text = ctx.getString(R.string.percent_fmt, share)

            b.progress.visibility = View.VISIBLE
            b.progress.max = 100
            val rel = if (maxUsedMs > 0L) {
                ((row.usedMs.toDouble()/maxUsedMs.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
            } else 0
            b.progress.progress = rel
            val accent = AccentColor.getAccentColorInt(ctx)
            b.progress.setIndicatorColor(accent)
            b.progress.trackColor = ColorUtils.setAlphaComponent(
                MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, 0),
                0x22
            )
        }

        private fun formatMsPretty(ms: Long): String {
            if (ms <= 0L) return "0m"
            val totalSec = (ms/1000L).toInt()
            val h = totalSec/3600
            val m = (totalSec % 3600)/60
            val s = totalSec % 60
            return when {
                h == 0 && m == 0 -> "${s}s"
                h == 0 -> if (s > 0) "${m}m ${s}s" else "${m}m"
                else -> "%dh %02dm".format(h, m)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ScreenTimeRow>() {
            override fun areItemsTheSame(oldItem: ScreenTimeRow, newItem: ScreenTimeRow): Boolean {
                return oldItem.packageName == newItem.packageName
            }

            override fun areContentsTheSame(oldItem: ScreenTimeRow, newItem: ScreenTimeRow): Boolean {
                return oldItem == newItem
            }
        }
    }
}
