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
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
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
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.ui.dialog.showDestructiveAccented
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.ui.dialog.SwitchlyDialogOption
import at.saltyy.switchly.ui.dialog.showSwitchlyOptionDialog
import at.saltyy.switchly.ui.dialog.showSwitchlyMultiChoiceDialog
import at.saltyy.switchly.util.EditingLockGuard
import at.saltyy.switchly.widget.BlockedNotificationsWidgetProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

        findViewById<View>(R.id.btnFilter)?.setOnClickListener { showFilterMenuDialog() }
        findViewById<View>(R.id.btnAppFilterChip)?.setOnClickListener { showAppFilterDialog() }
        findViewById<View>(R.id.btnSortChip)?.setOnClickListener { showSortDialog() }

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

        toolbar.subtitle = buildSubtitle()
        updateFilterSortChips()
        invalidateOptionsMenu()
    }

    private fun enterSelectionMode(preselect: BlockedNotificationEvent? = null) {
        selectionMode = true
        selectedKeys.clear()
        preselect?.let { selectedKeys.add(eventKey(it)) }
        toolbar.subtitle = getString(R.string.blocked_inbox_select_mode_subtitle)
        invalidateOptionsMenu()
        recycler.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedKeys.clear()
        toolbar.subtitle = buildSubtitle()
        updateFilterSortChips()
        invalidateOptionsMenu()
        recycler.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
    }

    private fun toggleSelected(e: BlockedNotificationEvent) {
        val k = eventKey(e)
        if (selectedKeys.contains(k)) selectedKeys.remove(k) else selectedKeys.add(k)
        recycler.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }

        // keep subtitle useful in selection mode
        if (selectionMode) {
            toolbar.subtitle = resources.getQuantityString(R.plurals.blocked_inbox_select_count_fmt, selectedKeys.size, selectedKeys.size)
        }
    }

    private fun updateFilterSortChips() {
        val accent = AccentColor.getAccentColorInt(this)
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val filterLabel = appFilter?.let { appLabel(it) } ?: getString(R.string.blocked_inbox_chip_all_apps)
        val sortLabel = getString(if (sortNewestFirst) R.string.blocked_inbox_sort_newest else R.string.blocked_inbox_sort_oldest)
        fun MaterialButton.lockChipSize() {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(44)
            minimumHeight = dp(44)
            isSingleLine = true
            isAllCaps = false
            gravity = android.view.Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            iconPadding = dp(6)
        }
        findViewById<MaterialButton>(R.id.btnAppFilterChip)?.apply {
            lockChipSize()
            text = filterLabel
            setTextColor(onSurface)
            strokeColor = ColorStateList.valueOf(accent)
            iconTint = ColorStateList.valueOf(onSurface)
        }
        findViewById<MaterialButton>(R.id.btnSortChip)?.apply {
            lockChipSize()
            minWidth = 0
            minimumWidth = 0
            textSize = 13f
            text = sortLabel
            setTextColor(onSurface)
            strokeColor = ColorStateList.valueOf(accent)
            iconTint = ColorStateList.valueOf(onSurface)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildSubtitle(): String? {
        val sort = getString(if (sortNewestFirst) R.string.blocked_inbox_sort_newest else R.string.blocked_inbox_sort_oldest)
        val filter = appFilter?.let { getString(R.string.blocked_inbox_filter_subtitle_fmt, appLabel(it)) }
        return listOfNotNull(filter, sort).joinToString(" • ").takeIf { it.isNotBlank() }
    }

    private fun showDetailDialog(e: BlockedNotificationEvent) {
        val v = layoutInflater.inflate(R.layout.dialog_blocked_inbox_detail, null)
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
            .setNeutralButton(R.string.delete) { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete)
                    .setMessage(getString(R.string.destructive_cannot_be_undone))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        BlockedInboxStore.remove(this, e)
                        BlockedNotificationsWidgetProvider.refreshAll(this)
                        load()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .showDestructiveAccented()
            }
            .create()
        detailDialog.setOnShowListener {
            detailDialog.styleSwitchlyDialogButtons()
            val error = Color.rgb(186, 26, 26)
            detailDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.let { button ->
                button.setTextColor(error)
                button.backgroundTintList = null
                runCatching { button.setBackgroundColor(Color.TRANSPARENT) }
            }
        }
        detailDialog.show()
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
        val sortLabel = getString(if (sortNewestFirst) R.string.blocked_inbox_sort_newest else R.string.blocked_inbox_sort_oldest)
        val filterLabel = appFilter?.let { appLabel(it) } ?: getString(R.string.blocked_inbox_filter_all_apps)
        val options = listOf(
            SwitchlyDialogOption(
                title = getString(R.string.blocked_inbox_sort_title),
                summary = sortLabel,
                iconRes = R.drawable.sort_24
            ),
            SwitchlyDialogOption(
                title = getString(R.string.blocked_inbox_filter_app),
                summary = filterLabel,
                iconRes = R.drawable.apps_24
            )
        )

        showSwitchlyOptionDialog(
            title = getString(R.string.blocked_inbox_filter_menu_title),
            options = options,
            compact = false,
            showCancelButton = true
        ) { which ->
            when (which) {
                0 -> showSortDialog()
                1 -> showAppFilterDialog()
            }
        }
    }

    private fun showSortDialog() {
        val labels = listOf(
            getString(R.string.blocked_inbox_sort_newest),
            getString(R.string.blocked_inbox_sort_oldest)
        )
        val icons = listOf(R.drawable.arrow_downward_24, R.drawable.arrow_upward_24)
        val checked = if (sortNewestFirst) 0 else 1

        showRadioSingleChoiceDialog(
            title = getString(R.string.blocked_inbox_sort_title),
            entries = labels,
            selectedIndex = checked,
            icons = icons,
            compact = false
        ) { which ->
            sortNewestFirst = which == 0
            prefs.edit { putBoolean(KEY_SORT_NEWEST, sortNewestFirst) }
            applyFilterSort()
        }
    }

    private fun showAppFilterDialog() {
        val pkgs = allItems.map { it.pkg }.distinct().sortedBy { appLabel(it).lowercase() }
        if (pkgs.isEmpty()) return

        val entries = mutableListOf<String>()
        val values = mutableListOf<String?>()

        entries += getString(R.string.blocked_inbox_filter_all_apps)
        values += null

        pkgs.forEach { pkg ->
            entries += appLabel(pkg)
            values += pkg
        }

        val selectedIndex = values.indexOf(appFilter).takeIf { it >= 0 } ?: 0
        val icons = values.map { pkg -> pkg?.let { appIcon(it) } }

        showRadioSingleChoiceDialog(
            title = getString(R.string.blocked_inbox_filter_app),
            entries = entries,
            selectedIndex = selectedIndex,
            iconDrawables = icons,
            fallbackIconRes = R.drawable.apps_24,
            compact = false
        ) { which ->
            appFilter = values.getOrNull(which)
            prefs.edit {
                val v = appFilter
                if (v.isNullOrBlank()) remove(KEY_APP_FILTER) else putString(KEY_APP_FILTER, v)
            }
            applyFilterSort()
        }
    }

    /**
     * Shared Switchly card-choice dialog.
     * No radios/checkmarks; selected row uses only accent border/background.
     */
    private fun showRadioSingleChoiceDialog(
        title: String,
        entries: List<String>,
        selectedIndex: Int,
        icons: List<Int?>? = null,
        iconDrawables: List<Drawable?>? = null,
        fallbackIconRes: Int? = null,
        compact: Boolean = false,
        onPicked: (Int) -> Unit
    ) {
        showSwitchlyOptionDialog(
            title = title,
            options = entries.mapIndexed { index, label ->
                SwitchlyDialogOption(
                    title = label,
                    iconRes = iconDrawables?.getOrNull(index)?.let { null } ?: icons?.getOrNull(index) ?: fallbackIconRes,
                    iconDrawable = iconDrawables?.getOrNull(index),
                    selected = index == selectedIndex
                )
            },
            compact = compact,
            showCancelButton = false
        ) { which ->
            onPicked(which)
        }
    }

    private fun showDeleteDialog() {
        if (visibleItems.isEmpty()) return

        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = visibleItems.map { e ->
            val app = appLabel(e.pkg)
            val preview = listOfNotNull(
                e.title.takeIf { it.isNotBlank() },
                e.text.takeIf { it.isNotBlank() }
            ).joinToString(" — ").ifBlank {
                getString(R.string.blocked_inbox_content_unknown)
            }
            "$app • ${fmt.format(Date(e.timeMillis))}\n$preview"
        }.toTypedArray()

        val checked = BooleanArray(labels.size) { false }

        showSwitchlyMultiChoiceDialog(
            title = getString(R.string.blocked_inbox_delete_title),
            options = visibleItems.mapIndexed { index, e ->
                SwitchlyDialogOption(
                    title = labels[index],
                    iconDrawable = appIcon(e.pkg),
                    destructive = true
                )
            },
            checked = checked,
            positiveTextRes = R.string.delete,
            compact = true
        ) { result ->
            val toDelete = visibleItems.filterIndexed { idx, _ -> result.getOrNull(idx) == true }
            toDelete.forEach { BlockedInboxStore.remove(this, it) }
            BlockedNotificationsWidgetProvider.refreshAll(this)
            load()
        }
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
                val accent = AccentColor.getAccentColorInt(holder.itemView.context)
                card.strokeWidth = if (selected) (2 * holder.itemView.resources.displayMetrics.density).toInt().coerceAtLeast(1) else 0
                card.strokeColor = if (selected) accent else Color.TRANSPARENT
                card.setCardBackgroundColor(
                    if (selected) ColorUtils.setAlphaComponent(accent, 0x14)
                    else MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorSurface)
                )
            }

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
        }
    }

    private class RadioSingleChoiceAdapter(
        private val entries: List<String>,
        selectedIndex: Int,
        private val onPick: (Int) -> Unit
    ) : RecyclerView.Adapter<RadioSingleChoiceAdapter.VH>() {

        private var selected = selectedIndex

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_single_select_checkbox, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.title.text = entries[position]

            // Round selection indicator + consistent accent (including CUSTOM).
            val ctx = holder.itemView.context
            val accent = AccentColor.getAccentColorInt(ctx)
            val onSurface = MaterialColors.getColor(holder.radio, com.google.android.material.R.attr.colorOnSurface)
            val unchecked = ColorUtils.setAlphaComponent(onSurface, 0x88)
            holder.radio.buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, unchecked)
            )
            runCatching { holder.radio.setUseMaterialThemeColors(false) }

            holder.radio.isChecked = position == selected

            holder.itemView.setOnClickListener {
                val adapterPosition = holder.bindingAdapterPosition
                if (adapterPosition == RecyclerView.NO_POSITION) return@setOnClickListener

                val old = selected
                selected = adapterPosition
                if (old != adapterPosition) {
                    notifyItemChanged(old)
                    notifyItemChanged(adapterPosition)
                } else {
                    notifyItemChanged(adapterPosition)
                }
                onPick(adapterPosition)
            }
        }

        override fun getItemCount(): Int = entries.size

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val radio: com.google.android.material.radiobutton.MaterialRadioButton = v.findViewById(R.id.radio)
            val title: TextView = v.findViewById(R.id.title)
        }
    }

    companion object {
        const val EXTRA_APP_FILTER = "extra_app_filter"
        private const val KEY_SORT_NEWEST = "blocked_inbox_sort_newest"
        private const val KEY_APP_FILTER = "blocked_inbox_app_filter"
    }
}
