package at.saltyy.switchly.feature.profiles

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.SwitchlyAppAccessGuard
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class ManageProfilesActivity : AppCompatActivity() {

    private fun isProfileLockActive(): Boolean {
        // Use the persisted base state so temporary schedule/temp-disable changes do not make profile switching appear to randomly lock or unlock.
        val enabled = SwitchModeStore.isBaseEnabled(this)
        if (!enabled) return false

        val requireNfc = SwitchModeStore.isNfcRequiredForDisable(this)
        if (requireNfc) return true

        return !AutomationModeStore.isProfileSwitchingAllowedWhileEnabled(this)
    }

    private fun profileLockReasonMessageRes(): Int {
        val enabled = SwitchModeStore.isBaseEnabled(this)
        val requireNfc = SwitchModeStore.isNfcRequiredForDisable(this)
        return if (enabled && !requireNfc) {
            R.string.toast_disable_switchly_to_switch_profiles
        } else {
            R.string.toast_cannot_change_profile_while_locked
        }
    }

    private fun showProfileLockedToast() {
        Toast.makeText(
            this,
            getString(profileLockReasonMessageRes()),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun snackRoot(): View {
        return findViewById(android.R.id.content) ?: window.decorView
    }

    private fun syncProfileLockUi() {
        val locked = isProfileLockActive()
        findViewById<FloatingActionButton>(R.id.fabAdd)?.alpha = if (locked) 0.62f else 1f
        list.alpha = if (locked) 0.92f else 1f
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
        if (SwitchlyAppAccessGuard.blockIfLocked(this)) return
        setContentView(R.layout.activity_manage_profiles)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        val fabAdd = findViewById<FloatingActionButton>(R.id.fabAdd)
        fabAdd.backgroundTintList = AccentColor.getActiveColor(this)

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

        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener { showAddProfileSheet() }
        list.setOnItemClickListener { _, _, position, _ -> showActions(position) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SwitchModeStore.enabledFlow.collect {
                    runOnUiThread { syncProfileLockUi() }
                }
            }
        }

        refresh()
        syncProfileLockUi()
    }

    override fun onResume() {
        super.onResume()
        if (SwitchlyAppAccessGuard.blockIfLocked(this)) return
        findViewById<MaterialToolbar>(R.id.toolbar)
            .setBackgroundColor(AccentColor.getToolbarColor(this))
        refresh()
        syncProfileLockUi()
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

        val actions = mutableListOf(
            getString(R.string.action_rename_profile),
            getString(R.string.action_duplicate_profile)
        )

        if (name != current) {
            actions.add(getString(R.string.action_set_active_profile))
        }

        actions.add(getString(R.string.action_delete_profile))

        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(actions.toTypedArray()) { _, which ->
                var idx = 0

                if (which == idx) {
                    showRenameProfileSheet(name)
                    return@setItems
                }
                idx++

                if (which == idx) {
                    duplicateProfile(name)
                    return@setItems
                }
                idx++

                if (name != current) {
                    if (which == idx) {
                        setActiveProfile(name)
                        return@setItems
                    }
                    idx++
                }

                if (which == idx) {
                    deleteProfile(name)
                }
            }
            .showAccented()
    }

    private fun showAddProfileSheet() {
        if (isProfileLockActive()) {
            showProfileLockedToast()
            return
        }

        showProfileNameDialog(
            title = getString(R.string.add_profile),
            positiveText = getString(R.string.create),
            initialValue = ""
        ) { name ->
            if (ProfileStore.addProfile(this, name)) {
                ProfileStore.setCurrent(this, name)
                refresh()
                Snackbar.make(
                    snackRoot(),
                    getString(R.string.profile_created, name),
                    Snackbar.LENGTH_SHORT
                ).show()
                null
            } else {
                getString(R.string.profile_name_exists, name)
            }
        }
    }

    private fun showRenameProfileSheet(oldName: String) {
        if (isProfileLockActive()) {
            showProfileLockedToast()
            return
        }

        showProfileNameDialog(
            title = getString(R.string.profile_rename_hint),
            positiveText = getString(R.string.rename),
            initialValue = oldName
        ) { newName ->
            when {
                newName == oldName -> null
                ProfileStore.getProfiles(this).contains(newName) -> {
                    getString(R.string.profile_name_exists, newName)
                }
                ProfileStore.renameProfile(this, oldName, newName) -> {
                    refresh()
                    Snackbar.make(
                        snackRoot(),
                        getString(R.string.profile_renamed, newName),
                        Snackbar.LENGTH_SHORT
                    ).show()
                    null
                }
                else -> getString(R.string.error_unknown)
            }
        }
    }

    private fun showProfileNameDialog(
        title: String,
        positiveText: String,
        initialValue: String = "",
        onSubmit: (String) -> String?
    ) {
        val content = layoutInflater.inflate(R.layout.dialog_profile_name, null)
        val tilProfile = content.findViewById<TextInputLayout>(R.id.tilProfile)
        val input = content.findViewById<TextInputEditText>(R.id.etProfile)

        input.setText(initialValue)
        input.setSelection(initialValue.length)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(content)
            .setPositiveButton(positiveText, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.styleSwitchlyDialogButtons()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                when {
                    name.length < 2 -> {
                        tilProfile.error = getString(R.string.profile_name_too_short)
                    }
                    else -> {
                        tilProfile.error = null
                        val error = onSubmit(name)
                        if (error == null) {
                            dialog.dismiss()
                        } else {
                            tilProfile.error = error
                        }
                    }
                }
            }
        }

        input.requestFocus()
        dialog.show()
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
            .showAccented()
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
}
