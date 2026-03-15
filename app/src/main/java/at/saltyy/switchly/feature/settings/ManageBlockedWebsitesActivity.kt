package at.saltyy.switchly.feature.settings

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.widget.AutoCompleteTextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.data.prefs.DomainBlockStore
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.data.prefs.DomainLimitStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputLayout

class ManageBlockedWebsitesActivity : AppCompatActivity() {

    private fun websiteEditingLocked(): Boolean {
        val locked = SwitchModeStore.isEnabled(this)
        if (locked) {
            Toast.makeText(this, R.string.toast_disable_switchly_to_edit_websites, Toast.LENGTH_SHORT).show()
        }
        return locked
    }

    private fun syncEditingLockUi() {
        val locked = SwitchModeStore.isEnabled(this)
        findViewById<FloatingActionButton>(R.id.fabAdd)?.isEnabled = !locked && !isSelectionMode
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.swWebsitesEnabled)?.isEnabled = !locked
    }

    private lateinit var rv: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: DomainRuleAdapter
    private lateinit var toolbar: MaterialToolbar

    private var isSelectionMode: Boolean = false
    private val selectedDomains = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage_blocked_websites)

        val swWebsitesEnabled = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.swWebsitesEnabled)
        swWebsitesEnabled?.let { sw ->
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            val key = BlockingToggleKeys.KEY_BLOCK_WEBSITES
            val initial = prefs.getBoolean(key, DomainBlockStore.isEnabled(this))
            sw.isChecked = initial
            // Keep both stores in sync (toggle + domain subsystem flag).
            DomainBlockStore.setEnabled(this, initial)
            sw.setOnCheckedChangeListener { _, isChecked ->
                if (SwitchModeStore.isEnabled(this)) {
                    sw.isChecked = !isChecked
                    Toast.makeText(this, R.string.toast_disable_switchly_to_edit_websites, Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                prefs.edit { putBoolean(key, isChecked) }
                DomainBlockStore.setEnabled(this, isChecked)
            }
        }

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        rv = findViewById(R.id.list)
        empty = findViewById(R.id.tvEmpty)

        adapter = DomainRuleAdapter(
            onEdit = { showEditDialog(it) },
            onRemove = { removeRule(it) },
            onToggleSelection = { toggleSelection(it) },
            isSelectionMode = { isSelectionMode },
            isSelected = { selectedDomains.contains(it) }
        )

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            if (websiteEditingLocked()) return@setOnClickListener
            showAddDialog()
        }

        updateMenuState()
    }

    override fun onResume() {
        super.onResume()
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
                limitMin = DomainLimitStore.getLimitMinutes(this, d)
            )
        }

        adapter.submit(rules)
        empty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE

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
        val hasItems = adapter.itemCount > 0
        menu.findItem(R.id.action_select)?.isVisible = !isSelectionMode && hasItems
        menu.findItem(R.id.action_cancel_selection)?.isVisible = isSelectionMode
        menu.findItem(R.id.action_delete_selected)?.isVisible = isSelectionMode

        val deleteItem = menu.findItem(R.id.action_delete_selected)
        deleteItem?.isEnabled = selectedDomains.isNotEmpty()
        deleteItem?.alphaCompat(if (selectedDomains.isNotEmpty()) 1f else 0.4f)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
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
        syncEditingLockUi()
    }

    private fun enterSelectionMode(preselect: String? = null) {
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
        if (!isSelectionMode) return
        if (selectedDomains.contains(domain)) selectedDomains.remove(domain) else selectedDomains.add(domain)
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        updateMenuState()
    }

    private fun confirmDeleteSelected() {
        if (websiteEditingLocked()) return
        if (selectedDomains.isEmpty()) return
        val count = selectedDomains.size
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete))
            .setMessage(resources.getQuantityString(R.plurals.delete_websites_confirm, count, count))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete, null)
            .create()

        dlg.setOnShowListener {
            dlg.styleSwitchlyDialogButtons()
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                selectedDomains.toList().forEach { removeRule(it) }
                exitSelectionMode()
                dlg.dismiss()
            }
        }

        dlg.show()
    }

    private fun removeRule(domain: String) {
        if (websiteEditingLocked()) return
        DomainBlockStore.removeDomain(this, domain)
        DomainLimitStore.clear(this, domain)
        refreshList()
    }

    private fun showAddDialog() {
        if (websiteEditingLocked()) return
        showRuleDialog(
            title = getString(R.string.add_website_rule_title),
            initialDomain = "",
            initialHardBlock = false,
            initialLimit = 0,
            allowDomainEdit = true
        )
    }

    private fun showEditDialog(domain: String) {
        if (websiteEditingLocked()) return
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
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_domain_rule, null)

        // In CUSTOM accent mode, ensure TextInput cursor/selection and dropdown indicators don't fall back to green.
        if (CustomAccentApplier.isCustomAccentEnabled(this)) {
            runCatching { CustomAccentApplier.applyToView(v, this) }
        }

        val etDomain = v.findViewById<EditText>(R.id.etDomain)
        val acMode = v.findViewById<AutoCompleteTextView>(R.id.acMode)
        val tilLimit = v.findViewById<TextInputLayout>(R.id.tilDailyLimit)
        val etLimit = v.findViewById<EditText>(R.id.etDailyLimit)

        etDomain.setText(initialDomain)
        etDomain.isEnabled = allowDomainEdit

        val modeAlways = getString(R.string.rule_block_always)
        val modeLimit = getString(R.string.rule_daily_limit)

        val modeAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf(modeAlways, modeLimit))
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

        val dlg = AlertDialog.Builder(this)
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
                val domainRaw = etDomain.text?.toString()?.trim().orEmpty()

                if (domainRaw.isBlank()) {
                    etDomain.error = getString(R.string.domain_required)
                    return@setOnClickListener
                }

                val normalized = DomainBlockStore.normalize(domainRaw)
                if (normalized.isNullOrBlank()) {
                    etDomain.error = getString(R.string.domain_required)
                    return@setOnClickListener
                }

                val hardBlock = acMode.text?.toString() == modeAlways
                val limitMin = etLimit.text?.toString()?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0

                if (!hardBlock && limitMin <= 0) {
                    tilLimit.error = getString(R.string.minutes_hint)
                    return@setOnClickListener
                } else {
                    tilLimit.error = null
                }

                if (hardBlock) {
                    DomainLimitStore.clear(this, normalized)
                    DomainBlockStore.addDomain(this, normalized)
                } else {
                    DomainBlockStore.removeDomain(this, normalized)
                    DomainLimitStore.setLimitMinutes(this, normalized, limitMin)
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
        val limitMin: Int
    )

    private inner class DomainRuleAdapter(
        private val onEdit: (String) -> Unit,
        private val onRemove: (String) -> Unit,
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

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvDomain: TextView = itemView.findViewById(R.id.tvDomain)
            private val tvMeta: TextView = itemView.findViewById(R.id.tvMeta)
            private val btnEdit: View = itemView.findViewById(R.id.btnEdit)
            private val cbSelect: MaterialCheckBox = itemView.findViewById(R.id.cbSelect)

            fun bind(rule: DomainRule) {
                tvDomain.text = rule.domain

                tvMeta.text = when {
                    rule.isHardBlocked -> getString(R.string.rule_blocked)
                    rule.limitMin > 0 -> getString(R.string.daily_limit_value_format, rule.limitMin)
                    else -> ""
                }

                val selecting = isSelectionMode()
                cbSelect.visibility = if (selecting) View.VISIBLE else View.GONE
                btnEdit.visibility = if (selecting) View.GONE else View.VISIBLE

                cbSelect.isChecked = isSelected(rule.domain)
                cbSelect.setOnClickListener {
                    onToggleSelection(rule.domain)
                }

                itemView.setOnLongClickListener {
                    if (!isSelectionMode()) {
                        if (websiteEditingLocked()) return@setOnLongClickListener true
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
                btnEdit.setOnClickListener {
                    if (websiteEditingLocked()) return@setOnClickListener
                    onEdit(rule.domain)
                }
            }
        }
    }
}

private fun MenuItem.alphaCompat(alpha: Float) {
    // Some OEMs ignore alpha on menu icons; best-effort.
    icon?.alpha = (alpha * 255).toInt().coerceIn(0, 255)
}