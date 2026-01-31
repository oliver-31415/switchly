package at.saltyy.switchly.feature.picker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.UsageLimitStore
import java.util.Locale

class AppListAdapter(
    private val allApps: List<AppEntry>,
    preselectedManaged: Set<String>,
    private val currentProfileProvider: () -> String?,
    private val onSetLimitClicked: (app: AppEntry) -> Unit
) : ListAdapter<AppEntry, AppListAdapter.VH>(DIFF) {

    private val managed = preselectedManaged.toMutableSet()

    fun getManagedPackages(): Set<String> = managed.toSet()

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

        private val limitRow: LinearLayout = v.findViewById(R.id.limitRow)
        private val ivTimer: ImageView = v.findViewById(R.id.ivTimer)
        private val tvState: TextView = v.findViewById(R.id.tvState)
        private val tvLimit: TextView = v.findViewById(R.id.tvLimit)

        private val btnLimit: ImageButton = v.findViewById(R.id.btnLimit)

        fun bind(item: AppEntry) {
            val ctx = itemView.context
            val profile = currentProfileProvider.invoke()

            // App icon (cached) to keep scrolling smooth
            ivAppIcon.setImageDrawable(AppIconCache.get(ctx, item.packageName))

            // app icon
            ivAppIcon.setImageDrawable(AppIconCache.get(ctx, item.packageName))

            tvLabel.text = item.label
            tvPkg.text = item.packageName

            val limitMin = if (!profile.isNullOrBlank()) {
                UsageLimitStore.getLimitMinutes(ctx, profile, item.packageName)
            } else 0

            val hasLimit = limitMin > 0
            val accent = ContextCompat.getColor(ctx, R.color.switchly_green)

            if (hasLimit) {
                limitRow.visibility = View.VISIBLE

                // icon tint
                ivTimer.setColorFilter(accent)

                // state + limit text
                tvState.text = ctx.getString(R.string.limit_set)
                tvLimit.text = ctx.getString(R.string.daily_limit_label, limitMin)

                tvState.setTextColor(accent)
                tvLimit.setTextColor(accent)
            } else {
                limitRow.visibility = View.GONE
            }

            // If limit is set -> ALWAYS managed
            if (hasLimit) {
                managed.add(item.packageName)
            }

            cb.setOnCheckedChangeListener(null)

            cb.isChecked = hasLimit || managed.contains(item.packageName)
            cb.isEnabled = !hasLimit
            cb.alpha = if (hasLimit) 0.65f else 1f

            cb.setOnCheckedChangeListener { _, checked ->
                if (checked) managed.add(item.packageName) else managed.remove(item.packageName)
            }

            btnLimit.setOnClickListener { onSetLimitClicked(item) }
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
