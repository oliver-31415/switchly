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

package at.saltyy.switchly.feature.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.DomainBlockStore
import at.saltyy.switchly.data.prefs.DomainLimitStore
import at.saltyy.switchly.data.prefs.WebsiteRuleModeStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.SegmentedToggleUi
import at.saltyy.switchly.ui.SwitchlyDropdownAdapter
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.attachEditDeleteSwipe
import at.saltyy.switchly.ui.updateSelectionSubtitle
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.ui.dialog.styleSwitchlyDestructivePositiveButton
import at.saltyy.switchly.ui.dialog.showDestructiveAccented
import at.saltyy.switchly.util.EditingLockGuard
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class ManageBlockedWebsitesActivity : AppCompatActivity() {

    private fun websiteEditingLocked(): Boolean {
        return EditingLockGuard.isLocked(this)
    }

    private fun syncEditingLockUi() {
        val locked = EditingLockGuard.isLocked(this)
        findViewById<FloatingActionButton>(R.id.fabAdd)?.apply {
            isEnabled = !locked && !isSelectionMode
            isClickable = !locked && !isSelectionMode
            alpha = if (locked) 0.45f else 1f
        }
        findViewById<View>(R.id.btnEmptyAddWebsite)?.apply {
            isEnabled = !locked
            isClickable = !locked
            alpha = if (locked) 0.45f else 1f
        }
        findViewById<MaterialButtonToggleGroup>(R.id.toggleWebsiteRuleMode)?.apply {
            isEnabled = !locked
            alpha = if (locked) 0.62f else 1f
        }
        findViewById<View>(R.id.btnWebsiteModeBlock)?.isEnabled = !locked
        findViewById<View>(R.id.btnWebsiteModeAllow)?.isEnabled = !locked

        if (::adapter.isInitialized && adapter.itemCount > 0) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }
        invalidateOptionsMenu()
    }

    private var emptyCard: View? = null
    private lateinit var rv: RecyclerView
    private lateinit var emptyTitle: TextView
    private lateinit var emptyBody: TextView
    private lateinit var adapter: DomainRuleAdapter
    private lateinit var toolbar: MaterialToolbar

    private var isSelectionMode: Boolean = false
    private val selectedDomains = linkedSetOf<String>()

    private fun currentProfile(): String = ProfileStore.getCurrent(this) ?: "default"

    private fun isAllowMode(): Boolean =
        WebsiteRuleModeStore.isAllowMode(this, currentProfile())

    private fun syncRuleModeUi() {
        val allow = isAllowMode()
        toolbar.title = getString(R.string.website_rules_title)
        toolbar.subtitle = websiteRulesSubtitle()
        emptyTitle.text = getString(
            if (allow) R.string.allowed_websites_empty_title else R.string.blocked_websites_empty_title
        )
        emptyBody.text = getString(
            if (allow) R.string.allowed_websites_empty_body else R.string.blocked_websites_empty_body
        )
        findViewById<MaterialButtonToggleGroup>(R.id.toggleWebsiteRuleMode)?.check(
            if (allow) R.id.btnWebsiteModeAllow else R.id.btnWebsiteModeBlock
        )
        findViewById<TextView>(R.id.tvWebsiteRuleModeSummary)?.text = getString(
            if (allow) R.string.website_rule_mode_allow_summary else R.string.website_rule_mode_block_summary
        )
        applyWebsiteRuleModeButtonStyle()
    }

    private fun websiteRulesSubtitle(): String {
        return getString(R.string.blocked_websites_profile_subtitle, currentProfile())
    }

    private fun setupWebsiteRuleMode() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.toggleWebsiteRuleMode) ?: return
        group.check(if (isAllowMode()) R.id.btnWebsiteModeAllow else R.id.btnWebsiteModeBlock)
        group.addOnButtonCheckedListener { toggleGroup, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (websiteEditingLocked()) {
                toggleGroup.check(if (isAllowMode()) R.id.btnWebsiteModeAllow else R.id.btnWebsiteModeBlock)
                return@addOnButtonCheckedListener
            }
            val mode = if (checkedId == R.id.btnWebsiteModeAllow) {
                WebsiteRuleModeStore.MODE_ALLOW_SELECTED
            } else {
                WebsiteRuleModeStore.MODE_BLOCK_SELECTED
            }
            if (mode == WebsiteRuleModeStore.getMode(this, currentProfile())) return@addOnButtonCheckedListener
            WebsiteRuleModeStore.setMode(this, currentProfile(), mode)
            syncRuleModeUi()
            refreshList()
        }
        applyWebsiteRuleModeButtonStyle()
    }

    private fun applyWebsiteRuleModeButtonStyle() {
        val blockButton = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnWebsiteModeBlock)
        val allowButton = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnWebsiteModeAllow)
        SegmentedToggleUi.apply(
            this,
            listOf(blockButton, allowButton),
            if (isAllowMode()) allowButton.id else blockButton.id,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage_blocked_websites)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        rv = findViewById(R.id.list)
        emptyTitle = findViewById(R.id.tvEmptyTitle)
        emptyBody = findViewById(R.id.tvEmptyBody)
        emptyCard = findViewById(R.id.emptyCard)

        adapter = DomainRuleAdapter(
            onEdit = { showEditDialog(it) },
            onToggleEnabled = { domain, enabled -> setRuleEnabled(domain, enabled) },
            onToggleSelection = { toggleSelection(it) },
            isSelectionMode = { isSelectionMode },
            isSelected = { selectedDomains.contains(it) }
        )

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.attachEditDeleteSwipe(
            canSwipe = { !isSelectionMode && !EditingLockGuard.isLocked(this) },
            onEdit = { position ->
                adapter.itemAt(position)?.let { showEditDialog(it.domain) }
            },
            onDelete = { position ->
                adapter.itemAt(position)?.let { confirmDeleteSingle(it.domain) }
            }
        )

        DomainBlockStore.migrateLegacyDomainsIntoCurrentProfileIfNeeded(this)
        setupWebsiteRuleMode()
        syncRuleModeUi()

        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            if (websiteEditingLocked()) return@setOnClickListener
            showAddDialog()
        }
        findViewById<View>(R.id.btnEmptyAddWebsite).setOnClickListener {
            if (websiteEditingLocked()) return@setOnClickListener
            showAddDialog()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SwitchModeStore.enabledFlow.collect {
                    runOnUiThread { syncEditingLockUi() }
                }
            }
        }

        updateMenuState()
    }

    override fun onResume() {
        super.onResume()
        syncRuleModeUi()
        refreshList()
        syncEditingLockUi()
    }

    private fun refreshList() {
        val blocked = DomainBlockStore.getDomains(this)
        val limited = DomainLimitStore.getDomainsWithLimit(this)

        val all = (blocked + limited)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSortedSet()

        val rules = all.map { d ->
            DomainRule(
                domain = d,
                isHardBlocked = blocked.contains(d),
                limitMin = DomainLimitStore.getLimitMinutes(this, d),
                enabled = DomainBlockStore.isDomainEnabled(this, d)
            )
        }

        adapter.submit(rules)
        emptyCard?.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE

        // Keep selection consistent.
        selectedDomains.retainAll(rules.map { it.domain }.toSet())
        if (isSelectionMode && selectedDomains.isEmpty()) {
            exitSelectionMode()
        } else {
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
            updateMenuState()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_manage_blocked_websites, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val readOnly = websiteEditingLocked()
        val hasItems = adapter.itemCount > 0
        menu.findItem(R.id.action_browser_support)?.isVisible = true
        menu.findItem(R.id.action_select)?.isVisible = !readOnly && !isSelectionMode && hasItems
        menu.findItem(R.id.action_cancel_selection)?.isVisible = !readOnly && isSelectionMode
        menu.findItem(R.id.action_delete_selected)?.isVisible = !readOnly && isSelectionMode

        val deleteItem = menu.findItem(R.id.action_delete_selected)
        deleteItem?.isEnabled = selectedDomains.isNotEmpty()
        deleteItem?.alphaCompat(if (selectedDomains.isNotEmpty()) 1f else 0.4f)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_browser_support -> {
                showSupportedBrowsersInfo()
                true
            }
            R.id.action_select -> {
                enterSelectionMode()
                true
            }
            R.id.action_cancel_selection -> {
                exitSelectionMode()
                true
            }
            R.id.action_delete_selected -> {
                confirmDeleteSelected()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateMenuState() {
        invalidateOptionsMenu()
        findViewById<FloatingActionButton>(R.id.fabAdd)?.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
        toolbar.updateSelectionSubtitle(
            selectionMode = isSelectionMode,
            selectedCount = selectedDomains.size,
            normalSubtitle = websiteRulesSubtitle()
        )
        syncEditingLockUi()
    }

    private fun normalizeDialogBreaks(text: String): String =
        text.replace("/n", "\n").replace("\\n", "\n")

    private fun showSupportedBrowsersInfo() {
        AlertDialog.Builder(this)
            .setTitle(R.string.website_rules_info_title)
            .setMessage(normalizeDialogBreaks(getString(R.string.website_rules_info_body)))
            .setPositiveButton(android.R.string.ok, null)
            .show()
            .styleSwitchlyDialogButtons()
    }

    private fun enterSelectionMode(preselect: String? = null) {
        if (websiteEditingLocked()) {
            return
        }
        isSelectionMode = true
        selectedDomains.clear()
        preselect?.let { selectedDomains.add(it) }
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        updateMenuState()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedDomains.clear()
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        updateMenuState()
    }

    private fun toggleSelection(domain: String) {
        if (!isSelectionMode) {
            return
        }
        if (selectedDomains.contains(domain)) {
            selectedDomains.remove(domain)
        } else {
            selectedDomains.add(domain)
        }
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        updateMenuState()
    }

    private fun confirmDeleteSelected() {
        if (websiteEditingLocked()) {
            return
        }
        if (selectedDomains.isEmpty()) {
            return
        }
        val count = selectedDomains.size
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete))
            .setMessage(resources.getQuantityString(R.plurals.delete_websites_confirm, count, count) + "\n\n" + getString(R.string.destructive_cannot_be_undone))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete, null)
            .create()

        dlg.setOnShowListener {
            dlg.styleSwitchlyDestructivePositiveButton()
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                selectedDomains.toList().forEach { removeRule(it) }
                exitSelectionMode()
                dlg.dismiss()
            }
        }

        dlg.show()
    }

    private fun removeRule(domain: String) {
        if (websiteEditingLocked()) {
            return
        }
        DomainBlockStore.removeDomain(this, domain)
        DomainLimitStore.clear(this, domain)
        refreshList()
    }

    private fun setRuleEnabled(domain: String, enabled: Boolean) {
        if (websiteEditingLocked()) {
            return
        }
        DomainBlockStore.setDomainEnabled(this, domain, enabled)
        refreshList()
    }

    private fun confirmDeleteSingle(domain: String) {
        if (websiteEditingLocked()) {
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(
                domain + "\n\n" + getString(R.string.destructive_cannot_be_undone)
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> removeRule(domain) }
            .showDestructiveAccented()
    }

    private fun showAddDialog() {
        if (websiteEditingLocked()) {
            return
        }
        showRuleDialog(
            title = getString(R.string.add_website_rule_title),
            initialDomain = "",
            initialHardBlock = true,
            initialLimit = 0,
            allowDomainEdit = true
        )
    }

    private fun showEditDialog(domain: String) {
        if (websiteEditingLocked()) {
            return
        }
        val hard = DomainBlockStore.getDomains(this).contains(domain)
        val limit = DomainLimitStore.getLimitMinutes(this, domain)

        showRuleDialog(
            title = domain,
            initialDomain = domain,
            initialHardBlock = hard,
            initialLimit = limit,
            allowDomainEdit = false
        )
    }

    private fun showRuleDialog(
        title: String,
        initialDomain: String,
        initialHardBlock: Boolean,
        initialLimit: Int,
        allowDomainEdit: Boolean
    ) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_domain_rule, FrameLayout(this), false)

        // In CUSTOM accent mode, ensure TextInput cursor/selection and dropdown indicators don't fall back to green.
        if (CustomAccentApplier.isCustomAccentEnabled(this)) {
            runCatching { CustomAccentApplier.applyToView(v, this) }
        }

        val tilDomain = v.findViewById<TextInputLayout>(R.id.tilDomain)
        val etDomain = v.findViewById<TextInputEditText>(R.id.etDomain)
        val acMode = v.findViewById<AutoCompleteTextView>(R.id.acMode)
        val tilLimit = v.findViewById<TextInputLayout>(R.id.tilDailyLimit)
        val etLimit = v.findViewById<TextInputEditText>(R.id.etDailyLimit)

        etDomain.setText(initialDomain)
        etDomain.isEnabled = allowDomainEdit

        val modeAlways = getString(if (isAllowMode()) R.string.rule_allowed_always else R.string.rule_block_always)
        val modeLimit = getString(R.string.rule_daily_limit)

        val modeAdapter = SwitchlyDropdownAdapter(this, listOf(modeAlways, modeLimit))
        acMode.setAdapter(modeAdapter)
        acMode.setText(if (initialHardBlock) modeAlways else modeLimit, false)

        etLimit.inputType = InputType.TYPE_CLASS_NUMBER
        etLimit.setText(if (initialLimit > 0) initialLimit.toString() else "")

        fun applyMode() {
            val hard = acMode.text?.toString() == modeAlways
            tilLimit.visibility = if (hard) View.GONE else View.VISIBLE
            if (hard) etLimit.setText("")
        }
        applyMode()

        acMode.setOnItemClickListener { _, _, _, _ ->
            applyMode()
        }

        val dlg = at.saltyy.switchly.ui.dialog.Dialogs.builder(this)
            .setTitle(title)
            .setView(v)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dlg.setOnShowListener {
            dlg.styleSwitchlyDialogButtons()

            // Dialog list/inputs can be rebound after show; do a best-effort second pass.
            if (CustomAccentApplier.isCustomAccentEnabled(this)) {
                runCatching { CustomAccentApplier.applyToDialog(dlg) }
            }

            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (websiteEditingLocked()) {
                    dlg.dismiss()
                    refreshList()
                    return@setOnClickListener
                }
                val domainRaw = etDomain.text?.toString()?.trim().orEmpty()

                if (domainRaw.isBlank()) {
                    tilDomain.error = getString(R.string.domain_required)
                    return@setOnClickListener
                }

                val normalized = DomainBlockStore.normalize(domainRaw)
                if (normalized.isNullOrBlank()) {
                    tilDomain.error = getString(R.string.domain_required)
                    return@setOnClickListener
                }
                tilDomain.error = null

                val hardBlock = acMode.text?.toString() == modeAlways
                val limitMin = etLimit.text?.toString()?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0

                if (!hardBlock && limitMin <= 0) {
                    tilLimit.error = getString(R.string.domain_limit_required)
                    return@setOnClickListener
                } else {
                    tilLimit.error = null
                }

                if (isAllowMode()) {
                    DomainBlockStore.addDomain(this, normalized)
                    DomainBlockStore.setDomainEnabled(this, normalized, true)
                    if (hardBlock) {
                        DomainLimitStore.clear(this, normalized)
                    } else {
                        DomainLimitStore.setLimitMinutes(this, normalized, limitMin)
                    }
                } else if (hardBlock) {
                    DomainLimitStore.clear(this, normalized)
                    DomainBlockStore.addDomain(this, normalized)
                    DomainBlockStore.setDomainEnabled(this, normalized, true)
                } else {
                    DomainBlockStore.removeDomain(this, normalized)
                    DomainLimitStore.setLimitMinutes(this, normalized, limitMin)
                    DomainBlockStore.setDomainEnabled(this, normalized, true)
                }

                refreshList()
                dlg.dismiss()
            }
        }

        dlg.show()
    }

    private data class DomainRule(
        val domain: String,
        val isHardBlocked: Boolean,
        val limitMin: Int,
        val enabled: Boolean
    )

    private inner class DomainRuleAdapter(
        private val onEdit: (String) -> Unit,
        private val onToggleEnabled: (String, Boolean) -> Unit,
        private val onToggleSelection: (String) -> Unit,
        private val isSelectionMode: () -> Boolean,
        private val isSelected: (String) -> Boolean,
    ) : RecyclerView.Adapter<DomainRuleAdapter.VH>() {

        private val items = mutableListOf<DomainRule>()

        fun submit(newItems: List<DomainRule>) {
            val oldSize = items.size
            items.clear()
            items.addAll(newItems)
            if (oldSize == 0 && newItems.isNotEmpty()) {
                notifyItemRangeInserted(0, newItems.size)
            } else if (newItems.isEmpty() && oldSize > 0) {
                notifyItemRangeRemoved(0, oldSize)
            } else {
                notifyItemRangeChanged(0, minOf(oldSize, newItems.size))
                if (newItems.size > oldSize) notifyItemRangeInserted(oldSize, newItems.size - oldSize)
                if (oldSize > newItems.size) notifyItemRangeRemoved(newItems.size, oldSize - newItems.size)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_blocked_domain, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        fun itemAt(position: Int): DomainRule? = items.getOrNull(position)

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvDomain: TextView = itemView.findViewById(R.id.tvDomain)
            private val tvMeta: TextView = itemView.findViewById(R.id.tvMeta)
            private val cbRuleEnabled: MaterialCheckBox = itemView.findViewById(R.id.cbRuleEnabled)
            private val cbSelect: MaterialCheckBox = itemView.findViewById(R.id.cbSelect)
            private val btnLimit: ImageButton = itemView.findViewById(R.id.btnLimit)

            fun bind(rule: DomainRule) {
                val readOnly = websiteEditingLocked()
                tvDomain.text = rule.domain

                val baseMeta = when {
                    rule.limitMin > 0 -> getString(R.string.daily_limit_value_format, rule.limitMin)
                    rule.isHardBlocked && isAllowMode() -> getString(R.string.rule_allowed)
                    rule.isHardBlocked -> getString(R.string.rule_blocked)
                    else -> ""
                }
                tvMeta.text = listOfNotNull(
                    if (rule.enabled) null else getString(R.string.website_rule_disabled),
                    baseMeta.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                val contentAlpha = if (rule.enabled) 1f else 0.52f
                tvDomain.alpha = contentAlpha
                tvMeta.alpha = if (rule.enabled) 0.70f else 0.56f

                val selecting = isSelectionMode()
                cbRuleEnabled.visibility = if (selecting) View.GONE else View.VISIBLE
                cbSelect.visibility = if (selecting) View.VISIBLE else View.GONE
                btnLimit.visibility = if (selecting) View.GONE else View.VISIBLE

                cbRuleEnabled.buttonTintList = AccentColor.getActiveColor(this@ManageBlockedWebsitesActivity)
                cbRuleEnabled.setOnCheckedChangeListener(null)
                cbRuleEnabled.isChecked = rule.enabled
                cbRuleEnabled.isEnabled = !readOnly
                cbRuleEnabled.alpha = if (readOnly) 0.45f else 1f
                cbRuleEnabled.setOnClickListener {
                    if (websiteEditingLocked()) {
                        cbRuleEnabled.isChecked = rule.enabled
                        cbRuleEnabled.isEnabled = false
                        cbRuleEnabled.alpha = 0.45f
                        return@setOnClickListener
                    }
                    onToggleEnabled(rule.domain, cbRuleEnabled.isChecked)
                }

                cbSelect.buttonTintList = AccentColor.getActiveColor(this@ManageBlockedWebsitesActivity)
                cbSelect.isChecked = isSelected(rule.domain)
                cbSelect.isEnabled = !readOnly
                cbSelect.setOnClickListener {
                    if (!websiteEditingLocked()) {
                        onToggleSelection(rule.domain)
                    }
                }

                btnLimit.imageTintList = ColorStateList.valueOf(AccentColor.getAccentColorInt(this@ManageBlockedWebsitesActivity))
                btnLimit.isEnabled = !readOnly
                btnLimit.alpha = if (readOnly) 0.45f else 1f
                btnLimit.setOnClickListener {
                    if (websiteEditingLocked()) {
                        return@setOnClickListener
                    }
                    onEdit(rule.domain)
                }

                itemView.setOnLongClickListener {
                    if (websiteEditingLocked()) {
                        return@setOnLongClickListener true
                    }
                    if (!isSelectionMode()) {
                        enterSelectionMode(rule.domain)
                        true
                    } else {
                        false
                    }
                }

                itemView.setOnClickListener {
                    if (websiteEditingLocked()) return@setOnClickListener
                    if (isSelectionMode()) onToggleSelection(rule.domain) else onEdit(rule.domain)
                }
            }
        }
    }
}

private fun MenuItem.alphaCompat(alpha: Float) {
    // Some OEMs ignore alpha on menu icons; best-effort.
    icon?.alpha = (alpha * 255).toInt().coerceIn(0, 255)
}
