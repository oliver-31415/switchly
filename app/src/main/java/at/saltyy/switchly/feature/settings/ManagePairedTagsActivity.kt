package at.saltyy.switchly.feature.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.data.prefs.NfcTempDisableLimiterStore
import at.saltyy.switchly.data.prefs.NfcUidPairingStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputLayout
import java.text.NumberFormat
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.util.EditingLockGuard
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.PreferenceManager
import at.saltyy.switchly.nfc.NfcWriteWaitingActivity
import at.saltyy.switchly.nfc.NfcWriterActivity

class ManagePairedTagsActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
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

    private fun setSortMode(mode: SortMode) {
        val sp = getSharedPreferences("switchly_prefs", MODE_PRIVATE)
        sp.edit { putInt("paired_tags_sort_mode", mode.id) }
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
        if (EditingLockGuard.blockWithDialog(this, R.string.edit_locked_manage_paired_tags)) return

        val pairedTagsEnabled = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, false)
        if (!pairedTagsEnabled) {
            finish()
            return
        }

        setContentView(R.layout.activity_manage_paired_tags)

        // Ensure CUSTOM accent mode recolors checkboxes/cursor in this screen + dialogs.
        CustomAccentApplier.applyIfNeeded(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
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

        fabAdd = findViewById(R.id.fabAdd)
        fabAdd?.setOnClickListener {
            if (!selectionMode) showAddDialog()
        }
    }

    private fun notifySelectionUiChanged() {
        // Selection mode toggles checkboxes in row layout.
        // A full refresh keeps it simple and avoids edge cases with moves.
        if (::adapter.isInitialized) adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val tags = NfcUidPairingStore.getPairedTags(this)
        val sorted = applySort(tags, getSortMode())
        lastTags = sorted
        adapter.submit(sorted)
        empty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
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

    private fun showSortDialog() {
        val modes = listOf(
            SortMode.NEW_OLD,
            SortMode.OLD_NEW,
            SortMode.NAME_AZ,
            SortMode.NAME_ZA
        )
        val labels = arrayOf(
            getString(R.string.paired_tags_sort_new_old),
            getString(R.string.paired_tags_sort_old_new),
            getString(R.string.paired_tags_sort_name_az),
            getString(R.string.paired_tags_sort_name_za)
        )

        val current = getSortMode()
        val checked = modes.indexOf(current).coerceAtLeast(0)

        // Use the same custom single-select list component as in Settings.
        // This avoids OEM/framework tinted list indicators that can stay green in CUSTOM accent mode.
        showSingleSelectRadioDialog(
            title = getString(R.string.paired_tags_sort_title),
            entries = labels.toList(),
            checkedIndex = checked,
        ) { which, dialog ->
            val picked = modes.getOrNull(which) ?: SortMode.NAME_AZ
            setSortMode(picked)
            refresh()
            dialog.dismiss()
        }
    }

    private fun showAddDialog() {
        val entries = listOf(
            getString(R.string.paired_tags_add_writable_title),
            getString(R.string.paired_tags_add_readonly_title)
        )

        showSimpleChoiceDialog(
            title = getString(R.string.paired_tags_add_title),
            entries = entries,
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
        onSelected: (index: Int, dialog: AlertDialog) -> Unit,
    ) {
        val content = layoutInflater.inflate(R.layout.dialog_single_select_list, null)
        val rv = content.findViewById<RecyclerView>(R.id.recycler)
        rv.layoutManager = LinearLayoutManager(this)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        val adapter = SimpleChoiceAdapter(entries) { which ->
            onSelected(which, dialog)
        }
        rv.adapter = adapter

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private fun showSingleSelectRadioDialog(
        title: String,
        entries: List<String>,
        checkedIndex: Int,
        onSelected: (index: Int, dialog: AlertDialog) -> Unit,
    ) {
        val content = layoutInflater.inflate(R.layout.dialog_single_select_list, null)
        val rv = content.findViewById<RecyclerView>(R.id.recycler)
        rv.layoutManager = LinearLayoutManager(this)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        val adapter = SingleSelectRadioAdapter(
            entries = entries,
            initialSelected = checkedIndex,
        ) { which ->
            onSelected(which, dialog)
        }
        rv.adapter = adapter

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private fun toggleSelection(uid: String) {
        if (selectedUids.contains(uid)) selectedUids.remove(uid) else selectedUids.add(uid)
        if (selectedUids.isEmpty()) {
            selectionMode = false
        }
        invalidateOptionsMenu()
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedUids.clear()
        invalidateOptionsMenu()
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    private fun showEditDialog(tag: NfcUidPairingStore.TagMeta) {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_paired_tag_edit, null)
        val etName = content.findViewById<EditText>(R.id.etTagName)
        val etNote = content.findViewById<EditText>(R.id.etTagNote)
        val etDailyLimit = content.findViewById<EditText>(R.id.etTagDailyLimit)
        val etCooldown = content.findViewById<EditText>(R.id.etTagCooldownMinutes)
        val tvUid = content.findViewById<TextView>(R.id.tvUid)

        val btnSave = content.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSave)
        val btnCancel = content.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)
        val btnDelete = content.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDelete)

        val tilName = content.findViewById<TextInputLayout>(R.id.tilTagName)
        val tilNote = content.findViewById<TextInputLayout>(R.id.tilTagNote)

        val uidBucket = NfcTempDisableLimiterStore.bucketForUid(tag.uid)

        tvUid.text = tag.uid
        etName.setText(tag.name.orEmpty())
        etNote.setText(tag.note.orEmpty())

        NfcTempDisableLimiterStore.getDailyLimitOverride(uidBucket, this)
            ?.let { etDailyLimit.setText(NumberFormat.getIntegerInstance().format(it)) }
        NfcTempDisableLimiterStore.getCooldownOverrideMinutes(uidBucket, this)
            ?.let { etCooldown.setText(NumberFormat.getIntegerInstance().format(it)) }

        // Make sure inputs (cursor blink/selection) are already hooked before the dialog is shown.
        runCatching { CustomAccentApplier.applyToView(content, this) }

        // Extra-hardening for paired-tag inputs: some OEM/Material combos keep the focused stroke/cursor in the system accent colour even after our global pass.
        val accent = AccentColor.getAccentColorInt(this)
        fun forceInputs() {
            runCatching { forceTextInputAccent(tilName, accent) }
            runCatching { forceTextInputAccent(tilNote, accent) }
            runCatching { forceCursor(etName, accent) }
            runCatching { forceCursor(etNote, accent) }
        }

        // Use a custom button row inside the dialog view so spacing/tint is consistent.
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.paired_tag_edit_title))
            .setView(content)
            .create()
        dialog.setOnShowListener {
            // Retint inputs + any default Material accents when CUSTOM accent is selected.
            runCatching { CustomAccentApplier.applyToDialog(dialog) }

            // Force stroke + cursor specifically for the paired-tag inputs.
            forceInputs()

            // Some Material/OEM combos recreate focused stroke + cursor tint after show/focus.
            // Re-apply on the whole content view a few times so TextInputLayout focus state doesnt fall back to theme green
            longArrayOf(0L, 80L, 220L, 520L, 1000L).forEach { d ->
                content.postDelayed({ runCatching { CustomAccentApplier.applyToView(content, this) } }, d)
            }

            // And re-force the critical parts (cursor/stroke) after those passes.
            longArrayOf(120L, 360L, 720L, 1200L).forEach { d ->
                content.postDelayed({ runCatching { forceInputs() } }, d)
            }
        }
        dialog.show()

        // Keep cancel/delete as lightweight text buttons, but make save the primary filled action.
        listOf(btnCancel, btnDelete).forEach { button ->
            button.setTextColor(accent)
            button.isAllCaps = false
            button.strokeColor = null
            button.backgroundTintList = null
            runCatching { button.setBackgroundColor(android.graphics.Color.TRANSPARENT) }
            button.iconTint = ColorStateList.valueOf(accent)
            button.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x22))
        }

        btnSave.isAllCaps = false
        btnSave.strokeColor = null
        btnSave.backgroundTintList = ColorStateList.valueOf(accent)
        btnSave.setTextColor(ContextCompat.getColor(this, R.color.font_white))
        btnSave.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.font_white))
        btnSave.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x44))

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnDelete.setOnClickListener {
            dialog.dismiss()
            confirmRemove(tag.uid)
        }

        btnSave.setOnClickListener {
            val dailyResult = parseOptionalBoundedInt(
                raw = etDailyLimit.text?.toString(),
                min = 1,
                max = 50,
            )
            if (dailyResult == INVALID_NUMBER) {
                Toast.makeText(this, getString(R.string.paired_tag_daily_limit_invalid), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val cooldownResult = parseOptionalBoundedInt(
                raw = etCooldown.text?.toString(),
                min = 1,
                max = 24 * 60,
            )
            if (cooldownResult == INVALID_NUMBER) {
                Toast.makeText(this, getString(R.string.paired_tag_cooldown_invalid), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val daily = dailyResult.takeIf { it != EMPTY_NUMBER }
            val cooldown = cooldownResult.takeIf { it != EMPTY_NUMBER }

            NfcUidPairingStore.setTagMeta(
                this,
                tag.uid,
                etName.text?.toString(),
                etNote.text?.toString()
            )
            NfcTempDisableLimiterStore.setTagConfig(
                uidBucket = uidBucket,
                ctx = this,
                dailyLimit = daily,
                cooldownMinutes = cooldown,
            )

            refresh()
            dialog.dismiss()
        }
    }

    private fun forceTextInputAccent(til: TextInputLayout?, accent: Int) {
        til ?: return
        val enabled = intArrayOf(android.R.attr.state_enabled)
        val focused = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused)
        val hovered = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_hovered)
        val disabled = intArrayOf(-android.R.attr.state_enabled)

        val normal = ColorUtils.setAlphaComponent(accent, 0xAA)
        val hover = ColorUtils.setAlphaComponent(accent, 0xCC)
        val dis = ColorUtils.setAlphaComponent(accent, 0x55)

        runCatching {
            til.setBoxStrokeColorStateList(
                ColorStateList(
                    arrayOf(focused, hovered, enabled, disabled),
                    intArrayOf(accent, hover, normal, dis)
                )
            )
        }
        runCatching { til.boxStrokeColor = accent }

        val tint = ColorStateList.valueOf(accent)
        runCatching { til.hintTextColor = tint }
        runCatching { til.defaultHintTextColor = tint }
        runCatching { til.setStartIconTintList(tint) }
        runCatching { til.setEndIconTintList(tint) }

        (til.editText as? EditText)?.let { forceCursor(it, accent) }
    }

    private fun forceCursor(et: EditText, accent: Int) {
        runCatching {
            et.highlightColor = ColorUtils.setAlphaComponent(accent, 0x44)
        }

        val cursorDrawable = GradientDrawable().apply {
            setColor(accent)
            setSize(dpToPx(2), (et.lineHeight * 2).coerceAtLeast(dpToPx(24)))
        }

        // On API 29+ prefer the public setter only (more stable on multi-line TextInputEditText).
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { et.textCursorDrawable = cursorDrawable }
            et.invalidate()
            return
        }

        // Best-effort public/hidden setters (OEM/framework differences)
        runCatching {
            val m = TextView::class.java.getMethod(
                "setTextCursorDrawable",
                android.graphics.drawable.Drawable::class.java
            )
            m.invoke(et, cursorDrawable)
        }
        runCatching {
            val m = TextView::class.java.getDeclaredMethod(
                "setTextCursorDrawable",
                android.graphics.drawable.Drawable::class.java
            )
            m.isAccessible = true
            m.invoke(et, cursorDrawable)
        }

    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun parseOptionalBoundedInt(raw: String?, min: Int, max: Int): Int {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return EMPTY_NUMBER
        val parsed = text.toIntOrNull() ?: return INVALID_NUMBER
        if (parsed !in min..max) return INVALID_NUMBER
        return parsed
    }

    private fun confirmRemove(uid: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.paired_tags_remove_confirm))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                NfcUidPairingStore.removePairedUidHex(this, uid)
                refresh()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .showAccented()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_manage_paired_tags, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        fabAdd?.isVisible = !selectionMode
        menu.findItem(R.id.action_cancel_select)?.isVisible = selectionMode
        menu.findItem(R.id.action_sort)?.isVisible = !selectionMode
        menu.findItem(R.id.action_select)?.isVisible = !selectionMode
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

            R.id.action_sort -> {
                showSortDialog(); true
            }

            R.id.action_select -> {
                selectionMode = true
                selectedUids.clear()
                invalidateOptionsMenu()
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
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
            .setMessage(resources.getQuantityString(R.plurals.paired_tag_delete_selected_confirm_fmt, selectedUids.size, selectedUids.size))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                selectedUids.toList().forEach { uid ->
                    NfcUidPairingStore.removePairedUidHex(this, uid)
                }
                exitSelectionMode()
                refresh()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .showAccented()
    }

    /**
     * Custom single-select list adapter (radio rows) used in dialogs.
     * Ensures the radio indicator always follows Switchly's accent/custom colour.
     */
    private class SingleSelectRadioAdapter(
        private val entries: List<String>,
        initialSelected: Int,
        private val onSelected: (Int) -> Unit,
    ) : RecyclerView.Adapter<SingleSelectRadioAdapter.VH>() {

        private var selectedIndex: Int = initialSelected.coerceIn(0, (entries.size - 1).coerceAtLeast(0))

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_single_select_checkbox, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.title.text = entries[position]

            val accent = AccentColor.getAccentColorInt(holder.itemView.context)
            val unchecked = (accent and 0x00FFFFFF) or (0x8C shl 24) // ~55% alpha
            holder.radio.buttonTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(
                    accent,
                    unchecked
                )
            )

            holder.radio.setOnCheckedChangeListener(null)
            holder.radio.isChecked = position == selectedIndex
            holder.radio.isClickable = false
            holder.radio.isFocusable = false

            fun select() {
                val p = holder.bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return
                selectedIndex = p
                onSelected(p)
            }

            holder.itemView.setOnClickListener { select() }
        }

        override fun getItemCount(): Int = entries.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.title)
            val radio: MaterialRadioButton = itemView.findViewById(R.id.radio)
        }
    }

    private class SimpleChoiceAdapter(
        private val entries: List<String>,
        private val onSelected: (Int) -> Unit,
    ) : RecyclerView.Adapter<SimpleChoiceAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_single_select_plain, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.title.text = entries[position]
            holder.itemView.setOnClickListener {
                val p = holder.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onSelected(p)
            }
        }

        override fun getItemCount(): Int = entries.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.title)
        }
    }

    private class TagAdapter(
        private val isSelectionMode: () -> Boolean,
        private val isSelected: (NfcUidPairingStore.TagMeta) -> Boolean,
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

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_paired_tag, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]

            holder.name.text = item.name ?: holder.itemView.context.getString(R.string.paired_tag_default_name)
            holder.uid.text = item.uid

            if (item.note.isNullOrBlank()) {
                holder.note.visibility = View.GONE
            } else {
                holder.note.visibility = View.VISIBLE
                holder.note.text = item.note
            }

            val sel = isSelectionMode.invoke()
            holder.cb.visibility = if (sel) View.VISIBLE else View.GONE
            holder.cb.setOnCheckedChangeListener(null)
            holder.cb.isChecked = isSelected.invoke(item)
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
            val cb: MaterialCheckBox = v.findViewById(R.id.cbSelect)
        }
    }
}