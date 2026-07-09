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

package at.saltyy.switchly.feature.inbox

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.BlockedInboxStore
import at.saltyy.switchly.data.prefs.BlockedNotificationEvent
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.SwitchlyDropdownAdapter
import at.saltyy.switchly.ui.attachEditDeleteSwipe
import at.saltyy.switchly.ui.updateSelectionSubtitle
import at.saltyy.switchly.ui.dialog.showDestructiveAccented
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.util.EditingLockGuard
import at.saltyy.switchly.widget.BlockedNotificationsWidgetProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import java.text.DateFormat
import java.util.Date

class BlockedInboxActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var empty: View
    private lateinit var toolbar: MaterialToolbar

    private val prefs by lazy { getSharedPreferences("switchly_prefs", MODE_PRIVATE) }

    private var allItems: List<BlockedNotificationEvent> = emptyList()
    private var visibleItems: List<BlockedNotificationEvent> = emptyList()

    private var sortNewestFirst: Boolean = true
    private var appFilter: String? = null

    private var selectionMode: Boolean = false
    private val selectedKeys: MutableSet<String> = linkedSetOf()

    private val dateFmt by lazy { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        if (SwitchModeStore.isEnabled(this) && !EmergencyBypassStore.isActive(this)) {
            EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_blocked_notifications)
            finish()
            return
        }
        setContentView(R.layout.activity_blocked_inbox)

        // Ensure selection checkboxes and other widgets never fall back to OEM green in CUSTOM accent mode.
        CustomAccentApplier.applyIfNeeded(this)

        toolbar = findViewById(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        sortNewestFirst = prefs.getBoolean(KEY_SORT_NEWEST, true)
        appFilter = prefs.getString(KEY_APP_FILTER, null)?.takeIf { it.isNotBlank() }
        intent.getStringExtra(EXTRA_APP_FILTER)?.takeIf { it.isNotBlank() }?.let { forced ->
            appFilter = forced
        }

        recycler = findViewById(R.id.recyclerInbox)
        empty = findViewById(R.id.tvEmpty)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.attachEditDeleteSwipe(
            canSwipe = { !selectionMode },
            editIconRes = R.drawable.open_in_new_24,
            onEdit = { position -> visibleItems.getOrNull(position)?.let(::showDetailDialog) },
            onDelete = { position -> visibleItems.getOrNull(position)?.let(::confirmDeleteSingle) }
        )

        findViewById<View>(R.id.btnFilter)?.setOnClickListener { showFilterMenuDialog() }

        load()
    }

    private fun load() {
        allItems = runCatching { BlockedInboxStore.getAll(this) }.getOrDefault(emptyList())
        applyFilterSort()
    }

    private fun applyFilterSort() {
        val filtered = if (appFilter.isNullOrBlank()) {
            allItems
        } else {
            allItems.filter { it.pkg == appFilter }
        }

        val sorted = if (sortNewestFirst) {
            filtered.sortedByDescending { it.timeMillis }
        } else {
            filtered.sortedBy { it.timeMillis }
        }

        visibleItems = sorted
        selectedKeys.retainAll(visibleItems.map { eventKey(it) }.toSet())
        if (selectionMode && selectedKeys.isEmpty()) {
            selectionMode = false
        }
        recycler.adapter = InboxAdapter(
            items = visibleItems,
            isSelectionMode = { selectionMode },
            isSelected = { e -> selectedKeys.contains(eventKey(e)) },
            onRowClick = { e ->
                if (selectionMode) {
                    toggleSelected(e)
                } else {
                    showDetailDialog(e)
                }
            },
            onRowLongPress = { e ->
                if (!selectionMode) {
                    enterSelectionMode(preselect = e)
                    true
                } else {
                    false
                }
            }
        )

        empty.visibility = if (visibleItems.isEmpty()) View.VISIBLE else View.GONE

        toolbar.updateSelectionSubtitle(selectionMode, selectedKeys.size, null)
        renderActiveFilterTags()
        invalidateOptionsMenu()
    }

    private fun enterSelectionMode(preselect: BlockedNotificationEvent? = null) {
        selectionMode = true
        selectedKeys.clear()
        preselect?.let { selectedKeys.add(eventKey(it)) }
        toolbar.updateSelectionSubtitle(true, selectedKeys.size)
        invalidateOptionsMenu()
        recycler.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedKeys.clear()
        toolbar.updateSelectionSubtitle(false, 0, null)
        renderActiveFilterTags()
        invalidateOptionsMenu()
        recycler.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
    }

    private fun toggleSelected(e: BlockedNotificationEvent) {
        val k = eventKey(e)
        if (selectedKeys.contains(k)) selectedKeys.remove(k) else selectedKeys.add(k)
        recycler.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }

        // keep subtitle useful in selection mode
        if (selectionMode) {
            toolbar.updateSelectionSubtitle(true, selectedKeys.size)
        }
    }

    private fun renderActiveFilterTags() {
        val scroll = findViewById<View>(R.id.activeFilterScroll) ?: return
        val group = findViewById<ChipGroup>(R.id.activeFilterChipGroup) ?: return
        group.removeAllViews()

        appFilter?.takeIf { it.isNotBlank() }?.let { pkg ->
            group.addView(activeFilterChip(getString(R.string.blocked_inbox_filter_tag_fmt, appLabel(pkg))) {
                appFilter = null
                prefs.edit { remove(KEY_APP_FILTER) }
                applyFilterSort()
            })
        }

        if (!sortNewestFirst) {
            group.addView(activeFilterChip(getString(R.string.blocked_inbox_sort_oldest)) {
                sortNewestFirst = true
                prefs.edit { putBoolean(KEY_SORT_NEWEST, true) }
                applyFilterSort()
            })
        }

        scroll.isVisible = group.isNotEmpty()
    }

    private fun activeFilterChip(label: String, onClear: () -> Unit): Chip {
        val accent = AccentColor.getAccentColorInt(this)
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        return Chip(this).apply {
            text = label
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            minHeight = dp(44)
            minimumHeight = dp(44)
            isSingleLine = true
            isCheckable = false
            isCloseIconVisible = true
            closeIconTint = ColorStateList.valueOf(onSurface)
            chipStrokeWidth = dp(1).toFloat()
            chipStrokeColor = ColorStateList.valueOf(accent)
            chipBackgroundColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x16))
            textSize = 13f
            setTextColor(onSurface)
            setOnClickListener { showFilterMenuDialog() }
            setOnCloseIconClickListener { onClear() }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun showDetailDialog(e: BlockedNotificationEvent) {
        val v = layoutInflater.inflate(R.layout.dialog_blocked_inbox_detail, FrameLayout(this), false)
        val tv = v.findViewById<TextView>(R.id.tvDetail)
        val detailIcon = v.findViewById<ImageView>(R.id.imgDetailIcon)
        val detailTitle = v.findViewById<TextView>(R.id.tvDetailTitle)
        val detailSubtitle = v.findViewById<TextView>(R.id.tvDetailSubtitle)

        fun line(label: String, value: String?): String {
            val clean = value?.trim()?.takeIf { it.isNotBlank() } ?: "-"
            return "$label: $clean"
        }

        val appName = appLabel(e.pkg)
        detailTitle.text = appName
        detailSubtitle.text = dateFmt.format(Date(e.timeMillis))
        appIcon(e.pkg)?.let { detailIcon.setImageDrawable(it) } ?: detailIcon.setImageResource(R.drawable.notifications_24)

        val profile = e.profile.takeIf { it.isNotBlank() }
        val title = e.title.takeIf { it.isNotBlank() }
        val text = e.text.takeIf { it.isNotBlank() }
        val bigText = e.bigText.takeIf { it.isNotBlank() }
        val subText = e.subText.takeIf { it.isNotBlank() }
        val summaryText = e.summaryText.takeIf { it.isNotBlank() }

        val lines = mutableListOf<String>()
        profile?.let { lines += line(getString(R.string.blocked_inbox_field_profile), it) }
        lines += line(getString(R.string.blocked_inbox_field_reason), e.reason)
        lines += line(getString(R.string.blocked_inbox_field_title), title)
        lines += line(getString(R.string.blocked_inbox_field_text), text)

        bigText?.let { lines += line(getString(R.string.blocked_inbox_field_big_text), it) }
        subText?.let { lines += line(getString(R.string.blocked_inbox_field_sub_text), it) }
        summaryText?.let { lines += line(getString(R.string.blocked_inbox_field_summary_text), it) }

        tv.text = lines.joinToString("\n\n")

        val detailDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.blocked_inbox_detail_title))
            .setView(v)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        detailDialog.setOnShowListener {
            detailDialog.styleSwitchlyDialogButtons()
        }
        detailDialog.show()
    }

    private fun confirmDeleteSingle(event: BlockedNotificationEvent) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.destructive_cannot_be_undone))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                BlockedInboxStore.remove(this, event)
                BlockedNotificationsWidgetProvider.refreshAll(this)
                load()
            }
            .showDestructiveAccented()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_blocked_inbox, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_cancel_select)?.isVisible = selectionMode
        // In selection mode, delete action is "Delete selected". Outside, it enters selection mode.
        menu.findItem(R.id.action_delete)?.title =
            if (selectionMode) getString(R.string.delete) else getString(R.string.select)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                if (selectionMode) {
                    exitSelectionMode(); true
                } else {
                    finish(); true
                }
            }

            R.id.action_cancel_select -> {
                exitSelectionMode(); true
            }

            R.id.action_delete -> {
                if (selectionMode) {
                    confirmDeleteSelected(); true
                } else {
                    enterSelectionMode(); true
                }
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showFilterMenuDialog() {
        val pkgs = allItems.map { it.pkg }.distinct().sortedBy { appLabel(it).lowercase() }
        val appLabels = mutableListOf(getString(R.string.blocked_inbox_filter_all_apps))
        val appValues = mutableListOf<String?>(null)
        pkgs.forEach { pkg ->
            appLabels += appLabel(pkg)
            appValues += pkg
        }

        val root = layoutInflater.inflate(R.layout.dialog_statistics_dropdown_sort_filter, FrameLayout(this), false)
        val filterLabel = root.findViewById<TextView>(R.id.tvStatsDropdownPrimaryLabel)
        val sortLabel = root.findViewById<TextView>(R.id.tvStatsDropdownSortLabel)
        val appDropdown = root.findViewById<MaterialAutoCompleteTextView>(R.id.dropdownStatsPrimary)
        val sortDropdown = root.findViewById<MaterialAutoCompleteTextView>(R.id.dropdownStatsSort)
        val extraFilter = root.findViewById<View>(R.id.cbStatsExtraFilter)

        filterLabel.text = getString(R.string.blocked_inbox_filter_app)
        sortLabel.text = getString(R.string.blocked_inbox_sort_title)
        extraFilter.visibility = View.GONE

        var selectedAppFilter = appFilter
        val appIndex = appValues.indexOf(appFilter).takeIf { it >= 0 } ?: 0
        appDropdown.setAdapter(SwitchlyDropdownAdapter(this, appLabels))
        appDropdown.setText(appLabels[appIndex], false)
        appDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedAppFilter = appValues.getOrNull(position)
        }
        appDropdown.setOnClickListener { appDropdown.showDropDown() }

        val sortOptions = listOf(
            true to getString(R.string.blocked_inbox_sort_newest),
            false to getString(R.string.blocked_inbox_sort_oldest)
        )
        var selectedSortNewest = sortNewestFirst
        sortDropdown.setAdapter(SwitchlyDropdownAdapter(this, sortOptions.map { it.second }))
        sortDropdown.setText(sortOptions.first { it.first == sortNewestFirst }.second, false)
        sortDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedSortNewest = sortOptions.getOrElse(position) { sortOptions.first() }.first
        }
        sortDropdown.setOnClickListener { sortDropdown.showDropDown() }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.blocked_inbox_filter_menu_title)
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.blocked_inbox_clear_filters) { _, _ ->
                appFilter = null
                sortNewestFirst = true
                prefs.edit {
                    remove(KEY_APP_FILTER)
                    putBoolean(KEY_SORT_NEWEST, true)
                }
                applyFilterSort()
            }
            .setPositiveButton(R.string.blocked_inbox_apply_filters) { _, _ ->
                appFilter = selectedAppFilter
                sortNewestFirst = selectedSortNewest
                prefs.edit {
                    val v = appFilter
                    if (v.isNullOrBlank()) remove(KEY_APP_FILTER) else putString(KEY_APP_FILTER, v)
                    putBoolean(KEY_SORT_NEWEST, sortNewestFirst)
                }
                applyFilterSort()
            }
            .create()
        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private fun confirmDeleteSelected() {
        if (selectedKeys.isEmpty()) {
            exitSelectionMode()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.blocked_inbox_delete_title))
            .setMessage(resources.getQuantityString(R.plurals.blocked_inbox_delete_selected_confirm_fmt, selectedKeys.size, selectedKeys.size) + "\n\n" + getString(R.string.destructive_cannot_be_undone))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                val toDelete = visibleItems.filter { selectedKeys.contains(eventKey(it)) }
                toDelete.forEach { BlockedInboxStore.remove(this, it) }
                BlockedNotificationsWidgetProvider.refreshAll(this)
                exitSelectionMode()
                load()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .showDestructiveAccented()
    }

    private fun eventKey(e: BlockedNotificationEvent): String {
        // Matches the fields used by BlockedInboxStore.remove()
        return listOf(e.timeMillis, e.pkg, e.reason, e.title, e.text).joinToString("|")
    }

    private fun appLabel(pkg: String): String {
        return runCatching {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            val label = packageManager.getApplicationLabel(ai)?.toString().orEmpty().trim()
            if (label.isNotBlank()) label else pkg
        }.getOrElse { pkg }
    }

    private fun appIcon(pkg: String): Drawable? {
        return runCatching {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationIcon(ai)
        }.getOrNull()
    }

    private class InboxAdapter(
        private val items: List<BlockedNotificationEvent>,
        private val isSelectionMode: () -> Boolean,
        private val isSelected: (BlockedNotificationEvent) -> Boolean,
        private val onRowClick: (BlockedNotificationEvent) -> Unit,
        private val onRowLongPress: (BlockedNotificationEvent) -> Boolean,
    ) : RecyclerView.Adapter<InboxAdapter.VH>() {

        private val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_blocked_inbox_event, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            holder.title.text = runCatching {
                val ai = holder.itemView.context.packageManager.getApplicationInfo(e.pkg, 0)
                holder.itemView.context.packageManager.getApplicationLabel(ai).toString()
            }.getOrDefault(e.pkg)

            val preview = listOfNotNull(
                e.title.takeIf { it.isNotBlank() },
                e.text.takeIf { it.isNotBlank() }
            ).joinToString(" — ").ifBlank {
                holder.itemView.context.getString(R.string.blocked_inbox_content_unknown)
            }
            holder.content.text = preview

            val profilePart = e.profile.takeIf { it.isNotBlank() }
                ?.let { " • ${holder.itemView.context.getString(R.string.blocked_inbox_field_profile)}: $it" }
                .orEmpty()

            holder.subtitle.text = holder.itemView.context.getString(
                R.string.blocked_inbox_subtitle_compact_fmt,
                fmt.format(Date(e.timeMillis)),
                profilePart
            )

            // Resolve app icon safely (some package names may no longer exist).
            val icon = runCatching {
                holder.itemView.context.packageManager.getApplicationIcon(e.pkg)
            }.getOrNull()
            if (icon != null) {
                holder.icon.setImageDrawable(icon)
            } else {
                holder.icon.setImageResource(R.drawable.notifications_24)
            }

            val selecting = isSelectionMode()
            val selected = selecting && isSelected(e)
            holder.checkbox.visibility = if (selecting) View.VISIBLE else View.GONE
            holder.checkbox.isChecked = selected
            (holder.itemView as? MaterialCardView)?.let { card ->
                val ctx = holder.itemView.context
                val accent = AccentColor.getAccentColorInt(ctx)
                if (selected) {
                    card.strokeWidth = (2 * holder.itemView.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                    card.strokeColor = accent
                    card.setCardBackgroundColor(ColorUtils.setAlphaComponent(accent, 0x14))
                } else {
                    card.strokeWidth = (1 * holder.itemView.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                    card.strokeColor = ContextCompat.getColor(ctx, R.color.switchly_card_stroke)
                    card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.switchly_card_bg))
                }
            }
            holder.more.visibility = if (selecting) View.GONE else View.VISIBLE

            holder.itemView.setOnClickListener { onRowClick(e) }
            holder.itemView.setOnLongClickListener { onRowLongPress(e) }
        }

        override fun getItemCount(): Int = items.size

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.imgIcon)
            val title: TextView = v.findViewById(R.id.tvTitle)
            val content: TextView = v.findViewById(R.id.tvContent)
            val subtitle: TextView = v.findViewById(R.id.tvSubtitle)
            val checkbox: com.google.android.material.checkbox.MaterialCheckBox = v.findViewById(R.id.cbSelect)
            val more: ImageView = v.findViewById(R.id.imgMore)
        }
    }

    companion object {
        const val EXTRA_APP_FILTER = "extra_app_filter"
        private const val KEY_SORT_NEWEST = "blocked_inbox_sort_newest"
        private const val KEY_APP_FILTER = "blocked_inbox_app_filter"
    }
}
