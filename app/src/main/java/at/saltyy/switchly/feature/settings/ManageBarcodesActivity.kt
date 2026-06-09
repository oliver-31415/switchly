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

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.ScanCodeStore
import at.saltyy.switchly.feature.barcode.BarcodeScanActivity
import at.saltyy.switchly.nfc.NfcSchema
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.util.EditingLockGuard
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import java.text.NumberFormat
import java.util.Locale

class ManageBarcodesActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: CodeAdapter
    private var fabAdd: FloatingActionButton? = null
    private val selectedRawValues: MutableSet<String> = linkedSetOf()
    private var selectionMode: Boolean = false

    private data class ActionSpec(
        val id: String,
        val labelRes: Int,
        val supportsMinutes: Boolean,
    )

    private val actions by lazy {
        listOf(
            ActionSpec("enable", R.string.qr_action_enable, false),
            ActionSpec("disable", R.string.qr_action_disable, false),
            ActionSpec("toggle", R.string.qr_action_toggle, false),
            ActionSpec("temp_disable", R.string.qr_action_temp_disable, true),
            ActionSpec("temp_enable", R.string.qr_action_temp_enable, true),
        )
    }

    private fun defaultAction(): ActionSpec =
        actions.firstOrNull { it.id == "toggle" } ?: actions.first()

    private data class ActionForm(
        val rawValue: String,
        val name: String?,
        val note: String?,
        val dailyLimit: Int?,
        val cooldownMinutes: Int?,
        val action: ActionSpec,
        val profile: String?,
        val minutes: Long?,
    )

    private val pickCodeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val rawValue = data.getStringExtra(BarcodeScanActivity.EXTRA_PICKED_RAW).orEmpty()
        if (rawValue.isBlank()) return@registerForActivityResult
        showEditDialog(existing = null, prefilledRaw = rawValue)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        if (EditingLockGuard.isLocked(this) && !AutomationModeStore.isBarcodeSetupMissing(this)) {
            EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_barcodes)
            finish()
            return
        }

        if (!AutomationModeStore.shouldShowBarcodeTools(this)) {
            Toast.makeText(this, R.string.toast_manage_barcodes_requires_enabled, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContentView(R.layout.activity_manage_barcodes)
        CustomAccentApplier.applyIfNeeded(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            if (selectionMode) exitSelectionMode() else finish()
        }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        recycler = findViewById(R.id.recycler)
        empty = findViewById(R.id.tvEmpty)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = CodeAdapter(
            isSelectionMode = { selectionMode },
            isSelected = { selectedRawValues.contains(it.rawValue) },
            onRowClick = { entry ->
                if (selectionMode) toggleSelection(entry.rawValue) else showEditDialog(entry)
            },
            onRowLongPress = { entry ->
                if (!selectionMode) {
                    selectionMode = true
                    selectedRawValues.clear()
                    selectedRawValues.add(entry.rawValue)
                    invalidateOptionsMenu()
                    notifySelectionUiChanged()
                    true
                } else {
                    false
                }
            }
        )
        recycler.adapter = adapter

        fabAdd = findViewById<FloatingActionButton>(R.id.fabAdd)
        fabAdd?.setOnClickListener {
            if (!selectionMode) showAddChoiceDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_manage_barcodes, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        fabAdd?.isVisible = !selectionMode
        menu.findItem(R.id.action_cancel_select)?.isVisible = selectionMode
        menu.findItem(R.id.action_select)?.isVisible = !selectionMode && adapter.itemCount > 0
        menu.findItem(R.id.action_delete_selected)?.isVisible = selectionMode
        menu.findItem(R.id.action_delete_selected)?.isEnabled = selectedRawValues.isNotEmpty()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                if (selectionMode) {
                    exitSelectionMode()
                    true
                } else {
                    finish()
                    true
                }
            }
            R.id.action_cancel_select -> {
                exitSelectionMode()
                true
            }
            R.id.action_select -> {
                selectionMode = true
                selectedRawValues.clear()
                invalidateOptionsMenu()
                notifySelectionUiChanged()
                true
            }
            R.id.action_delete_selected -> {
                confirmDeleteSelected()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refresh() {
        val entries = ScanCodeStore.getEntries(this).filter { it.kind == ScanCodeStore.Kind.BARCODE }
        val validRaws = entries.map { it.rawValue }.toSet()
        selectedRawValues.retainAll(validRaws)
        if (selectionMode && selectedRawValues.isEmpty()) {
            selectionMode = false
        }
        adapter.submit(entries)
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        invalidateOptionsMenu()
    }

    private fun notifySelectionUiChanged() {
        if (::adapter.isInitialized) adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    private fun toggleSelection(rawValue: String) {
        if (!selectedRawValues.add(rawValue)) {
            selectedRawValues.remove(rawValue)
        }
        if (selectedRawValues.isEmpty()) {
            selectionMode = false
        }
        invalidateOptionsMenu()
        notifySelectionUiChanged()
    }

    private fun exitSelectionMode() {
        selectedRawValues.clear()
        selectionMode = false
        invalidateOptionsMenu()
        notifySelectionUiChanged()
    }

    private fun confirmDeleteSelected() {
        if (selectedRawValues.isEmpty()) {
            exitSelectionMode()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manage_barcodes_delete_selected_title)
            .setMessage(
                resources.getQuantityString(
                    R.plurals.manage_barcodes_delete_selected_confirm_fmt,
                    selectedRawValues.size,
                    selectedRawValues.size,
                )
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                selectedRawValues.toList().forEach { rawValue ->
                    ScanCodeStore.remove(this, ScanCodeStore.Kind.BARCODE, rawValue)
                }
                exitSelectionMode()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .showAccented()
    }

    private fun showAddChoiceDialog() {
        val items = listOf(
            getString(R.string.manage_barcodes_add_scan_barcode),
            getString(R.string.manage_barcodes_add_manual),
        )
        showSimpleChoiceDialog(
            title = getString(R.string.manage_barcodes_add_title),
            entries = items,
        ) { which, dialog ->
            when (which) {
                0 -> launchPicker()
                else -> showEditDialog(existing = null)
            }
            dialog.dismiss()
        }
    }

    private fun showSimpleChoiceDialog(
        title: String,
        entries: List<String>,
        onSelected: (index: Int, dialog: AlertDialog) -> Unit,
    ) {
        val content = layoutInflater.inflate(R.layout.dialog_single_select_list, null)
        val rv = content.findViewById<RecyclerView>(R.id.recycler)
        rv.layoutManager = LinearLayoutManager(this)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .create()

        rv.adapter = SimpleChoiceAdapter(entries) { which ->
            onSelected(which, dialog)
        }

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private fun launchPicker() {
        val intent = Intent(this, BarcodeScanActivity::class.java)
            .putExtra(BarcodeScanActivity.EXTRA_PICK_MODE, true)
        pickCodeLauncher.launch(intent)
    }

    private fun showEditDialog(
        existing: ScanCodeStore.Entry?,
        prefilledRaw: String? = null,
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_barcode_edit, null)
        val etRaw = view.findViewById<TextInputEditText>(R.id.etRawValue)
        val etName = view.findViewById<TextInputEditText>(R.id.etCodeName)
        val etNote = view.findViewById<TextInputEditText>(R.id.etCodeNote)
        val etDailyLimit = view.findViewById<TextInputEditText>(R.id.etDailyLimit)
        val etCooldown = view.findViewById<TextInputEditText>(R.id.etCooldownMinutes)
        val acProfile = view.findViewById<MaterialAutoCompleteTextView>(R.id.acProfile)
        val acAction = view.findViewById<MaterialAutoCompleteTextView>(R.id.acAction)
        val acMinutes = view.findViewById<MaterialAutoCompleteTextView>(R.id.acMinutes)
        val tilMinutes = view.findViewById<View>(R.id.tilMinutes)

        val actionLabels = actions.map { getString(it.labelRes) }
        acAction.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, actionLabels))

        val profiles = listOf(getString(R.string.manage_barcodes_profile_universal)) +
            ProfileStore.getProfiles(this).toList().sorted()
        acProfile.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, profiles))

        val minutePresets = listOf("5", "10", "30", "60", "120")
        acMinutes.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, minutePresets))

        val initial = parseExisting(existing)
        etRaw.setText(prefilledRaw ?: existing?.rawValue.orEmpty())
        etName.setText(existing?.name.orEmpty())
        etNote.setText(existing?.note.orEmpty())
        etDailyLimit.setText(formatIntOrEmpty(existing?.dailyLimit))
        etCooldown.setText(formatIntOrEmpty(existing?.cooldownMinutes))

        val initialAction = initial?.action ?: defaultAction()
        acProfile.setText(initial?.profile ?: getString(R.string.manage_barcodes_profile_universal), false)
        acAction.setText(getString(initialAction.labelRes), false)
        acMinutes.setText(formatLong(initial?.minutes ?: 10L), false)

        fun updateVisibility() {
            val action = actions.firstOrNull {
                getString(it.labelRes) == acAction.text?.toString().orEmpty()
            } ?: defaultAction()
            tilMinutes.visibility = if (action.supportsMinutes) View.VISIBLE else View.GONE
        }
        updateVisibility()
        acAction.setOnItemClickListener { _, _, _, _ -> updateVisibility() }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.manage_barcodes_add_title else R.string.manage_barcodes_edit_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ -> }
            .showAccented()
            .also { dialog ->
                val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                positive.setOnClickListener {
                    val form = readForm(
                        rawValue = etRaw.text?.toString().orEmpty(),
                        name = etName.text?.toString(),
                        note = etNote.text?.toString(),
                        dailyLimitText = etDailyLimit.text?.toString(),
                        cooldownText = etCooldown.text?.toString(),
                        actionLabel = acAction.text?.toString().orEmpty(),
                        profile = acProfile.text?.toString(),
                        minutesText = acMinutes.text?.toString(),
                    )
                    if (form == null) {
                        Toast.makeText(this, R.string.invalid_value, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    if (existing != null && existing.rawValue != form.rawValue) {
                        ScanCodeStore.remove(this, ScanCodeStore.Kind.BARCODE, existing.rawValue)
                    }
                    ScanCodeStore.upsert(
                        this,
                        ScanCodeStore.Entry(
                            kind = ScanCodeStore.Kind.BARCODE,
                            rawValue = form.rawValue,
                            actionUri = buildActionUri(form),
                            name = form.name,
                            note = form.note,
                            dailyLimit = form.dailyLimit,
                            cooldownMinutes = form.cooldownMinutes,
                            addedAtMillis = existing?.addedAtMillis ?: System.currentTimeMillis(),
                        )
                    )
                    refresh()
                    dialog.dismiss()
                }
            }
    }

    private fun readForm(
        rawValue: String,
        name: String?,
        note: String?,
        dailyLimitText: String?,
        cooldownText: String?,
        actionLabel: String,
        profile: String?,
        minutesText: String?,
    ): ActionForm? {
        val cleanRaw = rawValue.trim()
        val action = actions.firstOrNull { getString(it.labelRes) == actionLabel } ?: return null
        if (cleanRaw.isBlank()) return null
        val minutes = minutesText?.trim()?.takeIf { it.isNotBlank() }?.toLongOrNull()
        if (action.supportsMinutes && (minutes == null || minutes <= 0L)) return null
        val universal = getString(R.string.manage_barcodes_profile_universal)
        val cleanProfile = profile?.trim()?.takeIf { it.isNotBlank() && !it.equals(universal, ignoreCase = true) }
        val dailyLimit = dailyLimitText?.trim()?.takeIf { it.isNotBlank() }?.toIntOrNull()
        val cooldownMinutes = cooldownText?.trim()?.takeIf { it.isNotBlank() }?.toIntOrNull()
        if (dailyLimitText?.trim()?.isNotEmpty() == true && (dailyLimit == null || dailyLimit <= 0)) return null
        if (cooldownText?.trim()?.isNotEmpty() == true && (cooldownMinutes == null || cooldownMinutes <= 0)) return null
        return ActionForm(
            rawValue = cleanRaw,
            name = name?.trim()?.takeIf { it.isNotBlank() },
            note = note?.trim()?.takeIf { it.isNotBlank() },
            dailyLimit = dailyLimit,
            cooldownMinutes = cooldownMinutes,
            action = action,
            profile = cleanProfile,
            minutes = minutes,
        )
    }

    private fun buildActionUri(form: ActionForm): String {
        val finalAction = if (form.action.supportsMinutes) {
            form.action.id + (form.minutes ?: 10L).coerceIn(1L, 120L)
        } else {
            form.action.id
        }
        return if (form.profile.isNullOrBlank()) {
            NfcSchema.uriForGlobalAction(finalAction)
        } else {
            NfcSchema.uriForProfileAction(form.profile, finalAction)
        }
    }

    private data class ParsedExisting(
        val action: ActionSpec,
        val profile: String?,
        val minutes: Long?,
    )

    private fun formatActionSummary(entry: ScanCodeStore.Entry): String {
        val parsed = parseExisting(entry) ?: return entry.actionUri
        val label = getString(parsed.action.labelRes)
        val profileLabel = parsed.profile ?: getString(R.string.manage_barcodes_profile_universal)
        val actionLabel = if (parsed.action.supportsMinutes) {
            label + " (" + ((parsed.minutes ?: 10L).coerceAtLeast(1L)) + " " +
                getString(R.string.qr_minutes).lowercase(Locale.getDefault()) + ")"
        } else {
            label
        }
        return getString(R.string.manage_barcodes_action_summary_fmt, profileLabel, actionLabel)
    }

    private fun formatIntOrEmpty(value: Int?): String =
        value?.let { NumberFormat.getIntegerInstance(Locale.getDefault()).format(it) }.orEmpty()

    private fun formatLong(value: Long): String =
        NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

    private fun buildMetaSummary(entry: ScanCodeStore.Entry): String {
        val parts = mutableListOf<String>()
        entry.note?.takeIf { it.isNotBlank() }?.let { parts += it }
        entry.dailyLimit?.let { parts += getString(R.string.manage_barcodes_limit_daily_summary, it) }
        entry.cooldownMinutes?.let { parts += getString(R.string.manage_barcodes_limit_cooldown_summary, it) }
        return parts.joinToString(" · ")
    }

    private fun parseExisting(entry: ScanCodeStore.Entry?): ParsedExisting? {
        entry ?: return null
        val uri = runCatching { entry.actionUri.toUri() }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val segs = uri.pathSegments ?: emptyList()
        return when (host) {
            NfcSchema.HOST_SWITCH -> {
                val actionStr = segs.getOrNull(0)?.lowercase(Locale.ROOT).orEmpty()
                when {
                    actionStr.startsWith("temp_disable") -> ParsedExisting(actions.first { it.id == "temp_disable" }, null, actionStr.removePrefix("temp_disable").toLongOrNull())
                    actionStr.startsWith("temp_enable") -> ParsedExisting(actions.first { it.id == "temp_enable" }, null, actionStr.removePrefix("temp_enable").toLongOrNull())
                    else -> actions.firstOrNull { it.id == actionStr }?.let { ParsedExisting(it, null, null) }
                }
            }

            NfcSchema.HOST_PROFILE -> {
                val profile = segs.getOrNull(0)
                val actionStr = segs.getOrNull(1)?.lowercase(Locale.ROOT).orEmpty()
                when {
                    actionStr.startsWith("temp_disable") -> ParsedExisting(actions.first { it.id == "temp_disable" }, profile, actionStr.removePrefix("temp_disable").toLongOrNull())
                    actionStr.startsWith("temp_enable") -> ParsedExisting(actions.first { it.id == "temp_enable" }, profile, actionStr.removePrefix("temp_enable").toLongOrNull())
                    else -> actions.firstOrNull { it.id == actionStr }?.let { ParsedExisting(it, profile, null) }
                }
            }

            else -> null
        }
    }

    private class SimpleChoiceAdapter(
        private val entries: List<String>,
        private val onSelected: (Int) -> Unit,
    ) : RecyclerView.Adapter<SimpleChoiceAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val text = view.findViewById<TextView>(android.R.id.text1)

            fun bind(label: String) {
                text.text = label
                itemView.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onSelected(pos)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(entries[position])
        }

        override fun getItemCount(): Int = entries.size
    }

    private inner class CodeAdapter(
        private val isSelectionMode: () -> Boolean,
        private val isSelected: (ScanCodeStore.Entry) -> Boolean,
        private val onRowClick: (ScanCodeStore.Entry) -> Unit,
        private val onRowLongPress: (ScanCodeStore.Entry) -> Boolean,
    ) : RecyclerView.Adapter<CodeAdapter.VH>() {
        private val items = mutableListOf<ScanCodeStore.Entry>()

        fun submit(list: List<ScanCodeStore.Entry>) {
            val previousItems = items.toList()
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = previousItems.size

                override fun getNewListSize(): Int = list.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    previousItems[oldItemPosition].rawValue == list[newItemPosition].rawValue

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    previousItems[oldItemPosition] == list[newItemPosition]
            })
            items.clear()
            items.addAll(list)
            diff.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_barcode, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val ivIcon = view.findViewById<ImageView>(R.id.ivIcon)
            private val tvName = view.findViewById<TextView>(R.id.tvName)
            private val tvRaw = view.findViewById<TextView>(R.id.tvRaw)
            private val tvAction = view.findViewById<TextView>(R.id.tvAction)
            private val tvMeta = view.findViewById<TextView>(R.id.tvMeta)
            private val cbSelect = view.findViewById<MaterialCheckBox>(R.id.cbSelect)

            fun bind(item: ScanCodeStore.Entry) {
                ivIcon.setImageResource(R.drawable.barcode_24)
                tvName.text = item.name ?: getString(R.string.manage_barcodes_kind_barcode)
                tvRaw.text = item.rawValue
                tvAction.text = formatActionSummary(item)
                val meta = buildMetaSummary(item)
                tvMeta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE
                tvMeta.text = meta
                cbSelect.isVisible = isSelectionMode()
                cbSelect.isChecked = isSelected(item)
                itemView.setOnClickListener { onRowClick(item) }
                itemView.setOnLongClickListener { onRowLongPress(item) }
            }
        }
    }
}
