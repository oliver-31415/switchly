package at.saltyy.switchly.feature.profiles

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class ManageProfilesActivity : AppCompatActivity() {

    private fun isProfileLockActive(): Boolean {
        val enabled = SwitchModeStore.isEnabled(this)
        val requireNfc = SwitchModeStore.isNfcRequiredForDisable(this)
        return enabled && requireNfc
    }

    private fun showProfileLockedToast() {
        Toast.makeText(
            this,
            getString(R.string.toast_cannot_change_profile_while_locked),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun snackRoot(): View {
        return findViewById(android.R.id.content) ?: window.decorView
    }

    private lateinit var list: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private val data = mutableListOf<String>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage_profiles)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        val btnAdd = findViewById<MaterialButton>(R.id.btnAdd)
        btnAdd.backgroundTintList = AccentColor.getActiveColor(this)

        list = findViewById(R.id.listProfiles)
        adapter = object : ArrayAdapter<String>(this, R.layout.row_profile_item, data) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.row_profile_item, parent, false)
                val tv = v.findViewById<TextView>(R.id.tvName)
                val badge = v.findViewById<TextView>(R.id.tvActiveBadge)
                val name = getItem(position) ?: ""
                tv.text = name
                val current = ProfileStore.getCurrent(this@ManageProfilesActivity)
                badge.visibility = if (name == current) View.VISIBLE else View.GONE
                return v
            }
        }
        list.adapter = adapter

        findViewById<Button>(R.id.btnAdd).setOnClickListener { showAddProfileSheet() }
        list.setOnItemClickListener { _, _, position, _ -> showActions(position) }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        findViewById<MaterialToolbar>(R.id.toolbar)
            .setBackgroundColor(AccentColor.getToolbarColor(this))
        refresh()
    }

    // Loads all profiles and sorts them so the active one is on top.
    private fun refresh() {
        val current = ProfileStore.getCurrent(this)
        val all = ProfileStore.getProfiles(this)

        val sorted = if (!current.isNullOrEmpty()) {
            listOf(current) + all.filter { it != current }.sorted()
        } else {
            all.sorted()
        }

        data.clear()
        data.addAll(sorted)
        adapter.notifyDataSetChanged()
    }

    // Shows a context menu for a profile – but hides "set active" if it’s already active.
    private fun showActions(pos: Int) {
        val name = data[pos]
        val current = ProfileStore.getCurrent(this)

        // base actions
        val actions = mutableListOf(
            getString(R.string.action_rename_profile),
            getString(R.string.action_duplicate_profile)
        )

        // show "set active" only if it’s not currently active
        if (name != current) {
            actions.add(getString(R.string.action_set_active_profile))
        }

        // delete is always allowed
        actions.add(getString(R.string.action_delete_profile))

        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(actions.toTypedArray()) { _, which ->
                var idx = 0

                // rename
                if (which == idx) {
                    showRenameProfileSheet(name)
                    return@setItems
                }
                idx++

                // duplicate
                if (which == idx) {
                    duplicateProfile(name)
                    return@setItems
                }
                idx++

                // set active (only if present)
                if (name != current) {
                    if (which == idx) {
                        setActiveProfile(name)
                        return@setItems
                    }
                    idx++
                }

                // delete
                if (which == idx) {
                    deleteProfile(name)
                }
            }
            .show()
    }

    private fun showAddProfileSheet() {
        if (isProfileLockActive()) {
            showProfileLockedToast()
            return
        }
        val sheet = BottomSheetDialog(this)
        val parent = findViewById<ViewGroup>(android.R.id.content)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_add_profile, parent, false)
        sheet.setContentView(view)

        val til = view.findViewById<TextInputLayout>(R.id.tilProfile)
        val et = view.findViewById<TextInputEditText>(R.id.etProfile)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        val btnCreate = view.findViewById<MaterialButton>(R.id.btnCreate)

        btnCancel.backgroundTintList = AccentColor.getActiveColor(this)
        btnCreate.backgroundTintList = AccentColor.getActiveColor(this)

        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS

        fun validate(): Boolean {
            val name = et.text?.toString()?.trim().orEmpty()
            return when {
                name.length < 2 -> {
                    til.error = getString(R.string.profile_name_too_short); false
                }
                ProfileStore.getProfiles(this).contains(name) -> {
                    til.error = getString(R.string.profile_name_exists, name); false
                }
                else -> {
                    til.error = null; true
                }
            }
        }

        btnCreate.isEnabled = false
        et.addTextChangedListener(simpleTextWatcher { btnCreate.isEnabled = validate() })

        btnCancel.setOnClickListener { sheet.dismiss() }
        btnCreate.setOnClickListener {
            if (!validate()) return@setOnClickListener
            val name = et.text?.toString()?.trim().orEmpty()
            if (ProfileStore.addProfile(this, name)) {
                ProfileStore.setCurrent(this, name)
                refresh()
                sheet.dismiss()

                Snackbar.make(
                    snackRoot(),
                    getString(R.string.profile_created, name),
                    Snackbar.LENGTH_SHORT
                ).show()
            } else {
                til.error = getString(R.string.profile_name_exists, name)
            }
        }

        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
    }

    private fun showRenameProfileSheet(oldName: String) {
        if (isProfileLockActive()) {
            showProfileLockedToast()
            return
        }
        val sheet = BottomSheetDialog(this)
        val parent = findViewById<ViewGroup>(android.R.id.content)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_add_profile, parent, false)
        sheet.setContentView(view)

        val til = view.findViewById<TextInputLayout>(R.id.tilProfile)
        val et = view.findViewById<TextInputEditText>(R.id.etProfile)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        val btnCreate = view.findViewById<MaterialButton>(R.id.btnCreate)

        btnCancel.backgroundTintList = AccentColor.getActiveColor(this)
        btnCreate.backgroundTintList = AccentColor.getActiveColor(this)

        et.setText(oldName)
        // selection safe (prevents IndexOutOfBounds with emoji spans / builders)
        et.post {
            val len = et.text?.length ?: 0
            et.setSelection(len.coerceIn(0, len))
        }

        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS

        fun validate(): Boolean {
            val newName = et.text?.toString()?.trim().orEmpty()
            return when {
                newName.length < 2 -> {
                    til.error = getString(R.string.profile_name_too_short); false
                }
                newName == oldName -> {
                    til.error = null; true
                }
                ProfileStore.getProfiles(this).contains(newName) -> {
                    til.error = getString(R.string.profile_name_exists, newName); false
                }
                else -> {
                    til.error = null; true
                }
            }
        }

        btnCreate.isEnabled = true
        et.addTextChangedListener(simpleTextWatcher { btnCreate.isEnabled = validate() })

        btnCancel.setOnClickListener { sheet.dismiss() }
        btnCreate.setOnClickListener {
            if (!validate()) return@setOnClickListener
            val newName = et.text?.toString()?.trim().orEmpty()

            if (newName != oldName) {
                val ok = ProfileStore.renameProfile(this, oldName, newName)
                if (!ok) {
                    til.error = getString(R.string.profile_name_exists, newName)
                    return@setOnClickListener
                }
                if (ProfileStore.getCurrent(this) == oldName) {
                    ProfileStore.setCurrent(this, newName)
                }
            }

            refresh()
            sheet.dismiss()

            Snackbar.make(
                snackRoot(),
                getString(R.string.profile_renamed, newName.ifEmpty { oldName }),
                Snackbar.LENGTH_SHORT
            ).show()
        }

        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
    }

    private fun deleteProfile(name: String) {
        if (isProfileLockActive()) {
            showProfileLockedToast()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_profile_confirm, name))
            .setPositiveButton(R.string.ok) { _, _ ->
                ProfileStore.removeProfile(this, name)
                val profiles = ProfileStore.getProfiles(this)
                ProfileStore.setCurrent(this, profiles.firstOrNull() ?: "")
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun duplicateProfile(name: String) {
        if (isProfileLockActive()) {
            showProfileLockedToast()
            return
        }
        val blocked = ProfileStore.getBlockedForProfile(this, name)
        var i = 0
        var newName: String
        val existing = ProfileStore.getProfiles(this)
        while (true) {
            newName = if (i == 0) {
                getString(R.string.profile_duplicate_name_fmt, name)
            } else {
                getString(R.string.profile_duplicate_name_numbered_fmt, name, i + 1)
            }
            if (!existing.contains(newName)) break
            i++
        }
        val ok = ProfileStore.addProfile(this, newName)
        if (ok) {
            ProfileStore.setBlockedForProfile(this, newName, blocked)
            Toast.makeText(
                this,
                getString(R.string.profile_duplicated, newName),
                Toast.LENGTH_SHORT
            ).show()
            refresh()
        } else {
            Toast.makeText(
                this,
                getString(R.string.profile_name_exists, newName),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setActiveProfile(name: String) {
        if (isProfileLockActive()) {
            showProfileLockedToast()
            return
        }

        ProfileStore.setCurrent(this, name)
        Toast.makeText(
            this,
            getString(R.string.profile_set_active_toast, name),
            Toast.LENGTH_SHORT
        ).show()
        refresh()
    }

    private fun simpleTextWatcher(onChange: (CharSequence?) -> Unit) =
        object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onChange(s)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
}
