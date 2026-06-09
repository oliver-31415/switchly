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

package at.saltyy.switchly.feature.picker

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AttemptLimitStore
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SessionLimitStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.UsageLimitStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.feature.usage.QuickLimitDialogs
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.util.ActivityTransitionCompat
import at.saltyy.switchly.util.AppBlockSafety
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.SwitchlyAppAccessGuard
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

class AppPickerActivity : AppCompatActivity() {

    private lateinit var adapter: AppListAdapter
    private var currentProfile: String? = null
    private var autoBlockNewAppsCheckbox: CheckBox? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        if (SwitchlyAppAccessGuard.blockIfLocked(this)) return
        setContentView(R.layout.activity_app_picker)
        CustomAccentApplier.applyIfNeeded(this)

        val enabled = SwitchModeStore.isEnabled(this)
        val nfcLocked = enabled && SwitchModeStore.isNfcRequiredForDisable(this)
        if (nfcLocked) {
            Toast.makeText(
                this,
                getString(R.string.toast_cannot_change_profile_while_locked),
                Toast.LENGTH_SHORT
            ).show()
            finish()
            return
        }

        if (enabled && !AutomationModeStore.isAppPickerAllowedWhileEnabled(this)) {
            Toast.makeText(
                this,
                getString(R.string.toast_disable_switchly_to_edit_blocked_apps),
                Toast.LENGTH_SHORT
            ).show()
            finish()
            return
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        val rvApps = findViewById<RecyclerView>(R.id.rvApps)
        val searchBox = findViewById<TextInputLayout>(R.id.searchBox)
        val etSearch = findViewById<TextInputEditText>(R.id.etSearch)
        val btnSelectAll = findViewById<MaterialButton>(R.id.btnSelectAll)
        val btnClearAll = findViewById<MaterialButton>(R.id.btnClearAll)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val cbAutoBlockNewApps = findViewById<CheckBox>(R.id.cbAutoBlockNewApps)
        autoBlockNewAppsCheckbox = cbAutoBlockNewApps

        rvApps.layoutManager = LinearLayoutManager(this)

        currentProfile = ProfileStore.getCurrent(this)

        val preselectedManaged: Set<String> = if (!currentProfile.isNullOrEmpty()) {
            buildSet {
                val profile = currentProfile!!
                addAll(ProfileStore.getBlockedForProfile(this@AppPickerActivity, profile))
                addAll(UsageLimitStore.getAllLimitedPackages(this@AppPickerActivity, profile))
                addAll(SessionLimitStore.getAllLimitedPackages(this@AppPickerActivity, profile))
                addAll(AttemptLimitStore.getAllLimitedPackages(this@AppPickerActivity, profile))
            }
        } else {
            emptySet()
        }

        adapter = AppListAdapter(
            allApps = emptyList(),
            preselectedManaged = AppBlockSafety.sanitizeManagedPackages(this, preselectedManaged),
            currentProfileProvider = { currentProfile },
            onSetLimitClicked = { app ->
                QuickLimitDialogs.showForApp(
                    activity = this,
                    pkg = app.packageName,
                    label = app.label
                ) {
                    adapter.notifyPkgChanged(app.packageName)
                }
            },
            onSetSessionLimitClicked = { app -> showSessionLimitDialog(app) }
        )
        rvApps.adapter = adapter

        btnSave.backgroundTintList = AccentColor.getActiveColor(this)
        btnSave.setTextColor(ContextCompat.getColor(this, R.color.font_white))
        btnSelectAll.strokeColor = AccentColor.getActiveColor(this)
        btnClearAll.strokeColor = AccentColor.getActiveColor(this)
        btnSelectAll.setTextColor(AccentColor.getAccentColorInt(this))
        btnClearAll.setTextColor(AccentColor.getAccentColorInt(this))
        searchBox.boxStrokeColor = AccentColor.getAccentColorInt(this)
        searchBox.hintTextColor = AccentColor.getActiveColor(this)
        etSearch.backgroundTintList = AccentColor.getActiveColor(this)

        setupAutoBlockNewAppsCheckbox(cbAutoBlockNewApps)
        setupBulkButtons(btnSelectAll, btnClearAll, btnSave)
        updateClearButtonLabel(btnClearAll)

        Thread {
            val load = loadPickerEntries(this, preselectedManaged)
            if (isFinishing || isDestroyed) return@Thread

            runOnUiThread {
                val sanitizedPreselected = AppBlockSafety.sanitizeManagedPackages(this, preselectedManaged)
                val removedProtectedCount = (preselectedManaged - sanitizedPreselected).size

                adapter = AppListAdapter(
                    allApps = load.entries,
                    preselectedManaged = sanitizedPreselected,
                    currentProfileProvider = { currentProfile },
                    onSetLimitClicked = { app ->
                        QuickLimitDialogs.showForApp(
                            activity = this,
                            pkg = app.packageName,
                            label = app.label
                        ) {
                            adapter.notifyPkgChanged(app.packageName)
                        }
                    },
                    onSetSessionLimitClicked = { app -> showSessionLimitDialog(app) }
                )
                rvApps.adapter = adapter

                setupSearch(etSearch)
                setupSaveButton(btnSave)

                if (removedProtectedCount > 0) {
                    showPickerNotice(
                        btnSave,
                        resources.getQuantityString(
                            R.plurals.app_picker_removed_protected_notice,
                            removedProtectedCount,
                            removedProtectedCount
                        )
                    )
                }

                if (load.unavailableCount > 0) {
                    updateClearButtonLabel(btnClearAll)
                    showPickerNotice(
                        btnSave,
                        resources.getQuantityString(
                            R.plurals.unavailable_apps_loaded_notice,
                            load.unavailableCount,
                            load.unavailableCount
                        )
                    )
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (SwitchlyAppAccessGuard.blockIfLocked(this)) return
    }

    override fun finish() {
        super.finish()
        ActivityTransitionCompat.finishWithoutAnimation(this)
    }

    private data class PickerLoadResult(
        val entries: List<AppEntry>,
        val unavailableCount: Int
    )

    private fun loadPickerEntries(context: Context, preselectedManaged: Set<String>): PickerLoadResult {
        val installed = loadLaunchableApps(context)
        if (preselectedManaged.isEmpty()) {
            return PickerLoadResult(entries = installed, unavailableCount = 0)
        }

        val installedByPackage = installed.associateBy { it.packageName }
        val selectedNotInLauncher = mutableListOf<AppEntry>()
        val unavailable = mutableListOf<AppEntry>()
        val packageListLooksIncomplete = installed.size < 50 &&
            preselectedManaged.size >= 50 &&
            preselectedManaged.size > installed.size

        preselectedManaged
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .forEach { pkg ->
                if (pkg in installedByPackage) return@forEach

                val installedEntry = resolveInstalledPackageEntry(context, pkg)
                if (installedEntry != null) {
                    selectedNotInLauncher += installedEntry
                } else if (packageListLooksIncomplete) {
                    // If Android/Samsung only returns a very small visible app list while the profile already contains many selected packages, avoid treating those packages as uninstalled. 
                    // Some devices limit package visibility and would otherwise mark real installed apps as unavailable and remove them in bulk.
                    selectedNotInLauncher += AppEntry(
                        packageName = pkg,
                        label = pkg,
                        isAvailable = true,
                        blockSafety = AppBlockSafety.resolve(context, pkg)
                    )
                } else {
                    unavailable += AppEntry(
                        packageName = pkg,
                        label = context.getString(R.string.unavailable_app_label),
                        isAvailable = false
                    )
                }
            }

        val entries = (unavailable + selectedNotInLauncher + installed)
            .distinctBy { it.packageName }

        return PickerLoadResult(entries = entries, unavailableCount = unavailable.size)
    }

    private fun loadLaunchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val byPackage = linkedMapOf<String, AppEntry>()

        fun addEntryFromApplicationInfo(ai: ApplicationInfo?) {
            if (ai == null) return
            val pkg = ai.packageName?.takeIf { it.isNotBlank() } ?: return
            if (pkg == context.packageName) return
            if (pkg in byPackage) return

            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: pkg

            byPackage[pkg] = AppEntry(
                label = label,
                packageName = pkg,
                blockSafety = AppBlockSafety.resolve(context, pkg)
            )
        }

        listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        ).forEach { launcherIntent ->
            runCatching { pm.queryIntentActivities(launcherIntent, 0) }
                .getOrDefault(emptyList())
                .forEach { ri -> addEntryFromApplicationInfo(ri.activityInfo?.applicationInfo) }
        }

        // Keep the base list limited to Android's launcher query.
        // Selected packages are resolved individually below via getApplicationInfoCompat(), so installed apps are not marked unavailable just because a Samsung/Android launcher query returned an incomplete list.

        return byPackage.values.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun resolveInstalledPackageEntry(context: Context, packageName: String): AppEntry? {
        val pm = context.packageManager
        val ai = runCatching { getApplicationInfoCompat(pm, packageName) }.getOrNull() ?: return null
        val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: packageName

        return AppEntry(
            label = label,
            packageName = packageName,
            isAvailable = true,
            blockSafety = AppBlockSafety.resolve(context, packageName)
        )
    }

    private fun getApplicationInfoCompat(pm: PackageManager, packageName: String): ApplicationInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            pm.getApplicationInfo(packageName, 0)
        }
    }

