package at.saltyy.switchly.feature.stats

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.databinding.RowUsageStatBinding
import at.saltyy.switchly.theme.AccentColor
import kotlin.math.max
import kotlin.math.min

class StatsAdapter : ListAdapter<StatsRow, StatsAdapter.VH>(DIFF) {

    enum class RangeLabel { TODAY, WEEK, MONTH, YEAR, OVERALL }

    private var rangeLabel: RangeLabel = RangeLabel.TODAY
    private var maxUsedMsInList: Long = 0L
    private var totalUsedMsInList: Long = 0L

    fun submit(rows: List<StatsRow>, range: RangeLabel) {
        rangeLabel = range
        maxUsedMsInList = rows.maxOfOrNull { it.usedMsToday.coerceAtLeast(0L) } ?: 0L
        totalUsedMsInList = rows.sumOf { it.usedMsToday.coerceAtLeast(0L) }
        submitList(rows)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = RowUsageStatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), rangeLabel, maxUsedMsInList, totalUsedMsInList)
    }

    class VH(private val b: RowUsageStatBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(row: StatsRow, range: RangeLabel, maxUsedInList: Long, totalUsedInList: Long) {
            val ctx = itemView.context

            b.tvApp.visibility = View.VISIBLE
            b.tvMeta.visibility = View.VISIBLE
            b.tvPercent.visibility = View.VISIBLE
            b.progress.visibility = View.VISIBLE

            b.tvMeta.text = ""
            b.tvPercent.text = ""

            val defaultMetaColors = b.tvMeta.textColors
            val defaultPercentColors = b.tvPercent.textColors
            val defaultProgressTint: ColorStateList? = AccentColor.getActiveColor(ctx)

            b.tvApp.text = row.appName

            val usedMs = row.usedMsToday.coerceAtLeast(0L)
            val usedPretty = formatMsPretty(usedMs)

            val usedText = when (range) {
                RangeLabel.WEEK  -> ctx.getString(R.string.stats_used_week_fmt, usedPretty)
                RangeLabel.MONTH -> ctx.getString(R.string.stats_used_month_fmt, usedPretty)
                RangeLabel.YEAR  -> ctx.getString(R.string.stats_used_year_fmt, usedPretty)
                RangeLabel.TODAY -> ctx.getString(R.string.stats_used_today_fmt, usedPretty)
                RangeLabel.OVERALL -> ctx.getString(R.string.stats_used_overall_fmt, usedPretty)
            }

            b.progress.max = 100
            b.progress.progressTintList = defaultProgressTint

            // WEEK / MONTH / YEAR / OVERALL
            if (range != RangeLabel.TODAY) {
                b.tvMeta.setTextColor(defaultMetaColors)
                b.tvPercent.setTextColor(defaultPercentColors)
                b.progress.progressTintList = defaultProgressTint

                val relPercent = if (maxUsedInList > 0L) {
                    ((usedMs.toDouble() / maxUsedInList.toDouble()) * 100).toInt()
                } else 0

                val sharePercent = if (totalUsedInList > 0L) {
                    ((usedMs.toDouble() / totalUsedInList.toDouble()) * 100).toInt()
                } else 0

                b.progress.progress = relPercent.coerceIn(0, 100)
                b.tvMeta.text = usedText
                b.tvPercent.text = ctx.getString(
                    R.string.percent_fmt,
                    sharePercent.coerceIn(0, 100)
                )
                return
            }

            // TODAY
            val limitMin = max(0, row.limitMinutes)
            val limitMs = limitMin * 60_000L

            val limitText = if (limitMin > 0) {
                ctx.getString(R.string.stats_limit_fmt, limitMin)
            } else {
                ctx.getString(R.string.stats_limit_none)
            }

            val percentOfLimit = if (limitMs > 0L) {
                ((usedMs.toDouble() / limitMs.toDouble()) * 100).toInt()
            } else 0

            b.progress.progress = if (limitMs > 0L) min(percentOfLimit, 100) else 0

            val overLimit = (limitMs > 0L && percentOfLimit >= 100)

            if (overLimit) {
                val err = ContextCompat.getColor(ctx, R.color.status_error)
                b.tvMeta.setTextColor(err)
                b.tvPercent.setTextColor(err)
                b.progress.progressTintList = ContextCompat.getColorStateList(ctx, R.color.status_error)
            } else {
                b.tvMeta.setTextColor(defaultMetaColors)
                b.tvPercent.setTextColor(defaultPercentColors)
                b.progress.progressTintList = defaultProgressTint
            }

            b.tvMeta.text = ctx.getString(
                R.string.stats_meta_limit_used_fmt,
                limitText,
                usedText
            )

            b.tvPercent.text = if (limitMs > 0L) {
                ctx.getString(R.string.percent_fmt, percentOfLimit.coerceAtLeast(0))
            } else {
                ""
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
        private val DIFF = object : DiffUtil.ItemCallback<StatsRow>() {
            override fun areItemsTheSame(oldItem: StatsRow, newItem: StatsRow): Boolean {
                // stable identity: package
                return oldItem.packageName == newItem.packageName
            }

            override fun areContentsTheSame(oldItem: StatsRow, newItem: StatsRow): Boolean {
                return oldItem == newItem
            }
        }
    }
}
