package at.saltyy.switchly.feature.stats

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.BlockedInboxStore
import at.saltyy.switchly.data.prefs.BlockedNotificationEvent
import at.saltyy.switchly.databinding.ActivityBlockStatsDetailBinding
import at.saltyy.switchly.databinding.ItemBlockedInboxEventBinding
import at.saltyy.switchly.feature.inbox.BlockedInboxActivity
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Calendar

/**
 * Dedicated details screen for a single app in "Blocking" stats.
 * Replaces the old popup so users can explore attempts + blocked messages more comfortably.
 */
class BlockStatsDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PKG = "extra_pkg"
        const val EXTRA_RANGE = "extra_range" // StatsActivity.Range.name
        private val iconCache = LruCache<String, Drawable>(120)
    }

    private lateinit var b: ActivityBlockStatsDetailBinding

    private val pkg: String by lazy { intent.getStringExtra(EXTRA_PKG).orEmpty() }
    private val rangeName: String by lazy { intent.getStringExtra(EXTRA_RANGE).orEmpty() }

    private val previewAdapter = PreviewAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)

        if (pkg.isBlank()) {
            finish()
            return
        }

        b = ActivityBlockStatsDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = b.toolbar)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        // Header: icon + label
        val pm = packageManager
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            .getOrElse { pkg }
        b.tvAppName.text = label
        b.tvPackageName.text = pkg
        loadIconInto(b.ivAppIcon, pkg)

        b.recyclerPreview.layoutManager = LinearLayoutManager(this)
        b.recyclerPreview.adapter = previewAdapter

        b.btnOpenInbox.setOnClickListener {
            startActivity(
                Intent(this, BlockedInboxActivity::class.java)
                    .putExtra(BlockedInboxActivity.EXTRA_APP_FILTER, pkg)
            )
        }

        load()
    }

    override fun onResume() {
        super.onResume()
        b.toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val attempts = withContext(Dispatchers.IO) { loadAttemptsForRange(pkg, rangeName) }
            val inboxAll = withContext(Dispatchers.IO) { BlockedInboxStore.getAll(this@BlockStatsDetailActivity) }
            val forApp = inboxAll.filter { it.pkg == pkg }
            val blockedCount = forApp.size
            val last3 = forApp.sortedByDescending { it.timeMillis }.take(3)

            b.tvAttemptsValue.text = NumberFormat.getInstance().format(attempts)
            b.tvAttemptsCaption.text = getString(R.string.stats_detail_attempts_caption)
            b.tvBlockedMsgsValue.text = NumberFormat.getInstance().format(blockedCount)
            b.tvBlockedMsgsCaption.text = getString(R.string.stats_detail_blocked_msgs_caption)

            b.tvLast3Title.visibility = if (last3.isEmpty()) View.GONE else View.VISIBLE
            b.recyclerPreview.visibility = if (last3.isEmpty()) View.GONE else View.VISIBLE
            b.tvNoMessages.visibility = if (last3.isEmpty()) View.VISIBLE else View.GONE

            previewAdapter.submit(last3)
        }
    }

    private suspend fun loadAttemptsForRange(pkg: String, rangeName: String): Int {
        // Attempts are persisted in switchly_prefs keys: blocked_attempt_<YYYYMMDD>_<package>
        // StatsActivity reads these keys directly for "Attempts".
        // The Room daily stats can be missing attemptCount, so we read the same source-of-truth here.
        val sp = getSharedPreferences("switchly_prefs", MODE_PRIVATE)

        fun ymd(cal: Calendar): Int {
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            val d = cal.get(Calendar.DAY_OF_MONTH)
            return (y * 10000) + (m * 100) + d
        }

        fun readInt(vAny: Any?): Int {
            val v = when (vAny) {
                is Int -> vAny.toLong()
                is Long -> vAny
                is Float -> vAny.toLong()
                is Double -> vAny.toLong()
                is Number -> vAny.toLong()
                is String -> vAny.toLongOrNull() ?: 0L
                else -> 0L
            }
            return v.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(0)
        }

        fun key(dayYmd: Int): String = "blocked_attempt_${dayYmd}_${pkg}"

        return when (rangeName) {
            "OVERALL" -> {
                var sum = 0
                for ((k, vAny) in sp.all) {
                    if (!k.startsWith("blocked_attempt_")) continue
                    if (!k.endsWith("_${pkg}")) continue
                    sum += readInt(vAny)
                }
                sum
            }
            "WEEK", "MONTH", "YEAR", "TODAY", "" -> {
                val days = when (rangeName) {
                    "WEEK" -> 7
                    "MONTH" -> 30
                    "YEAR" -> 365
                    else -> 1
                }

                val cal = Calendar.getInstance()
                var sum = 0
                for (i in 0 until days) {
                    val day = ymd(cal)
                    sum += readInt(sp.all[key(day)])
                    cal.add(Calendar.DAY_OF_YEAR, -1)
                }
                sum
            }
            else -> {
                val cal = Calendar.getInstance()
                readInt(sp.all[key(ymd(cal))])
            }
        }
    }

    private fun loadIconInto(target: android.widget.ImageView, pkg: String) {
        val cached = iconCache.get(pkg)
        if (cached != null) {
            target.setImageDrawable(cached)
            return
        }
        runCatching {
            val icon = packageManager.getApplicationIcon(pkg)
            iconCache.put(pkg, icon)
            target.setImageDrawable(icon)
        }.onFailure {
            target.setImageResource(R.mipmap.ic_launcher_round)
        }
    }

    private inner class PreviewAdapter : RecyclerView.Adapter<PreviewVH>() {

        private var items: List<BlockedNotificationEvent> = emptyList()

        fun submit(list: List<BlockedNotificationEvent>) {
            val oldSize = items.size
            items = list

            // Avoid notifyDataSetChanged() (lint: NotifyDataSetChanged).
            // This list is tiny ("last 3"), so a simple range-based update is enough.
            val newSize = items.size
            val changed = minOf(oldSize, newSize)
            if (changed > 0) notifyItemRangeChanged(0, changed)
            if (newSize > oldSize) notifyItemRangeInserted(oldSize, newSize - oldSize)
            if (oldSize > newSize) notifyItemRangeRemoved(newSize, oldSize - newSize)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewVH {
            val b = ItemBlockedInboxEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return PreviewVH(b)
        }

        override fun onBindViewHolder(holder: PreviewVH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

    }

    private inner class PreviewVH(private val bRow: ItemBlockedInboxEventBinding) : RecyclerView.ViewHolder(bRow.root) {
        fun bind(e: BlockedNotificationEvent) {
            // Hide selection checkbox in preview
            bRow.cbSelect.visibility = View.GONE

            // Icon
            val cached = iconCache.get(e.pkg)
            if (cached != null) {
                bRow.imgIcon.setImageDrawable(cached)
            } else {
                runCatching {
                    val icon = packageManager.getApplicationIcon(e.pkg)
                    iconCache.put(e.pkg, icon)
                    bRow.imgIcon.setImageDrawable(icon)
                }.onFailure {
                    bRow.imgIcon.setImageResource(R.mipmap.ic_launcher_round)
                }
            }

            val title = e.title.ifBlank { getString(R.string.blocked_inbox_detail_title) }
            val content = listOf(e.text, e.bigText, e.summaryText).firstOrNull { it.isNotBlank() }.orEmpty()
            bRow.tvTitle.text = title
            bRow.tvContent.text = content

            val whenText = DateUtils.getRelativeTimeSpanString(e.timeMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            val reason = e.reason.takeIf { it.isNotBlank() }
            bRow.tvSubtitle.text = if (reason != null) {
                "$whenText • $reason"
            } else {
                whenText
            }

            itemView.setOnClickListener {
                // Open full inbox filtered to this app.
                startActivity(
                    Intent(this@BlockStatsDetailActivity, BlockedInboxActivity::class.java)
                        .putExtra(BlockedInboxActivity.EXTRA_APP_FILTER, pkg)
                )
            }
        }
    }
}