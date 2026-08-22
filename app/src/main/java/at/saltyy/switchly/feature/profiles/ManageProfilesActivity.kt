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
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.DomainBlockStore
import at.saltyy.switchly.data.prefs.InAppLimitStore
import at.saltyy.switchly.data.prefs.InAppRuleStore
import at.saltyy.switchly.data.prefs.SurfaceLimitStore
import at.saltyy.switchly.data.prefs.ProfileRuleModeStore
import at.saltyy.switchly.data.prefs.WebsiteRuleModeStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.picker.AppPickerActivity
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.attachEditDeleteSwipe
import at.saltyy.switchly.ui.showSwitchlyStatus
import at.saltyy.switchly.ui.dialog.showDestructiveAccented
import at.saltyy.switchly.ui.dialog.SwitchlyDialogOption
import at.saltyy.switchly.ui.dialog.showSwitchlyOptionDialog
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.EditingLockGuard
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class ManageProfilesActivity : AppCompatActivity() {

    private fun isProfileLockActive(): Boolean {
        return EditingLockGuard.isLocked(this)
    }

    private fun snackRoot(): View {
        return findViewById(android.R.id.content) ?: window.decorView
    }

    private fun syncProfileLockUi() {
        val locked = isProfileLockActive()
        findViewById<FloatingActionButton>(R.id.fabAdd)?.apply {
            isEnabled = !locked
            isClickable = !locked
            alpha = if (locked) 0.45f else 1f
        }
        list.alpha = 1f
        if (::adapter.isInitialized && adapter.itemCount > 0) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }
    }

    private lateinit var list: RecyclerView
    private lateinit var adapter: ProfileAdapter
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

        val fabAdd = findViewById<FloatingActionButton>(R.id.fabAdd)
        fabAdd.backgroundTintList = AccentColor.getActiveColor(this)

        list = findViewById(R.id.listProfiles)
        list.layoutManager = LinearLayoutManager(this)
        adapter = ProfileAdapter()
        list.adapter = adapter
        list.attachEditDeleteSwipe(
            canSwipe = { !isProfileLockActive() },
            onEdit = { position -> adapter.itemAt(position)?.let(::showRenameProfileSheet) },
            onDelete = { position -> adapter.itemAt(position)?.let(::deleteProfile) }
        )

        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            if (!isProfileLockActive()) {
                showAddProfileSheet()
            }
        }

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
        adapter.submit(sorted)
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

        val actions = mutableListOf<ProfileAction>()

        if (name == current) {
            actions += ProfileAction(
                title = getString(R.string.action_manage_selected_apps),
                summary = getString(R.string.profile_action_apps_summary),
                icon = R.drawable.apps_24,
                perform = { openAppListForProfile(name) }
            )
        }

        actions += listOf(
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
        val intent = Intent(this, AppPickerActivity::class.java).apply {
            putExtra(AppPickerActivity.EXTRA_PROFILE_NAME, profile)
        }
        startActivity(intent)
    }

    private fun showAddProfileSheet() {
        if (isProfileLockActive()) {
            return
        }

        showProfileNameDialog(
            title = getString(R.string.add_profile),
            positiveText = getString(R.string.create),
            initialValue = "",
            initialDescription = ""
        ) { name, description ->
            if (ProfileStore.addProfile(this, name)) {
                ProfileStore.setDescription(this, name, description)
                ProfileStore.setCurrent(this, name)
                refresh()
                snackRoot().showSwitchlyStatus(getString(R.string.profile_created_open_apps, name))
                snackRoot().post { openAppListForProfile(name) }
                null
            } else {
                getString(R.string.profile_name_exists, name)
            }
        }
    }

    private fun showRenameProfileSheet(oldName: String) {
        if (isProfileLockActive()) {
            return
        }

        val oldDescription = ProfileStore.getDescription(this, oldName)
        showProfileNameDialog(
            title = getString(R.string.profile_details_title),
            positiveText = getString(R.string.save),
            initialValue = oldName,
            initialDescription = oldDescription
        ) { newName, description ->
            when {
                newName != oldName && ProfileStore.getProfiles(this).contains(newName) -> {
                    getString(R.string.profile_name_exists, newName)
                }
                newName == oldName -> {
                    ProfileStore.setDescription(this, oldName, description)
                    refresh()
                    snackRoot().showSwitchlyStatus(getString(R.string.profile_saved, oldName))
                    null
                }
                ProfileStore.renameProfile(this, oldName, newName) -> {
                    ProfileStore.setDescription(this, newName, description)
                    refresh()
                    snackRoot().showSwitchlyStatus(getString(R.string.profile_saved, newName))
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
        onSubmit: (String, String) -> String?
    ) {
        val content = layoutInflater.inflate(R.layout.dialog_profile_name, FrameLayout(this), false)
        val tilProfile = content.findViewById<TextInputLayout>(R.id.tilProfile)
        val input = content.findViewById<TextInputEditText>(R.id.etProfile)
        val descriptionInput = content.findViewById<TextInputEditText>(R.id.etProfileDescription)
        input.setText(initialValue)
        descriptionInput.setText(initialDescription)
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
                if (isProfileLockActive()) {
                    dialog.dismiss()
                    syncProfileLockUi()
                    return@setOnClickListener
                }
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
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete_profile)
            .setMessage(getString(R.string.delete_profile_confirm, name) + "\n\n" + getString(R.string.destructive_cannot_be_undone))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (isProfileLockActive()) {
                    syncProfileLockUi()
                    return@setPositiveButton
                }
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
            WebsiteRuleModeStore.copyProfile(this, name, newName)
            DomainBlockStore.copyProfile(this, name, newName)
            InAppLimitStore.copyProfile(this, name, newName)
            SurfaceLimitStore.copyProfile(this, name, newName)
            InAppRuleStore.copyProfile(this, name, newName)
            snackRoot().showSwitchlyStatus(getString(R.string.profile_duplicated, newName))
            refresh()
        } else {
            snackRoot().showSwitchlyStatus(getString(R.string.profile_name_exists, newName))
        }
    }

    private fun setActiveProfile(name: String) {
        if (isProfileLockActive()) {
            return
        }

        ProfileStore.setCurrent(this, name)
        snackRoot().showSwitchlyStatus(getString(R.string.profile_set_active_toast, name))
        refresh()
    }

    private inner class ProfileAdapter : RecyclerView.Adapter<ProfileAdapter.VH>() {
        private val items = mutableListOf<String>()

        fun submit(profiles: List<String>) {
            // The active badge, stroke and active bar depend on ProfileStore.getCurrent(), not only on the profile name.
            // Rebind visible rows with specific adapter events so profile changes update immediately without invalidating the full RecyclerView.
            val oldSize = items.size
            val newItems = profiles.toList()
            items.clear()
            items.addAll(newItems)

            val commonSize = minOf(oldSize, newItems.size)
            if (commonSize > 0) {
                notifyItemRangeChanged(0, commonSize)
            }
            if (newItems.size > oldSize) {
                notifyItemRangeInserted(oldSize, newItems.size - oldSize)
            } else if (oldSize > newItems.size) {
                notifyItemRangeRemoved(newItems.size, oldSize - newItems.size)
            }
        }

        fun itemAt(position: Int): String? = items.getOrNull(position)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(layoutInflater.inflate(R.layout.row_profile_item, parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val tv: TextView = view.findViewById(R.id.tvName)
            private val subtitle: TextView = view.findViewById(R.id.tvSubtitle)
            private val badge: TextView = view.findViewById(R.id.tvActiveBadge)
            private val card: MaterialCardView = view.findViewById(R.id.cardProfile)
            private val activeBar: View = view.findViewById(R.id.viewActiveBar)

            fun bind(name: String) {
                val modeLabel = if (ProfileRuleModeStore.isAllowMode(this@ManageProfilesActivity, name)) {
                    getString(R.string.profile_rule_mode_allow)
                } else {
                    getString(R.string.profile_rule_mode_block)
                }
                tv.text = getString(R.string.profile_name_mode_format, name, modeLabel)
                subtitle.text = ProfileStore.getDescription(this@ManageProfilesActivity, name)
                    .ifBlank { getString(R.string.profile_row_subtitle) }

                val isActive = name == ProfileStore.getCurrent(this@ManageProfilesActivity)
                badge.visibility = if (isActive) View.VISIBLE else View.GONE
                activeBar.visibility = if (isActive) View.VISIBLE else View.GONE
                val accent = AccentColor.getAccentColorInt(this@ManageProfilesActivity)
                card.strokeWidth = if (isActive) dp(2) else dp(1)
                card.strokeColor = if (isActive) accent else Color.TRANSPARENT
                val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.5) Color.BLACK else Color.WHITE
                badge.setTextColor(onAccent)
                badge.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(accent)
                    cornerRadius = dp(999).toFloat()
                }
                val readOnly = isProfileLockActive()
                itemView.alpha = if (readOnly) 0.82f else 1f
                itemView.isClickable = !readOnly
                itemView.setOnClickListener {
                    if (isProfileLockActive()) {
                        return@setOnClickListener
                    }
                    val currentPosition = bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) showActions(currentPosition)
                }
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt().coerceAtLeast(1)
}