    private fun setupSearch(etSearch: TextInputEditText) {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString())
            }
        })

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                etSearch.clearFocus()
                true
            } else {
                false
            }
        }
    }

    private fun setupAutoBlockNewAppsCheckbox(cbAutoBlockNewApps: CheckBox) {
        val profile = currentProfile
        cbAutoBlockNewApps.isEnabled = !profile.isNullOrBlank()
        cbAutoBlockNewApps.isChecked = profile?.let { ProfileStore.isAutoBlockNewAppsEnabled(this, it) } ?: false
        cbAutoBlockNewApps.setOnCheckedChangeListener { _, isChecked ->
            val activeProfile = currentProfile
            if (activeProfile.isNullOrBlank()) return@setOnCheckedChangeListener
            ProfileStore.setAutoBlockNewAppsEnabled(this, activeProfile, isChecked)
            if (isChecked) {
                ProfileStore.setAutoBlockKnownPackages(this, activeProfile, ProfileStore.getLaunchablePackages(this))
            }
        }
    }

    private fun setupBulkButtons(btnSelectAll: MaterialButton, btnClearAll: MaterialButton, btnSave: Button) {
        btnSelectAll.setOnClickListener {
            val skipped = adapter.selectAllVisible()
            val activeProfile = currentProfile
            if (!activeProfile.isNullOrBlank()) {
                ProfileStore.setAutoBlockNewAppsEnabled(this, activeProfile, true)
                ProfileStore.setAutoBlockKnownPackages(this, activeProfile, ProfileStore.getLaunchablePackages(this))
                autoBlockNewAppsCheckbox?.isChecked = true
            }
            if (skipped > 0) {
                Toast.makeText(
                    this,
                    resources.getQuantityString(R.plurals.app_picker_select_all_skipped_notice, skipped, skipped),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        btnClearAll.setOnClickListener {
            val unavailableCount = adapter.unavailableManagedCount()
            if (unavailableCount > 0) {
                val removed = adapter.clearUnavailable(this)
                updateClearButtonLabel(btnClearAll)
                if (removed > 0) {
                    showPickerNotice(
                        btnSave,
                        resources.getQuantityString(
                            R.plurals.unavailable_apps_cleared_notice,
                            removed,
                            removed
                        )
                    )
                }
            } else {
                adapter.clearAllVisible(this)
            }
        }
    }

    private fun updateClearButtonLabel(btnClearAll: MaterialButton) {
        btnClearAll.setText(
            if (::adapter.isInitialized && adapter.unavailableManagedCount() > 0) {
                R.string.app_picker_clear_unavailable
            } else {
                R.string.app_picker_clear_all
            }
        )
    }

    private fun showPickerNotice(anchor: View, message: CharSequence) {
        Snackbar.make(anchor, message, Snackbar.LENGTH_LONG)
            .setAnchorView(anchor)
            .show()
    }

    private fun setupSaveButton(btnSave: Button) {
        btnSave.setOnClickListener {
            val profile = currentProfile
            if (profile.isNullOrEmpty()) {
                Toast.makeText(this, R.string.select_profile_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val managed = AppBlockSafety.sanitizeManagedPackages(this, adapter.getManagedPackages())
            ProfileStore.setBlockedForProfile(this, profile, managed)

            BlockingRuntime.ensureRunning(this)
            finish()
        }
    }

    private fun ensureAppCanBeManaged(app: AppEntry, onAllowed: () -> Unit) {
        when (app.blockSafety.level) {
            AppBlockSafety.Level.HARD_EXCLUDED -> {
                Toast.makeText(
                    this,
                    app.blockSafety.hint ?: getString(R.string.app_picker_protected_generic_hint),
                    Toast.LENGTH_LONG
                ).show()
            }

            AppBlockSafety.Level.SOFT_WARNING -> {
                if (AppBlockSafety.requiresStrictModeForBlocking(this, app.packageName)) {
                    if (!AppBlockSafety.canAllowStrictModeBlocking(this, app.packageName)) {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.app_picker_settings_requirements_title)
                            .setMessage(R.string.app_picker_settings_requirements_message)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(android.R.string.ok) { _, _ -> }
                            .showAccented()
                        return
                    }

                    AlertDialog.Builder(this)
                        .setTitle(app.blockSafety.warningTitle ?: getString(R.string.app_picker_protected_caution_title))
                        .setMessage(app.blockSafety.warningMessage ?: app.blockSafety.hint ?: getString(R.string.app_picker_protected_generic_hint))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.continue_label) { _, _ ->
                            AlertDialog.Builder(this)
                                .setTitle(R.string.app_picker_settings_second_warning_title)
                                .setMessage(R.string.app_picker_settings_second_warning_message)
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.app_picker_block_settings_confirm) { _, _ -> onAllowed() }
                                .showAccented()
                        }
                        .showAccented()
                    return
                }

                AlertDialog.Builder(this)
                    .setTitle(app.blockSafety.warningTitle ?: getString(R.string.app_picker_protected_caution_title))
                    .setMessage(app.blockSafety.warningMessage ?: app.blockSafety.hint ?: getString(R.string.app_picker_protected_generic_hint))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.continue_label) { _, _ -> onAllowed() }
                    .showAccented()
            }

            else -> onAllowed()
        }
    }

    private fun showSessionLimitDialog(app: AppEntry) {
        ensureAppCanBeManaged(app) {
            val profile = currentProfile
            if (profile.isNullOrBlank()) {
                Toast.makeText(this, R.string.select_profile_first, Toast.LENGTH_SHORT).show()
                return@ensureAppCanBeManaged
            }

            if (SwitchModeStore.isEnabled(this)) {
                Toast.makeText(this, R.string.toast_disable_switchly_to_edit_app_limits, Toast.LENGTH_SHORT).show()
                return@ensureAppCanBeManaged
            }

            val presets = listOf(0, 3, 5, 10, 15, 30, 45, 60, 90, 120)
            val labels = presets.map { m ->
                if (m == 0) {
                    getString(R.string.no_limit)
                } else {
                    resources.getQuantityString(R.plurals.minutes_format, m, m)
                }
            } + getString(R.string.custom_minutes)

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.set_session_limit_title, app.label))
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which < presets.size) {
                        val chosen = presets[which]
                        SessionLimitStore.setLimitMinutes(this, profile, app.packageName, chosen)

                        BlockingRuntime.ensureRunning(this)
                        adapter.notifyPkgChanged(app.packageName)
                    } else {
                        showCustomSessionMinutesInput(app)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .showAccented()
        }
    }

    private fun showCustomSessionMinutesInput(app: AppEntry) {
        ensureAppCanBeManaged(app) {
            val profile = currentProfile ?: return@ensureAppCanBeManaged

            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = getString(R.string.minutes_hint)
                backgroundTintList = AccentColor.getActiveColor(context)
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                addView(
                    input,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }

            AlertDialog.Builder(this)
                .setTitle(R.string.custom_minutes_title)
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val m = input.text?.toString()?.trim()?.toIntOrNull()
                    if (m == null || m < 0) {
                        Toast.makeText(this, R.string.invalid_value, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    if (SwitchModeStore.isEnabled(this)) {
                        Toast.makeText(this, R.string.toast_disable_switchly_to_edit_app_limits, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    SessionLimitStore.setLimitMinutes(this, profile, app.packageName, m)

                    BlockingRuntime.ensureRunning(this)
                    adapter.notifyPkgChanged(app.packageName)
                }
                .showAccented()
        }
    }
}
