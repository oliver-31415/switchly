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
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.NfcTempDisableLimiterStore
import at.saltyy.switchly.data.prefs.NfcUidPairingStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.nfc.NfcWriteWaitingActivity
import at.saltyy.switchly.nfc.NfcWriterActivity
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.SwitchlyDropdownAdapter
import at.saltyy.switchly.ui.attachEditDeleteSwipe
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.updateSelectionSubtitle
import at.saltyy.switchly.ui.dialog.Dialogs
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.ui.dialog.showDestructiveAccented
import at.saltyy.switchly.ui.dialog.SwitchlyDialogOption
import at.saltyy.switchly.ui.dialog.showSwitchlyOptionDialog
import at.saltyy.switchly.ui.dialog.showSwitchlyFormDialog
import at.saltyy.switchly.util.EditingLockGuard
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import java.util.Locale

class ManagePairedTagsActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var empty: View
    private lateinit var toolbar: MaterialToolbar
    private val selectedUids: MutableSet<String> = linkedSetOf()
    private var selectionMode: Boolean = false
    private var lastTags: List<NfcUidPairingStore.TagMeta> = emptyList()

    private enum class SortMode(val id: Int) {
        NEW_OLD(0),
        OLD_NEW(1),
        NAME_AZ(2),
        NAME_ZA(3);

        companion object {
            fun fromId(id: Int): SortMode = entries.firstOrNull { it.id == id } ?: NAME_AZ
        }
    }

    private fun getSortMode(): SortMode {
        val sp = getSharedPreferences("switchly_prefs", MODE_PRIVATE)
        val value = sp.all["paired_tags_sort_mode"]
        val id = when (value) {
            is Int -> value
            is Long -> value.toInt()
            is String -> value.toIntOrNull() ?: SortMode.NAME_AZ.id
            else -> SortMode.NAME_AZ.id
        }
        return SortMode.fromId(id)
    }

    private lateinit var adapter: TagAdapter
    private var fabAdd: FloatingActionButton? = null

    private val pairWritableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val resultStr = data?.getStringExtra(NfcWriteWaitingActivity.EXTRA_RESULT)
            val uid = data?.getStringExtra(NfcWriteWaitingActivity.EXTRA_UID)
            val alreadyPaired = data?.getBooleanExtra(NfcWriteWaitingActivity.EXTRA_ALREADY_PAIRED, false) == true

            when {
                result.resultCode == RESULT_OK && resultStr == NfcWriteWaitingActivity.RESULT_OK_STR -> {
                    refresh()
                    if (alreadyPaired) {
                        Toast.makeText(this, R.string.nfc_pair_already_added_open_writer, Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this, NfcWriterActivity::class.java))
                    } else {
                        Toast.makeText(
                            this,
                            if (uid.isNullOrBlank()) getString(R.string.nfc_pair_ok) else getString(R.string.nfc_pair_ok_with_uid, uid),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                resultStr == NfcWriteWaitingActivity.RESULT_NOT_WRITABLE_STR -> {
                    Toast.makeText(this, R.string.nfc_pair_writable_requires_writable_tag, Toast.LENGTH_SHORT).show()
                }
                resultStr != null -> {
                    Toast.makeText(this, R.string.nfc_pair_error, Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val pairReadOnlyLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val resultStr = data?.getStringExtra(NfcWriteWaitingActivity.EXTRA_RESULT)
            val uid = data?.getStringExtra(NfcWriteWaitingActivity.EXTRA_UID)
            val alreadyPaired = data?.getBooleanExtra(NfcWriteWaitingActivity.EXTRA_ALREADY_PAIRED, false) == true

            when {
                result.resultCode == RESULT_OK && resultStr == NfcWriteWaitingActivity.RESULT_OK_STR -> {
                    refresh()
                    Toast.makeText(
                        this,
                        when {
                            alreadyPaired -> getString(R.string.nfc_pair_already_added)
                            uid.isNullOrBlank() -> getString(R.string.nfc_pair_ok)
                            else -> getString(R.string.nfc_pair_ok_with_uid, uid)
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                }
                resultStr != null -> {
                    Toast.makeText(this, R.string.nfc_pair_error, Toast.LENGTH_SHORT).show()
                }
            }
        }

    companion object {
        private const val EMPTY_NUMBER = -1
        private const val INVALID_NUMBER = -2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)

        val locked = EditingLockGuard.isLocked(this)
        AppLogStore.append(this, "NFC", "ManagePairedTagsActivity opened locked=$locked")
        if (EditingLockGuard.blockWithDialog(this, R.string.edit_locked_manage_paired_tags)) {
            AppLogStore.append(this, "NFC", "ManagePairedTagsActivity blocked by editing lock")
            return
        }

        setContentView(R.layout.activity_manage_paired_tags)
        AppLogStore.append(this, "NFC", "ManagePairedTagsActivity content shown")

        // Ensure CUSTOM accent mode recolors checkboxes/cursor in this screen + dialogs.
        CustomAccentApplier.applyIfNeeded(this)

        toolbar = findViewById(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        recycler = findViewById(R.id.recycler)
        empty = findViewById(R.id.tvEmpty)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = TagAdapter(
            isSelectionMode = { selectionMode },
            isSelected = { selectedUids.contains(it.uid) },
            onEnabledChanged = { tag, enabled ->
                NfcUidPairingStore.setTagEnabled(this, tag.uid, enabled)
                refresh()
            },
            onRowClick = { tag ->
                if (selectionMode) toggleSelection(tag.uid) else showEditDialog(tag)
            },
            onRowLongPress = { tag ->
                if (!selectionMode) {
                    selectionMode = true
                    selectedUids.clear()
                    selectedUids.add(tag.uid)
                    invalidateOptionsMenu()
                    notifySelectionUiChanged()
                    true
                } else {
                    false
                }
            }
        )
        recycler.adapter = adapter
        recycler.attachEditDeleteSwipe(
            canSwipe = { !selectionMode },
            onEdit = { position ->
                adapter.itemAt(position)?.let(::showEditDialog)
            },
            onDelete = { position ->
                adapter.itemAt(position)?.let { confirmRemove(it.uid) }
            }
        )

        fabAdd = findViewById(R.id.fabAdd)
        fabAdd?.setOnClickListener {
            if (!selectionMode) showAddDialog()
        }
        findViewById<View>(R.id.btnEmptyAddTag)?.setOnClickListener { showAddDialog() }
    }

    private fun notifySelectionUiChanged() {
        // Selection mode toggles checkboxes in row layout.
        // A full refresh keeps it simple and avoids edge cases with moves.
        if (::adapter.isInitialized) adapter.notifyItemRangeChanged(0, adapter.itemCount)
        if (::toolbar.isInitialized) {
            toolbar.updateSelectionSubtitle(selectionMode, selectedUids.size)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (!::adapter.isInitialized || !::empty.isInitialized) {
            return
        }
        val tags = NfcUidPairingStore.getPairedTags(this)
        val sorted = applySort(tags, getSortMode())
        lastTags = sorted
        selectedUids.retainAll(sorted.map { it.uid }.toSet())
        if (selectionMode && selectedUids.isEmpty()) {
            selectionMode = false
        }
        adapter.submit(sorted)
        empty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        notifySelectionUiChanged()
    }

    private fun applySort(
        list: List<NfcUidPairingStore.TagMeta>,
        mode: SortMode
    ): List<NfcUidPairingStore.TagMeta> {
        return when (mode) {
            SortMode.NEW_OLD -> list.sortedWith(
                compareByDescending<NfcUidPairingStore.TagMeta> { it.pairedAtMillis }
                    .thenBy { it.name?.lowercase() ?: "~" }
                    .thenBy { it.uid }
            )
            SortMode.OLD_NEW -> list.sortedWith(
                compareBy<NfcUidPairingStore.TagMeta> { it.pairedAtMillis }
                    .thenBy { it.name?.lowercase() ?: "~" }
                    .thenBy { it.uid }
            )
            SortMode.NAME_AZ -> list.sortedWith(
                compareBy<NfcUidPairingStore.TagMeta>({ it.name?.lowercase() ?: "~" }, { it.uid })
            )
            SortMode.NAME_ZA -> list.sortedWith(
                compareByDescending<NfcUidPairingStore.TagMeta> { it.name?.lowercase() ?: "" }
                    .thenByDescending { it.uid }
            )
        }
    }

    private fun showPairedTagsInfoDialog() {
        Dialogs.builder(this)
            .setTitle(R.string.paired_tags_info_title)
            .setMessage(R.string.paired_tags_info_body)
            .setPositiveButton(R.string.ok, null)
            .showAccented()
    }


    private fun showAddDialog() {
        val entries = listOf(
            getString(R.string.paired_tags_add_writable_title),
            getString(R.string.paired_tags_add_readonly_title)
        )

        showSimpleChoiceDialog(
            title = getString(R.string.paired_tags_add_title),
            entries = entries,
            icons = listOf(R.drawable.nfc_24, R.drawable.lock_open_24),
        ) { which, dialog ->
            when (which) {
                0 -> startPairWritableFlow()
                1 -> startPairReadOnlyFlow()
            }
            dialog.dismiss()
        }
    }

    private fun startPairWritableFlow() {
        val intent = Intent(this, NfcWriteWaitingActivity::class.java).apply {
            putExtra(NfcWriteWaitingActivity.EXTRA_MODE, NfcWriteWaitingActivity.MODE_PAIR_UID_WRITABLE)
        }
        pairWritableLauncher.launch(intent)
    }

    private fun startPairReadOnlyFlow() {
        val intent = Intent(this, NfcWriteWaitingActivity::class.java).apply {
            putExtra(NfcWriteWaitingActivity.EXTRA_MODE, NfcWriteWaitingActivity.MODE_PAIR_UID_READONLY)
        }
        pairReadOnlyLauncher.launch(intent)
    }

    private fun showSimpleChoiceDialog(
        title: String,
        entries: List<String>,
        icons: List<Int?>? = null,
        onSelected: (index: Int, dialog: AlertDialog) -> Unit,
    ) {
        lateinit var dialog: AlertDialog
        dialog = showSwitchlyOptionDialog(
            title = title,
            options = entries.mapIndexed { index, label ->
                SwitchlyDialogOption(title = label, iconRes = icons?.getOrNull(index))
            }
        ) { which ->
            onSelected(which, dialog)
        }
    }

    private fun toggleSelection(uid: String) {
        if (selectedUids.contains(uid)) selectedUids.remove(uid) else selectedUids.add(uid)
        if (selectedUids.isEmpty()) {
            selectionMode = false
        }
        invalidateOptionsMenu()
        notifySelectionUiChanged()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedUids.clear()
        invalidateOptionsMenu()
        notifySelectionUiChanged()
    }

    private fun showEditDialog(tag: NfcUidPairingStore.TagMeta) {
        val content = LayoutInflater.from(this).inflate(
            R.layout.dialog_paired_tag_edit,
            FrameLayout(this),
            false,
        )
        val etName = content.findViewById<EditText>(R.id.etTagName)
        val etNote = content.findViewById<EditText>(R.id.etTagNote)
        val etDailyLimit = content.findViewById<EditText>(R.id.etTagDailyLimit)
        val etCooldown = content.findViewById<EditText>(R.id.etTagCooldownMinutes)
        val etUid = content.findViewById<EditText>(R.id.etTagUid)
        val acProfile = content.findViewById<MaterialAutoCompleteTextView>(R.id.acTagProfile)
        val acAction = content.findViewById<MaterialAutoCompleteTextView>(R.id.acTagAction)
        val acMinutes = content.findViewById<MaterialAutoCompleteTextView>(R.id.acTagMinutes)
        val tilProfile = content.findViewById<View>(R.id.tilTagProfile)
        val tilMinutes = content.findViewById<View>(R.id.tilTagMinutes)
        val writtenActionHelper = content.findViewById<View>(R.id.tvWrittenActionHelper)
        val btnRewriteTag = content.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRewriteTag)
        val btnSave = content.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSave)
        val btnCancel = content.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)

        val uidBucket = NfcTempDisableLimiterStore.bucketForUid(tag.uid)
        val universalProfile = getString(R.string.manage_barcodes_profile_universal)
        val profiles = listOf(universalProfile) + ProfileStore.getProfiles(this).toList().sorted()
        val actionEntries = buildList {
            if (tag.tagKind == NfcUidPairingStore.TagKind.WRITABLE) {
                add(
                    NfcUidPairingStore.PairedTagAction.USE_WRITTEN to
                        getString(R.string.paired_tag_action_use_written)
                )
            }
            add(
                NfcUidPairingStore.PairedTagAction.ENABLE to
                    getString(R.string.paired_tag_action_enable)
            )
            add(
                NfcUidPairingStore.PairedTagAction.DISABLE to
                    getString(R.string.paired_tag_action_disable)
            )
            add(
                NfcUidPairingStore.PairedTagAction.TOGGLE to
                    getString(R.string.paired_tag_action_toggle)
            )
            add(
                NfcUidPairingStore.PairedTagAction.TEMP_DISABLE to
                    getString(R.string.paired_tag_action_temp_disable)
            )
            add(
                NfcUidPairingStore.PairedTagAction.TEMP_ENABLE to
                    getString(R.string.paired_tag_action_temp_enable)
            )
        }

        fun selectedAction(): NfcUidPairingStore.PairedTagAction {
            return actionEntries
                .firstOrNull { it.second == acAction.text?.toString().orEmpty() }
                ?.first
                ?: if (tag.tagKind == NfcUidPairingStore.TagKind.WRITABLE) {
                    NfcUidPairingStore.PairedTagAction.USE_WRITTEN
                } else {
                    NfcUidPairingStore.PairedTagAction.TOGGLE
                }
        }

        fun supportsMinutes(action: NfcUidPairingStore.PairedTagAction): Boolean {
            return action == NfcUidPairingStore.PairedTagAction.TEMP_DISABLE ||
                action == NfcUidPairingStore.PairedTagAction.TEMP_ENABLE
        }

        fun applyMinutePresets(action: NfcUidPairingStore.PairedTagAction) {
            val presets = mutableListOf("5", "10", "30", "60", "120")
            if (supportsMinutes(action)) {
                presets += getString(R.string.qr_minutes_ask_when_scanned)
            }
            acMinutes.setAdapter(SwitchlyDropdownAdapter(this, presets))
        }

        fun updateActionVisibility() {
            val action = selectedAction()
            val usesWrittenAction = action == NfcUidPairingStore.PairedTagAction.USE_WRITTEN
            tilProfile.isVisible = !usesWrittenAction
            tilMinutes.isVisible = !usesWrittenAction && supportsMinutes(action)
            writtenActionHelper.isVisible = usesWrittenAction
            applyMinutePresets(action)
        }

        etUid.setText(tag.uid)
        etName.setText(tag.name.orEmpty())
        etNote.setText(tag.note.orEmpty())

        acProfile.setAdapter(SwitchlyDropdownAdapter(this, profiles))
        acProfile.setText(tag.actionProfile ?: universalProfile, false)
        acProfile.setOnClickListener { acProfile.showDropDown() }

        acAction.setAdapter(SwitchlyDropdownAdapter(this, actionEntries.map { it.second }))
        val initialAction = actionEntries.firstOrNull { it.first == tag.action }
            ?: actionEntries.first()
        acAction.setText(initialAction.second, false)
        acAction.setOnClickListener { acAction.showDropDown() }
        acAction.setOnItemClickListener { _, _, _, _ -> updateActionVisibility() }

        acMinutes.setText(
            if (tag.askDurationWhenScanned) {
                getString(R.string.qr_minutes_ask_when_scanned)
            } else {
                String.format(Locale.ROOT, "%d", tag.durationMinutes)
            },
            false,
        )
        acMinutes.setOnClickListener { acMinutes.showDropDown() }
        updateActionVisibility()

        NfcTempDisableLimiterStore.getDailyLimitOverride(uidBucket, this)
            ?.let { etDailyLimit.setText(String.format(Locale.ROOT, "%d", it)) }
        NfcTempDisableLimiterStore.getCooldownOverrideMinutes(uidBucket, this)
            ?.let { etCooldown.setText(String.format(Locale.ROOT, "%d", it)) }

        val dialog = showSwitchlyFormDialog(
            title = getString(R.string.paired_tag_edit_title),
            content = content,
            cancelButton = btnCancel,
            saveButton = btnSave,
        )

        btnRewriteTag.isVisible = tag.tagKind == NfcUidPairingStore.TagKind.WRITABLE
        btnRewriteTag.setOnClickListener {
            dialog.dismiss()
            startRewriteFlow(tag)
        }

        btnSave.setOnClickListener {
            val dailyResult = parseOptionalBoundedInt(
                raw = etDailyLimit.text?.toString(),
                min = 1,
                max = 50,
            )
            if (dailyResult == INVALID_NUMBER) {
                Toast.makeText(
                    this,
                    getString(R.string.paired_tag_daily_limit_invalid),
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }

            val cooldownResult = parseOptionalBoundedInt(
                raw = etCooldown.text?.toString(),
                min = 1,
                max = 24 * 60,
            )
            if (cooldownResult == INVALID_NUMBER) {
                Toast.makeText(
                    this,
                    getString(R.string.paired_tag_cooldown_invalid),
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }

            val action = selectedAction()
            val usesWrittenAction = action == NfcUidPairingStore.PairedTagAction.USE_WRITTEN
            val temporaryAction = supportsMinutes(action)
            val minutesText = acMinutes.text?.toString()?.trim().orEmpty()
            val askWhenScanned = temporaryAction &&
                minutesText == getString(R.string.qr_minutes_ask_when_scanned)
            val durationResult = if (temporaryAction && !askWhenScanned) {
                parseOptionalBoundedInt(
                    raw = minutesText,
                    min = 1,
                    max = 1440,
                )
            } else {
                EMPTY_NUMBER
            }
            if (temporaryAction && !askWhenScanned && durationResult == INVALID_NUMBER) {
                Toast.makeText(
                    this,
                    getString(R.string.paired_tag_duration_invalid),
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }

            val selectedProfile = acProfile.text
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals(universalProfile, ignoreCase = true) }
            val duration = durationResult
                .takeIf { it != EMPTY_NUMBER && it != INVALID_NUMBER }
                ?: tag.durationMinutes

            NfcUidPairingStore.setTagMeta(
                ctx = this,
                uidHex = tag.uid,
                name = etName.text?.toString(),
                note = etNote.text?.toString(),
            )
            NfcUidPairingStore.setTagAction(
                ctx = this,
                uidHex = tag.uid,
                action = action,
                durationMinutes = if (temporaryAction && !usesWrittenAction) duration else null,
                askDurationWhenScanned = askWhenScanned && !usesWrittenAction,
                profile = if (usesWrittenAction) null else selectedProfile,
            )
            NfcTempDisableLimiterStore.setTagConfig(
                uidBucket = uidBucket,
                ctx = this,
                dailyLimit = dailyResult.takeIf { it != EMPTY_NUMBER },
                cooldownMinutes = cooldownResult.takeIf { it != EMPTY_NUMBER },
            )

            refresh()
            dialog.dismiss()
        }
    }

    private fun startRewriteFlow(tag: NfcUidPairingStore.TagMeta) {
        val action = when (tag.action) {
            NfcUidPairingStore.PairedTagAction.USE_WRITTEN -> null
            NfcUidPairingStore.PairedTagAction.TOGGLE -> "toggle"
            NfcUidPairingStore.PairedTagAction.DISABLE -> "disable"
            NfcUidPairingStore.PairedTagAction.ENABLE -> "enable"
            NfcUidPairingStore.PairedTagAction.TEMP_DISABLE -> "temp_disable"
            NfcUidPairingStore.PairedTagAction.TEMP_ENABLE -> "temp_enable"
        }

        startActivity(
            Intent(this, NfcWriterActivity::class.java).apply {
                putExtra(NfcWriterActivity.EXTRA_REWRITE_UID, tag.uid)
                action?.let { putExtra(NfcWriterActivity.EXTRA_PRESELECT_ACTION, it) }
                tag.actionProfile?.takeIf { it.isNotBlank() }?.let {
                    putExtra(NfcWriterActivity.EXTRA_PRESELECT_PROFILE, it)
                }
                if (action == "temp_disable" || action == "temp_enable") {
                    putExtra(NfcWriterActivity.EXTRA_PRESELECT_DURATION_MINUTES, tag.durationMinutes)
                    putExtra(NfcWriterActivity.EXTRA_PRESELECT_ASK_DURATION, tag.askDurationWhenScanned)
                }
            },
        )
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun parseOptionalBoundedInt(raw: String?, min: Int, max: Int): Int {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) {
            return EMPTY_NUMBER
        }
        val parsed = text.toIntOrNull() ?: return INVALID_NUMBER
        if (parsed !in min..max) {
            return INVALID_NUMBER
        }
        return parsed
    }

    private fun confirmRemove(uid: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.paired_tags_remove_confirm) + "\n\n" + getString(R.string.destructive_cannot_be_undone))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                NfcUidPairingStore.removePairedUidHex(this, uid)
                refresh()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .showDestructiveAccented()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_manage_paired_tags, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        fabAdd?.isVisible = !selectionMode
        menu.findItem(R.id.action_info)?.isVisible = true
        menu.findItem(R.id.action_cancel_select)?.isVisible = selectionMode
        menu.findItem(R.id.action_select)?.isVisible = !selectionMode && adapter.itemCount > 0
        menu.findItem(R.id.action_delete_selected)?.isVisible = selectionMode
        menu.findItem(R.id.action_delete_selected)?.isEnabled = selectedUids.isNotEmpty()
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

            R.id.action_info -> {
                showPairedTagsInfoDialog(); true
            }

            R.id.action_select -> {
                selectionMode = true
                selectedUids.clear()
                invalidateOptionsMenu()
                notifySelectionUiChanged()
                true
            }

            R.id.action_delete_selected -> {
                confirmDeleteSelected(); true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmDeleteSelected() {
        if (selectedUids.isEmpty()) {
            exitSelectionMode()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.paired_tag_delete_selected_title))
            .setMessage(resources.getQuantityString(R.plurals.paired_tag_delete_selected_confirm_fmt, selectedUids.size, selectedUids.size) + "\n\n" + getString(R.string.destructive_cannot_be_undone))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                selectedUids.toList().forEach { uid ->
                    NfcUidPairingStore.removePairedUidHex(this, uid)
                }
                exitSelectionMode()
                refresh()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .showDestructiveAccented()
    }

    private class TagAdapter(
        private val isSelectionMode: () -> Boolean,
        private val isSelected: (NfcUidPairingStore.TagMeta) -> Boolean,
        private val onEnabledChanged: (NfcUidPairingStore.TagMeta, Boolean) -> Unit,
        private val onRowClick: (NfcUidPairingStore.TagMeta) -> Unit,
        private val onRowLongPress: (NfcUidPairingStore.TagMeta) -> Boolean,
    ) : RecyclerView.Adapter<TagAdapter.VH>() {

        private val items = mutableListOf<NfcUidPairingStore.TagMeta>()

        fun submit(list: List<NfcUidPairingStore.TagMeta>) {
            // Items are displayed sorted (by name/uid).
            // Editing a tag can change its position in the list. Range-based notifications do not handle moves and can crash RecyclerView with "Inconsistency detected".
            val oldSize = items.size

            // Replace whole dataset with explicit remove/insert events.
            // This avoids the "rely on notifyDataSetChanged" lint warning while still being robust against reordering/moves.
            if (oldSize > 0) {
                items.clear()
                notifyItemRangeRemoved(0, oldSize)
            } else {
                items.clear()
            }

            if (list.isNotEmpty()) {
                items.addAll(list)
                notifyItemRangeInserted(0, list.size)
            }
        }

        fun itemAt(position: Int): NfcUidPairingStore.TagMeta? = items.getOrNull(position)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_paired_tag, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]

            holder.name.text = item.name ?: holder.itemView.context.getString(R.string.paired_tag_default_name)
            holder.uid.text = item.uid

            // Keep the list compact: name + NFC ID only. Details stay available in the edit dialog.
            holder.note.visibility = if (item.enabled) View.GONE else View.VISIBLE
            holder.note.text = if (item.enabled) "" else holder.itemView.context.getString(R.string.paired_tag_disabled)
            val contentAlpha = if (item.enabled) 1f else 0.56f
            holder.name.alpha = contentAlpha
            holder.uid.alpha = if (item.enabled) 0.80f else 0.56f

            val sel = isSelectionMode.invoke()
            holder.swEnabled.visibility = if (sel) View.GONE else View.VISIBLE
            holder.swEnabled.setOnCheckedChangeListener(null)
            holder.swEnabled.isChecked = item.enabled
            holder.swEnabled.contentDescription = holder.itemView.context.getString(R.string.paired_tag_enabled_toggle)
            CustomAccentApplier.tintSwitch(holder.swEnabled)
            holder.swEnabled.setOnCheckedChangeListener { _, checked ->
                onEnabledChanged(item, checked)
            }

            holder.cb.visibility = if (sel) View.VISIBLE else View.GONE
            holder.cb.setOnCheckedChangeListener(null)
            val selected = sel && isSelected.invoke(item)
            holder.cb.isChecked = selected
            (holder.itemView as? MaterialCardView)?.let { card ->
                val accent = AccentColor.getAccentColorInt(holder.itemView.context)
                card.strokeWidth = if (selected) (2 * holder.itemView.resources.displayMetrics.density).toInt().coerceAtLeast(1) else 0
                card.strokeColor = if (selected) accent else android.graphics.Color.TRANSPARENT
                card.setCardBackgroundColor(
                    if (selected) ColorUtils.setAlphaComponent(accent, 0x14)
                    else com.google.android.material.color.MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorSurface)
                )
            }
            // Prevent the checkbox from toggling only its visual state without updating selectedUids.
            // All selection changes should go through the row click handler.
            holder.cb.isClickable = false
            holder.cb.isFocusable = false

            // In CUSTOM accent mode, MaterialCheckBox can still fall back to the default theme green.
            // Force the button tint per-row (RecyclerView items are inflated after the main accent pass).
            if (AccentColor.getOption(holder.itemView.context) == AccentColor.Option.CUSTOM) {
                val accent = AccentColor.getAccentColorInt(holder.itemView.context)
                // Prefer the public API when available; fallback to reflection otherwise.
                runCatching { holder.cb.setUseMaterialThemeColors(false) }
                runCatching {
                    val m = holder.cb.javaClass.methods.firstOrNull {
                        it.name == "setUseMaterialThemeColors" &&
                            it.parameterTypes.size == 1 &&
                            it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                    }
                    m?.invoke(holder.cb, false)
                }

                val unchecked = (accent and 0x00FFFFFF) or (0x8C shl 24) // ~55% alpha
                holder.cb.buttonTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    ),
                    intArrayOf(
                        accent,
                        unchecked
                    )
                )
            }

            holder.itemView.setOnClickListener { onRowClick(item) }
            holder.cb.setOnClickListener { onRowClick(item) }
            holder.itemView.setOnLongClickListener { onRowLongPress(item) }
        }

        override fun getItemCount(): Int = items.size

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tvName)
            val uid: TextView = v.findViewById(R.id.tvUid)
            val note: TextView = v.findViewById(R.id.tvNote)
            val swEnabled: SwitchCompat = v.findViewById(R.id.swTagEnabled)
            val cb: MaterialCheckBox = v.findViewById(R.id.cbSelect)
        }
    }
}
