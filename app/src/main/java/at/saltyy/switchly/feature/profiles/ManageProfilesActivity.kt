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

package at.saltyy.switchly.feature.profiles

import android.content.Context
import android.content.Intent
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
import at.saltyy.switchly.data.prefs.DomainBlockStore
import at.saltyy.switchly.data.prefs.InAppLimitStore
import at.saltyy.switchly.data.prefs.InAppRuleStore
import at.saltyy.switchly.data.prefs.SurfaceLimitStore
import at.saltyy.switchly.data.prefs.ProfileRuleModeStore
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.picker.AppPickerActivity
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.ui.dialog.showDestructiveAccented
import at.saltyy.switchly.ui.dialog.SwitchlyDialogOption
import at.saltyy.switchly.ui.dialog.showSwitchlyOptionDialog
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.EditingLockGuard
import at.saltyy.switchly.util.SwitchlyAppAccessGuard
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class ManageProfilesActivity : AppCompatActivity() {

    private fun isProfileLockActive(): Boolean {
        val enabled = SwitchModeStore.isEnabled(this)
        val temporaryOverrideActive = SwitchModeStore.hasActiveTemporaryOverride(this)
        val emergencyActive = EmergencyBypassStore.isActive(this)
        val emergencyPaused = EmergencyBypassStore.isPaused(this)
        if (!enabled && !temporaryOverrideActive && !emergencyActive) return false

        if (temporaryOverrideActive) return true

        val requireNfc = SwitchModeStore.isNfcRequiredForDisable(this)
        if (requireNfc || emergencyActive || (enabled && emergencyPaused)) return true

        return !AutomationModeStore.isProfileSwitchingAllowedWhileEnabled(this)
    }

    private fun profileLockReasonMessageRes(): Int {
        val enabled = SwitchModeStore.isEnabled(this)
        val temporaryOverrideActive = SwitchModeStore.hasActiveTemporaryOverride(this)
        val emergencyActive = EmergencyBypassStore.isActive(this)
        val emergencyPaused = EmergencyBypassStore.isPaused(this)
        val requireNfc = SwitchModeStore.isNfcRequiredForDisable(this)
        return if (enabled && !temporaryOverrideActive && !requireNfc && !emergencyActive && !emergencyPaused) {
            R.string.toast_disable_switchly_to_switch_profiles
        } else {
            R.string.toast_cannot_change_profile_while_locked
        }
    }

    private fun showProfileLockedToast() {
        EditingLockGuard.showLockedDialog(this, profileLockReasonMessageRes())
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
                val subtitle = v.findViewById<TextView>(R.id.tvSubtitle)
                val badge = v.findViewById<TextView>(R.id.tvActiveBadge)
                val card = v.findViewById<MaterialCardView>(R.id.cardProfile)
                val activeBar = v.findViewById<View>(R.id.viewActiveBar)
                val name = getItem(position) ?: ""
                val modeLabel = if (ProfileRuleModeStore.isAllowMode(this@ManageProfilesActivity, name)) {
                    getString(R.string.profile_rule_mode_allow)
                } else {
                    getString(R.string.profile_rule_mode_block)
                }
                tv.text = getString(R.string.profile_name_mode_format, name, modeLabel)
                val description = ProfileStore.getDescription(this@ManageProfilesActivity, name)
                subtitle.text = description.ifBlank { getString(R.string.profile_row_subtitle) }
                val current = ProfileStore.getCurrent(this@ManageProfilesActivity)
                val isActive = name == current
                badge.visibility = if (isActive) View.VISIBLE else View.GONE
                activeBar.visibility = if (isActive) View.VISIBLE else View.GONE
                val accent = AccentColor.getAccentColorInt(this@ManageProfilesActivity)
                card.strokeWidth = if (isActive) (2 * this@ManageProfilesActivity.resources.displayMetrics.density).toInt().coerceAtLeast(1) else (1 * this@ManageProfilesActivity.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                card.strokeColor = if (isActive) accent else android.graphics.Color.TRANSPARENT
                badge.setTextColor(android.graphics.Color.WHITE)
                badge.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
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

    // Shows one flat, grouped context menu for a profile.
    private fun showActions(pos: Int) {
        val name = data[pos]
        val current = ProfileStore.getCurrent(this)

        data class ProfileAction(
            val title: String,
            val summary: String?,
            val icon: Int,
            val destructive: Boolean = false,
            val perform: () -> Unit,
        )

        val actions = mutableListOf(
            ProfileAction(
                title = getString(R.string.action_manage_selected_apps),
                summary = getString(R.string.profile_action_apps_summary),
                icon = R.drawable.apps_24,
                perform = { openAppListForProfile(name) }
            ),
            ProfileAction(
                title = getString(R.string.action_edit_profile_details),
                summary = getString(R.string.profile_action_details_summary),
                icon = R.drawable.edit_24,
                perform = { showRenameProfileSheet(name) }
            ),
            ProfileAction(
                title = getString(R.string.action_duplicate_profile),
                summary = getString(R.string.profile_action_duplicate_summary),
                icon = R.drawable.content_copy_24,
                perform = { duplicateProfile(name) }
            )
        )

        if (name != current) {
            actions += ProfileAction(
                title = getString(R.string.action_set_active_profile),
                summary = getString(R.string.profile_action_set_active_summary),
                icon = R.drawable.switch_account_24,
                perform = { setActiveProfile(name) }
            )
        }

        actions += ProfileAction(
            title = getString(R.string.action_delete_profile),
            summary = getString(R.string.destructive_cannot_be_undone),
            icon = R.drawable.delete_24,
            destructive = true,
            perform = { deleteProfile(name) }
        )

        showSwitchlyOptionDialog(
            title = name,
            options = actions.map { action ->
                SwitchlyDialogOption(
                    title = action.title,
                    summary = action.summary,
                    iconRes = action.icon,
                    destructive = action.destructive
                )
            }
        ) { which ->
            actions.getOrNull(which)?.perform?.invoke()
        }
    }

    private fun openAppListForProfile(profile: String) {
        if (EditingLockGuard.isLocked(this)) {
            EditingLockGuard.showLockedDialog(this, R.string.toast_disable_switchly_to_edit_blocked_apps)
            return
        }
        if (isProfileLockActive()) {
            showProfileLockedToast()
            return
        }

        val intent = Intent(this, AppPickerActivity::class.java).apply {
            putExtra(AppPickerActivity.EXTRA_PROFILE_NAME, profile)
        }
        startActivity(intent)
    }

    private fun showAddProfileSheet() {
        if (isProfileLockActive()) {
            showProfileLockedToast()
            return
        }

        showProfileNameDialog(
            title = getString(R.string.add_profile),
            positiveText = getString(R.string.create),
            initialValue = "",
            initialDescription = "",
            helperText = getString(R.string.profile_create_helper)
        ) { name, description ->
            if (ProfileStore.addProfile(this, name)) {
                ProfileStore.setDescription(this, name, description)
                ProfileStore.setCurrent(this, name)
                refresh()
                Snackbar.make(
                    snackRoot(),
                    getString(R.string.profile_created_open_apps, name),
                    Snackbar.LENGTH_SHORT
                ).show()
                snackRoot().post { openAppListForProfile(name) }
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

        val oldDescription = ProfileStore.getDescription(this, oldName)
        showProfileNameDialog(
            title = getString(R.string.profile_details_title),
            positiveText = getString(R.string.save),
            initialValue = oldName,
            initialDescription = oldDescription,
            helperText = getString(R.string.profile_details_helper)
        ) { newName, description ->
            when {
                newName != oldName && ProfileStore.getProfiles(this).contains(newName) -> {
                    getString(R.string.profile_name_exists, newName)
                }
                newName == oldName -> {
                    ProfileStore.setDescription(this, oldName, description)
                    refresh()
                    Snackbar.make(
                        snackRoot(),
                        getString(R.string.profile_saved, oldName),
                        Snackbar.LENGTH_SHORT
                    ).show()
                    null
                }
                ProfileStore.renameProfile(this, oldName, newName) -> {
                    ProfileStore.setDescription(this, newName, description)
                    refresh()
                    Snackbar.make(
                        snackRoot(),
                        getString(R.string.profile_saved, newName),
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
        initialDescription: String = "",
        helperText: String = getString(R.string.profile_name_helper),
        onSubmit: (String, String) -> String?
    ) {
        val content = layoutInflater.inflate(R.layout.dialog_profile_name, null)
        val tilProfile = content.findViewById<TextInputLayout>(R.id.tilProfile)
        val input = content.findViewById<TextInputEditText>(R.id.etProfile)
        val descriptionInput = content.findViewById<TextInputEditText>(R.id.etProfileDescription)
        val helper = content.findViewById<TextView>(R.id.tvHelper)

        input.setText(initialValue)
        descriptionInput.setText(initialDescription)
        helper.text = helperText
        val currentLength = input.text?.length ?: 0
        input.setSelection(currentLength.coerceAtMost(input.length()))

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
                        val description = descriptionInput.text?.toString()?.trim().orEmpty()
                        val error = onSubmit(name, description)
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
            .setTitle(R.string.action_delete_profile)
            .setMessage(getString(R.string.delete_profile_confirm, name) + "\n\n" + getString(R.string.destructive_cannot_be_undone))
            .setPositiveButton(R.string.delete) { _, _ ->
                ProfileStore.removeProfile(this, name)
                val profiles = ProfileStore.getProfiles(this)
                ProfileStore.setCurrent(this, profiles.firstOrNull() ?: "")
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .showDestructiveAccented()
    }

    private fun duplicateProfile(name: String) {
        if (isProfileLockActive()) {
            showProfileLockedToast()
            return
        }
        val blocked = ProfileStore.getBlockedForProfile(this, name)
        val allowed = ProfileStore.getAllowedForProfile(this, name)
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
            ProfileStore.setAllowedForProfile(this, newName, allowed)
            ProfileStore.setDescription(this, newName, ProfileStore.getDescription(this, name))
            ProfileRuleModeStore.copyProfile(this, name, newName)
            DomainBlockStore.copyProfile(this, name, newName)
            InAppLimitStore.copyProfile(this, name, newName)
            SurfaceLimitStore.copyProfile(this, name, newName)
            InAppRuleStore.copyProfile(this, name, newName)
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
