package at.saltyy.switchly.feature.settings

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import at.saltyy.switchly.auth.AccountDeletion
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.SchedulePlanner
import at.saltyy.switchly.feature.schedule.SchedulesActivity
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.sync.CloudSyncRuntime
import at.saltyy.switchly.feature.about.WhatsNewActivity
import at.saltyy.switchly.feature.about.AppInfoActivity
import at.saltyy.switchly.feature.about.DeviceInfoActivity
import at.saltyy.switchly.feature.about.OtherSwitchlyProductsActivity
import at.saltyy.switchly.feature.about.DeveloperInfoActivity
import at.saltyy.switchly.feature.faq.FaqActivity
import at.saltyy.switchly.feature.inbox.BlockedInboxActivity
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.feature.premium.PremiumInfoActivity
import at.saltyy.switchly.feature.profiles.ManageProfilesActivity
import at.saltyy.switchly.feature.support.SupportActivity
import at.saltyy.switchly.nfc.NfcWriterActivity
import at.saltyy.switchly.feature.settings.ManagePairedTagsActivity
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.PlayStoreUpdatePrompt
import com.google.android.material.color.MaterialColors
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class SettingsFragment : PreferenceFragmentCompat() {

    private fun openToggleOptions(section: String? = null) {
        val intent = Intent(requireContext(), ToggleOptionsActivity::class.java)
        if (!section.isNullOrBlank()) {
            intent.putExtra(ToggleOptionsActivity.EXTRA_SCROLL_TO_SECTION, section)
        }
        startActivity(intent)
    }

    fun currentScreenTitle(): String {
        val t = preferenceScreen?.title?.toString()
        return if (!t.isNullOrBlank()) t else getString(R.string.settings)
    }

    fun scrollToTop() {
        listView?.post {
            runCatching {
                listView?.smoothScrollToPosition(0)
            }
        }
    }

    private val categoryTitles = mutableSetOf<String>()
    private var devVisible: Boolean = false
    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var nextChangedReceiver: BroadcastReceiver? = null
    private var lastNestedNavKey: String? = null
    private var lastNestedNavAtMs: Long = 0L

    private data class IconActionItem(val title: String, val iconRes: Int)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // If we are navigating into a nested PreferenceScreen (Help/Account/...), we pass the target root via fragment arguments.
        val effectiveRoot = rootKey ?: arguments?.getString(ARG_PREFERENCE_ROOT)
        setPreferencesFromResource(R.xml.preferences_settings, effectiveRoot)

        val ctx = requireContext()
        // App-scoped prefs used by SettingsFragment
        val appPrefs = ctx.getSharedPreferences(PREFS, 0)
        devVisible = appPrefs.getBoolean(KEY_DEV_UNLOCKED, false)

        tintCategories()
        ensureDeveloperInfoIconAccent()
        // Extra pass to prevent occasional fallback to default accent in Customize.
        requireActivity().window?.decorView?.post {
            runCatching {
                CustomAccentApplier.applyIfNeeded(requireActivity())
                tintCategories()
                ensureDeveloperInfoIconAccent()
                tintCategoryViewsInList()
            }
        }

        // Hidden master toggle (dev-only)
        findPreference<SwitchPreferenceCompat>("pref_switch_mode")?.apply {
            isVisible = devVisible
            isChecked = SwitchModeStore.isEnabled(ctx)

            setOnPreferenceClickListener {
                val enabled = SwitchModeStore.isEnabled(ctx)
                val requireNfc = SwitchModeStore.isNfcRequiredForDisable(ctx)
                val locked = enabled && requireNfc
                if (locked) {
                    Toast.makeText(ctx, getString(R.string.toast_disable_requires_nfc), Toast.LENGTH_SHORT).show()
                }
                false
            }

            setOnPreferenceChangeListener { _, new ->
                val target = new as Boolean
                val currentlyEnabled = SwitchModeStore.isEnabled(ctx)
                val requireNfc = SwitchModeStore.isNfcRequiredForDisable(ctx)

                val locked = currentlyEnabled && requireNfc && !target
                if (locked) {
                    Toast.makeText(ctx, getString(R.string.toast_disable_requires_nfc), Toast.LENGTH_SHORT).show()
                    false
                } else {
                    SwitchModeStore.setEnabled(ctx, target)
                    refreshLockUi()
                    true
                }
            }
        }

        // Master toggle (visible)
        findPreference<SwitchPreferenceCompat>("pref_switchly_enabled")?.apply {
            isChecked = SwitchModeStore.isEnabled(ctx)

            setOnPreferenceClickListener {
                val enabled = SwitchModeStore.isEnabled(ctx)
                val requireNfc = SwitchModeStore.isNfcRequiredForDisable(ctx)
                val locked = enabled && requireNfc
                if (locked) {
                    Toast.makeText(ctx, getString(R.string.toast_disable_requires_nfc), Toast.LENGTH_SHORT).show()
                }
                false
            }

            setOnPreferenceChangeListener { _, new ->
                val target = new as Boolean
                val currentlyEnabled = SwitchModeStore.isEnabled(ctx)
                val requireNfc = SwitchModeStore.isNfcRequiredForDisable(ctx)

                val locked = currentlyEnabled && requireNfc && !target
                if (locked) {
                    Toast.makeText(ctx, getString(R.string.toast_disable_requires_nfc), Toast.LENGTH_SHORT).show()
                    false
                } else {
                    SwitchModeStore.setEnabled(ctx, target)
                    refreshLockUi()
                    true
                }
            }
        }

        // Language
        findPreference<Preference>("pref_language")?.apply {
            updateLanguageSummary(this)
            setOnPreferenceClickListener {
                showLanguageDialog()
                true
            }
        }

        // Appearance (Display mode + Theme color)
        findPreference<Preference>("pref_theme_mode")?.apply {
            updateThemeModeSummary(this)
            setOnPreferenceClickListener {
                showThemeModeDialog()
                true
            }
        }

        findPreference<Preference>("pref_theme_color")?.apply {
            updateThemeColorSummary(this)
            setOnPreferenceClickListener {
                showThemeColorDialog()
                true
            }
        }

        // Manage profiles
        findPreference<Preference>("pref_manage_profiles")?.apply {
            isVisible = true
            setOnPreferenceClickListener {
                val ctx = requireContext()
                // Use base enabled state so schedule/temporary windows do not make
                // profile management appear to randomly lock or unlock.
                val enabled = SwitchModeStore.isBaseEnabled(ctx)
                val requireNfc = SwitchModeStore.isNfcRequiredForDisable(ctx)
                val mixedProfileSwitchAllowed = AutomationModeStore.isProfileSwitchingAllowedWhileEnabled(ctx)
                if (enabled && (requireNfc || !mixedProfileSwitchAllowed)) {
                    val msgRes = if (requireNfc) {
                        R.string.toast_cannot_change_profile_while_locked
                    } else {
                        R.string.toast_disable_switchly_to_switch_profiles
                    }
                    Toast.makeText(ctx, getString(msgRes), Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceClickListener true
                }
                startActivity(Intent(requireContext(), ManageProfilesActivity::class.java))
                true
            }
        }

        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val pairedUiEnabled = defaultPrefs.getBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, false)

        // NFC tag writer (should always be available; pairing is just one optional action)
        findPreference<Preference>("pref_write_tag")?.apply {
            isVisible = true
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), NfcWriterActivity::class.java))
                true
            }
        }

        // Manage paired NFC tags (UID list) (only shown when feature is enabled)
        findPreference<Preference>("pref_manage_paired_tags")?.apply {
            isVisible = pairedUiEnabled
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), ManagePairedTagsActivity::class.java))
                true
            }
        }

        // Emergency unlock
        findPreference<Preference>("pref_emergency_unlock")?.setOnPreferenceClickListener {
            showEmergencyUnlockWithPin()
            true
        }

        // Permissions overview
        findPreference<Preference>("pref_permissions")?.setOnPreferenceClickListener {
            val enabled = SwitchModeStore.isEnabled(requireContext())
            val requireNfc = SwitchModeStore.isNfcRequiredForDisable(requireContext())
            val locked = enabled && requireNfc
            if (locked) {
                Toast.makeText(requireContext(), getString(R.string.toast_disable_requires_nfc), Toast.LENGTH_SHORT).show()
                return@setOnPreferenceClickListener true
            }

            startActivity(Intent(requireContext(), PermissionsActivity::class.java))
            true
        }

        // Toggle controls: flattened entry point (single page)
        findPreference<Preference>("pref_toggle_options")?.setOnPreferenceClickListener {
            openToggleOptions()
            true
        }
        findPreference<Preference>("pref_toggle_options_blocking")?.setOnPreferenceClickListener {
            openToggleOptions()
            true
        }

        // Troubleshooting
        findPreference<Preference>("pref_troubleshooting")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), TroubleshootingActivity::class.java))
            true
        }
        
        // Manage blocked websites (domain blocking list)
        findPreference<Preference>("pref_manage_blocked_websites")?.apply {
            isVisible = true
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), ManageBlockedWebsitesActivity::class.java))
                true
            }
        }

        // In-App Blocking
        findPreference<Preference>("pref_in_app_blocking")?.apply {
            isVisible = true
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), InAppBlockingActivity::class.java))
                true
            }
        }

        // Blocked notifications inbox
        findPreference<Preference>("pref_blocked_inbox")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), BlockedInboxActivity::class.java))
            true
        }

        // Open schedules (Customize -> Schedules)
        findPreference<Preference>("pref_open_schedules")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), SchedulesActivity::class.java))
            true
        }

        // Help -> Other help
        findPreference<Preference>("pref_other_help_battery")?.setOnPreferenceClickListener {
            // Open system screen where the user can allow Switchly to ignore battery optimizations.
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure {
                // Fallback: App details
                val uri = android.net.Uri.fromParts("package", requireContext().packageName, null)
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
            }
            true
        }

        findPreference<Preference>("pref_other_help_contact")?.setOnPreferenceClickListener {
            // Dedicated support screen with a single email action
            startActivity(Intent(requireContext(), SupportActivity::class.java))
            true
        }

        // About sub pages
        findPreference<Preference>("pref_about_app_info")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), AppInfoActivity::class.java))
            true
        }
        findPreference<Preference>("pref_about_device_info")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), DeviceInfoActivity::class.java))
            true
        }
        findPreference<Preference>("pref_about_developer_info")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), DeveloperInfoActivity::class.java))
            true
        }
        findPreference<Preference>("pref_about_other_switchly_products")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), OtherSwitchlyProductsActivity::class.java))
            true
        }

        // What's new
        findPreference<Preference>("pref_changelog")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), WhatsNewActivity::class.java))
            true
        }

        // FAQ
        findPreference<Preference>("pref_faq")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), FaqActivity::class.java))
            true
        }

        // Google account (popup only for sign-in/out/delete)
        findPreference<Preference>("pref_google_account")?.setOnPreferenceClickListener {
            showGoogleAccountDialog()
            true
        }

        // Emergency unlock PIN (Account)
        findPreference<Preference>("pref_change_emergency_pin")?.setOnPreferenceClickListener {
            showChangeEmergencyPinFlow()
            true
        }

        // Backup as standalone prefs
        findPreference<Preference>("pref_cloud_backup")?.apply {
            setOnPreferenceClickListener {
                confirmAction(
                    title = getString(R.string.settings_confirm_backup_title),
                    message = getString(R.string.settings_confirm_backup_message),
                    positiveText = getString(R.string.settings_confirm_backup_title),
                ) {
                    CloudSyncRuntime.pushLocalState(requireContext()) { ok, err ->
                        val c = requireContext()
                        val msg = if (ok) {
                            PreferenceManager.getDefaultSharedPreferences(c).edit {
                                putLong("pref_last_backup_epoch_ms", System.currentTimeMillis())
                            }
                            updateGooglePrefSummary()
                            updateCloudPrefVisibility()
                            getString(R.string.cloud_backup_ok)
                        } else {
                            getString(R.string.cloud_error_fmt, err ?: getString(R.string.error_unknown))
                        }
                        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
        }

        // Restore as standalone prefs
        findPreference<Preference>("pref_cloud_restore")?.apply {
            setOnPreferenceClickListener {
                confirmAction(
                    title = getString(R.string.settings_confirm_restore_title),
                    message = getString(R.string.settings_confirm_restore_message),
                    positiveText = getString(R.string.settings_confirm_restore_title),
                ) {
                    startRestoreFlowWithChoice()
                }
                true
            }
        }

        // Delete backups
        findPreference<Preference>("pref_cloud_delete_backups")?.apply {
            setOnPreferenceClickListener {
                showDeleteBackupsDialog()
                true
            }
        }

        // Local in-app reset (clear ALL app data)
        findPreference<Preference>("pref_reset_app_data")?.setOnPreferenceClickListener {
            showResetAllDataDialog()
            true
        }

        // Premium
        findPreference<Preference>("pref_premium_upgrade")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), PremiumInfoActivity::class.java))
            true
        }

        // Tutorial 
        findPreference<Preference>("pref_tutorial")
            ?.setOnPreferenceClickListener {
                startActivity(
                    Intent(requireContext(), at.saltyy.switchly.feature.onboarding.OnboardingActivity::class.java)
                        .putExtra(at.saltyy.switchly.feature.onboarding.OnboardingActivity.EXTRA_FORCE, true)
                )
                true
            }

        // Initial UI state
        updateGooglePrefSummary()
        updateCloudPrefVisibility()
        refreshEmergencyPref()
        refreshLockUi()

        // Live updates from SwitchModeStore
        SwitchModeStore.ensureInit(ctx)
        lifecycleScope.launch {
            SwitchModeStore.enabledFlow.collect {
                val enabledNow = SwitchModeStore.isEnabled(ctx)
                findPreference<SwitchPreferenceCompat>("pref_switch_mode")?.isChecked = enabledNow
                findPreference<SwitchPreferenceCompat>("pref_switchly_enabled")?.isChecked = enabledNow
                refreshLockUi()
            }
        }

        // Auth listener for Google account
        authListener = FirebaseAuth.AuthStateListener {
            if (isAdded) {
                updateGooglePrefSummary()
                updateCloudPrefVisibility()
            }
        }
        // Firebase can be missing during dev builds (e.g. no google-services.json).
        // Don't crash Settings screen if Firebase isn't initialized.
        authListener?.let { listener ->
            runCatching { FirebaseAuth.getInstance().addAuthStateListener(listener) }
        }
    }

    /**
     * Navigate nested PreferenceScreens (Help/Account/...). Some setups do not automatically open nested screens, so we handle it explicitly.
     */
    private fun openNestedPreferenceScreen(screenKey: String): Boolean {
        if (screenKey.isBlank()) return false

        // Some AndroidX/device combinations can dispatch both callbacks for one tap.
        // Debounce identical navigation to avoid double back-stack entries ("back needs 2 taps").
        val now = SystemClock.uptimeMillis()
        if (lastNestedNavKey == screenKey && (now - lastNestedNavAtMs) < 650L) {
            return true
        }
        lastNestedNavKey = screenKey
        lastNestedNavAtMs = now

        val currentRoot = arguments?.getString(ARG_PREFERENCE_ROOT)
        if (currentRoot == screenKey) return true

        val fragment = SettingsFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PREFERENCE_ROOT, screenKey)
            }
        }

        parentFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(screenKey)
            .commit()

        return true
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference is PreferenceScreen && preference.key?.startsWith("screen_") == true) {
            return openNestedPreferenceScreen(preference.key.orEmpty())
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onNavigateToScreen(preferenceScreen: PreferenceScreen) {
        // Some devices/androidx versions won't trigger onPreferenceTreeClick for PreferenceScreen, but will call onNavigateToScreen. Handle both to be safe.
        if (preferenceScreen.key?.startsWith("screen_") == true) {
            openNestedPreferenceScreen(preferenceScreen.key.orEmpty())
        } else {
            super.onNavigateToScreen(preferenceScreen)
        }
    }

    // Hide/show cloud backup section depending on login state
    private fun updateCloudPrefVisibility() {
        val loggedIn = at.saltyy.switchly.auth.Auth.uid() != null
        findPreference<PreferenceScreen>("screen_backup")?.isVisible = loggedIn
        findPreference<Preference>("pref_cloud_backup")?.isVisible = loggedIn
        findPreference<Preference>("pref_cloud_restore")?.isVisible = loggedIn
        findPreference<Preference>("pref_cloud_delete_backups")?.isVisible = loggedIn
    }

    private fun tintCategoryViewsInList() {
        val list = listView ?: return
        if (categoryTitles.isEmpty()) return
        val accent = getCurrentAccentColor(requireContext())

        fun tintInView(v: View) {
            if (v is TextView) {
                val text = v.text?.toString() ?: return
                if (categoryTitles.contains(text)) v.setTextColor(accent)
            } else if (v is ImageView) {
                val d = v.drawable ?: return
                val wrapped = DrawableCompat.wrap(d).mutate()
                DrawableCompat.setTint(wrapped, accent)
                v.setImageDrawable(wrapped)
            } else if (v is ViewGroup) {
                for (i in 0 until v.childCount) tintInView(v.getChildAt(i))
            }
        }

        for (i in 0 until list.childCount) tintInView(list.getChildAt(i))

        list.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                if (child != null) tintInView(child)
            }

            override fun onChildViewRemoved(parent: View?, child: View?) {}
        })
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = listView ?: return
        // Match the "Statistics Hub" spacing (cards have breathing room)
        val d = resources.displayMetrics.density
        val padH = (16f * d).toInt()
        val padTop = (12f * d).toInt()
        val padBottom = list.paddingBottom
        list.setPadding(padH, padTop, padH, padBottom)
        list.clipToPadding = false
        tintCategoryViewsInList()
        CustomAccentApplier.applyIfNeeded(requireActivity())
    }

    // Next schedule indicator
    private fun updateNextScheduleIndicator() {
        val ctx = requireContext()
        val pref = findPreference<Preference>("pref_schedules_next") ?: return

        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        val show = sp.getBoolean(ToggleOptionsActivity.KEY_SHOW_NEXT_SCHEDULE, false)
        pref.isVisible = show
        if (!show) return

        if (!AutomationModeStore.isScheduleAllowed(ctx)) {
            pref.summary = getString(R.string.schedules_next_inactive_control_mode)
            return
        }

        val nextMillis = SchedulePlanner.getNextBoundaryMillis(ctx)
        if (nextMillis <= 0L) {
            pref.summary = getString(R.string.schedules_next_none)
        } else {
            val text = android.text.format.DateFormat.getTimeFormat(ctx).format(Date(nextMillis))
            pref.summary = getString(R.string.schedules_next_at, text)
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? SettingsActivity)?.setToolbarTitle(currentScreenTitle())
        refreshLockUi()
        refreshEmergencyPref()
        updateNextScheduleIndicator()
        updateGooglePrefSummary()
        updateCloudPrefVisibility()
        CustomAccentApplier.applyIfNeeded(requireActivity())
        tintCategories()
        ensureDeveloperInfoIconAccent()

        val hasNextSchedulePref = findPreference<Preference>("pref_schedules_next") != null
        if (hasNextSchedulePref && nextChangedReceiver == null) {
            nextChangedReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == SchedulePlanner.ACTION_NEXT_CHANGED) {
                        updateNextScheduleIndicator()
                    }
                }
            }

            val filter = IntentFilter(SchedulePlanner.ACTION_NEXT_CHANGED)
            ContextCompat.registerReceiver(
                requireContext(),
                nextChangedReceiver!!,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    override fun onPause() {
        super.onPause()
        nextChangedReceiver?.let { runCatching { requireContext().unregisterReceiver(it) } }
        nextChangedReceiver = null
    }

    override fun onDestroyView() {
        authListener?.let { listener ->
            runCatching { FirebaseAuth.getInstance().removeAuthStateListener(listener) }
        }
        authListener = null

        nextChangedReceiver?.let { runCatching { requireContext().unregisterReceiver(it) } }
        nextChangedReceiver = null

        super.onDestroyView()
    }

    // Language
    private fun updateLanguageSummary(pref: Preference?) {
        pref ?: return
        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val current = prefs.getString("pref_language", "system") ?: "system"

        val entries = resources.getStringArray(R.array.pref_language_entries)
        val values = resources.getStringArray(R.array.pref_language_values)

        val label = values.indexOf(current).let { idx ->
            if (idx in entries.indices) entries[idx] else entries.firstOrNull()
        } ?: ""

        pref.summary = label
    }

    // Appearance summaries
    private fun updateThemeModeSummary(pref: Preference?) {
        pref ?: return
        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val current = prefs.getString("pref_theme_mode", "system") ?: "system"

        val label = when (current) {
            "light" -> getString(R.string.pref_theme_mode_light)
            "dark" -> getString(R.string.pref_theme_mode_dark)
            else -> getString(R.string.pref_theme_mode_system)
        }
        pref.summary = label
    }

    private fun updateThemeColorSummary(pref: Preference?) {
        pref ?: return
        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val current = prefs.getString("pref_accent", "default") ?: "default"

        if (current == "custom") {
            val hex = prefs.getString("pref_accent_custom", "").orEmpty()
            pref.summary = if (hex.isNotBlank()) {
                getString(R.string.pref_theme_color_custom_fmt, hex)
            } else {
                getString(R.string.pref_accent_custom_title)
            }
            return
        }

        val entries = resources.getStringArray(R.array.pref_accent_entries)
        val values = resources.getStringArray(R.array.pref_accent_values)
        val label = values.indexOf(current).let { i -> if (i in entries.indices) entries[i] else entries.firstOrNull() }
            ?: ""
        pref.summary = label
    }

    private fun showLanguageDialog() {
        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val pref = findPreference<Preference>("pref_language")
        val current = prefs.getString("pref_language", "system") ?: "system"

        val entries = resources.getStringArray(R.array.pref_language_entries)
        val values = resources.getStringArray(R.array.pref_language_values)

        val checked = values.indexOf(current).let { idx -> if (idx >= 0) idx else 0 }

        showSingleSelectCheckboxDialog(
            title = getString(R.string.pref_language_title),
            entries = entries,
            checkedIndex = checked,
        ) { which, dialog ->
            val selected = values[which]
            prefs.edit { putString("pref_language", selected) }
            LocaleHelper.setLanguage(requireActivity().application, selected)
            updateLanguageSummary(pref)
            restartAppTask()
            dialog.dismiss()
        }
    }

    // Theme Dialog (Mode + Color)
    private fun showThemeDialog() {
        val ctx = requireContext()
        val items = arrayOf(
            getString(R.string.pref_theme_mode_title),
            getString(R.string.pref_theme_color_title)
        )

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.pref_theme_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showThemeModeDialog()
                    1 -> showThemeColorDialog()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private fun showThemeModeDialog() {
        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val current = prefs.getString("pref_theme_mode", "system") ?: "system"

        val entries = arrayOf(
            getString(R.string.pref_theme_mode_system),
            getString(R.string.pref_theme_mode_light),
            getString(R.string.pref_theme_mode_dark)
        )
        val values = arrayOf("system", "light", "dark")
        val checked = values.indexOf(current).coerceAtLeast(0)

        showSingleSelectCheckboxDialog(
            title = getString(R.string.pref_theme_mode_title),
            entries = entries,
            checkedIndex = checked,
        ) { which, dialog ->
            val selected = values[which]
            prefs.edit { putString("pref_theme_mode", selected) }
            updateThemeModeSummary(findPreference("pref_theme_mode"))

            when (selected) {
                "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }

            dialog.dismiss()
        }
    }

    private fun showThemeColorDialog() {
        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val isPremium = PremiumManager.isPremium(ctx)
        val current = prefs.getString("pref_accent", "default") ?: "default"

        val allEntries = resources.getStringArray(R.array.pref_accent_entries)
        val allValues = resources.getStringArray(R.array.pref_accent_values)

        val freeCount = minOf(5, allEntries.size, allValues.size)

        val entries: Array<String>
        val values: Array<String>

        if (isPremium) {
            entries = allEntries + getString(R.string.pref_accent_custom)
            values = allValues + "custom"
        } else {
            entries = allEntries.copyOfRange(0, freeCount)
            values = allValues.copyOfRange(0, freeCount)
        }

        val checked = values.indexOf(current).let { idx -> if (idx >= 0) idx else 0 }

        showSingleSelectCheckboxDialog(
            title = getString(R.string.pref_theme_color_title),
            entries = entries,
            checkedIndex = checked,
        ) { which, dialog ->
            val selected = values[which]
            if (selected == "custom") {
                dialog.dismiss()
                showCustomColorPicker()
            } else {
                prefs.edit { putString("pref_accent", selected) }
                updateThemeColorSummary(findPreference("pref_theme_color"))
                dialog.dismiss()
                restartAppTask()
            }
        }
    }

    /**
     * Custom single-select dialog that uses MaterialCheckBox items.
     * This avoids OEM/framework-tinted single-choice list checkmarks that stay green in custom accent mode.
     */
    private fun showSingleSelectCheckboxDialog(
        title: String,
        entries: Array<String>,
        checkedIndex: Int,
        onSelected: (index: Int, dialog: AlertDialog) -> Unit,
    ) {
        val ctx = requireContext()
        val content = layoutInflater.inflate(R.layout.dialog_single_select_list, null)
        val recycler = content.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(ctx)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        val adapter = SingleSelectCheckboxAdapter(
            entries = entries.toList(),
            initialSelected = checkedIndex,
        ) { which ->
            onSelected(which, dialog)
        }
        recycler.adapter = adapter

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private class SingleSelectCheckboxAdapter(
        private val entries: List<String>,
        initialSelected: Int,
        private val onSelected: (Int) -> Unit,
    ) : RecyclerView.Adapter<SingleSelectCheckboxAdapter.VH>() {

        private var selectedIndex: Int = initialSelected.coerceIn(0, (entries.size - 1).coerceAtLeast(0))

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_single_select_checkbox, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.title.text = entries[position]

            // Ensure checkbox tint is always the current accent (especially important in custom-accent mode where OEM/framework defaults can show up again when views are rebound after scrolling).
            val accent = AccentColor.getAccentColorInt(holder.itemView.context)
            val unchecked = (accent and 0x00FFFFFF) or (0x8C shl 24) // ~55% alpha
            holder.cb.buttonTintList = android.content.res.ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(
                    accent,
                    unchecked
                )
            )

            // Avoid recursive click loops: row and checkbox both call select(position), but do NOT call performClick().
            holder.cb.setOnCheckedChangeListener(null)
            holder.cb.isChecked = position == selectedIndex
            // Checkbox is visual-only; the entire row handles clicks for stable behaviour across OEMs.
            holder.cb.isClickable = false
            holder.cb.isFocusable = false

            fun select() {
                // Selecting an entry immediately applies + closes the dialog.
                // Avoid any adapter update churn here (can race with dialog dismissal on some OEMs).
                // Do not treat "position" as stable; view holders can be rebound.
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
            val cb: MaterialRadioButton = itemView.findViewById(R.id.radio)
        }
    }

    private fun showCustomColorPicker() {
        val ctx = requireContext()
        if (!PremiumManager.isPremium(ctx)) {
            Toast.makeText(ctx, R.string.premium_required_for_theme, Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val initialHex = prefs.getString("pref_accent_custom", "#2E8B57") ?: "#2E8B57"
        var color = try { initialHex.toColorInt() } catch (_: IllegalArgumentException) { "#2E8B57".toColorInt() }

        val view = layoutInflater.inflate(R.layout.dialog_color_picker, null)
        val preview = view.findViewById<View>(R.id.colorPreview)
        val sliderR = view.findViewById<SeekBar>(R.id.sliderR)
        val sliderG = view.findViewById<SeekBar>(R.id.sliderG)
        val sliderB = view.findViewById<SeekBar>(R.id.sliderB)

        fun updatePreviewFromColor() { preview.setBackgroundColor(color) }
        fun updateColorFromSliders() {
            color = Color.rgb(sliderR.progress, sliderG.progress, sliderB.progress)
            updatePreviewFromColor()
        }

        sliderR.max = 255; sliderG.max = 255; sliderB.max = 255
        sliderR.progress = Color.red(color)
        sliderG.progress = Color.green(color)
        sliderB.progress = Color.blue(color)

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { updateColorFromSliders() }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        sliderR.setOnSeekBarChangeListener(listener)
        sliderG.setOnSeekBarChangeListener(listener)
        sliderB.setOnSeekBarChangeListener(listener)
        updatePreviewFromColor()

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.pref_accent_custom_title))
            .setView(view)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val hex = String.format("#%08X", color)
                prefs.edit {
                    putString("pref_accent", "custom")
                    putString("pref_accent_custom", hex)
                }
                updateThemeColorSummary(findPreference("pref_theme_color"))
                restartAppTask()
            }
            .create()

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    // Google Account (popup: sign in/out/delete only)
    private fun updateGooglePrefSummary() {
        val pref = findPreference<Preference>("pref_google_account") ?: return
        val ctx = requireContext()
        val loggedIn = at.saltyy.switchly.auth.Auth.uid() != null

        val base = if (loggedIn) getString(R.string.settings_google_logged_in)
        else getString(R.string.settings_google_logged_out)

        if (!loggedIn) {
            pref.summary = base
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val lastBackup = prefs.getLong("pref_last_backup_epoch_ms", -1L)
        if (lastBackup <= 0L) {
            pref.summary = base
            return
        }

        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val formatted = df.format(Date(lastBackup))
        pref.summary = "$base – " + getString(R.string.settings_last_backup, formatted)
    }

    private fun showGoogleAccountDialog() {
        val ctx = requireContext()
        val loggedIn = at.saltyy.switchly.auth.Auth.uid() != null

        fun makeIconAdapter(items: List<IconActionItem>): ArrayAdapter<IconActionItem> {
            return object : ArrayAdapter<IconActionItem>(ctx, android.R.layout.select_dialog_item, items) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent)
                    val tv = v.findViewById<TextView>(android.R.id.text1)
                    val item = getItem(position) ?: return v

                    tv.text = item.title
                    tv.compoundDrawablePadding = (12 * ctx.resources.displayMetrics.density).toInt()

                    val normalColor = MaterialColors.getColor(tv, com.google.android.material.R.attr.colorOnSurface)
                    val dangerColor = ContextCompat.getColor(ctx, R.color.status_error)

                    val isDelete = item.iconRes == R.drawable.delete_24
                    val textColor = if (isDelete) dangerColor else normalColor
                    tv.setTextColor(textColor)

                    // Tint icon to match (optional but looks consistent)
                    val d = ContextCompat.getDrawable(ctx, item.iconRes)
                    if (d != null) {
                        val wrap = DrawableCompat.wrap(d.mutate())
                        DrawableCompat.setTint(wrap, textColor)
                        tv.setCompoundDrawablesWithIntrinsicBounds(wrap, null, null, null)
                    } else {
                        tv.setCompoundDrawablesWithIntrinsicBounds(item.iconRes, 0, 0, 0)
                    }

                    return v
                }
            }
        }

        if (!loggedIn) {
            val items = listOf(
                IconActionItem(getString(R.string.sign_in), R.drawable.login_24)
            )

            val dialog = AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.settings_google_dialog_title))
                .setAdapter(makeIconAdapter(items)) { _, which ->
                    if (which == 0) {
                        findPreference<Preference>("pref_google_account")?.summary =
                            getString(R.string.settings_google_signing_in)
                        at.saltyy.switchly.auth.Auth.startSignIn(requireActivity())
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .create()

            dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
            dialog.show()
            return
        }

        val items = listOf(
            IconActionItem(getString(R.string.sign_out), R.drawable.logout_24),
            IconActionItem(getString(R.string.settings_account_action_delete), R.drawable.delete_24)
        )

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.settings_google_dialog_title))
            .setAdapter(makeIconAdapter(items)) { _, which ->
                when (which) {
                    0 -> confirmAction(
                        title = getString(R.string.sign_out),
                        message = getString(R.string.settings_confirm_sign_out_message),
                        positiveText = getString(R.string.sign_out),
                    ) {
                        at.saltyy.switchly.auth.Auth.signOut(ctx) {
                            PreferenceManager.getDefaultSharedPreferences(ctx).edit {
                                remove("pref_last_backup_epoch_ms")
                            }
                            updateGooglePrefSummary()
                            updateCloudPrefVisibility()
                            Toast.makeText(ctx, getString(R.string.settings_signed_out), Toast.LENGTH_SHORT).show()
                        }
                    }

                    1 -> confirmAction(
                        title = getString(R.string.settings_account_delete_confirm_title),
                        message = getString(R.string.settings_account_delete_confirm_message),
                        positiveText = getString(R.string.delete),
                    ) {
                        AccountDeletion.deleteAccount(requireActivity())
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private fun startRestoreFlowWithChoice() {
        val ctx = requireContext()
        CloudSyncRuntime.listBackups(requireContext()) { ok, err, backups ->
            if (!ok) {
                Toast.makeText(
                    ctx,
                    getString(R.string.cloud_error_fmt, err ?: getString(R.string.error_unknown)),
                    Toast.LENGTH_SHORT
                ).show()
                return@listBackups
            }

            val list = backups ?: emptyList()
            if (list.isEmpty()) {
                CloudSyncRuntime.pullRemoteState(ctx) { ok2, err2 ->
                    if (ok2) {
                        Toast.makeText(ctx, getString(R.string.cloud_restore_ok_restart), Toast.LENGTH_SHORT).show()
                        restartAppTask()
                    } else {
                        Toast.makeText(
                            ctx,
                            getString(R.string.cloud_error_fmt, err2 ?: getString(R.string.error_unknown)),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                return@listBackups
            }

            val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            val labels = list.map { meta -> df.format(Date(meta.createdAt)) }.toTypedArray()

            val dialog = AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.settings_restore_choose_title))
                .setItems(labels) { _, which ->
                    val meta = list[which]
                    CloudSyncRuntime.pullBackup(ctx, meta.id) { ok3, err3 ->
                        if (ok3) {
                            Toast.makeText(ctx, getString(R.string.cloud_restore_ok_restart), Toast.LENGTH_SHORT).show()
                            restartAppTask()
                        } else {
                            Toast.makeText(
                                ctx,
                                getString(R.string.cloud_error_fmt, err3 ?: getString(R.string.error_unknown)),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                .setNeutralButton(getString(R.string.delete)) { _, _ ->
                    showDeleteBackupsDialog(ctx, list)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .create()

	            dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
            dialog.show()
        }
    }

    private fun confirmAction(title: String, message: String, positiveText: String, onConfirm: () -> Unit) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { _, _ -> onConfirm() }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    // Emergency
    private fun refreshEmergencyPref() {
        val pref = findPreference<Preference>("pref_emergency_unlock") ?: return
        val ctx = requireContext()

        val featureEnabled = EmergencyBypassStore.isFeatureEnabled(ctx)
        pref.isVisible = true

        if (!featureEnabled) {
            pref.isEnabled = true
            pref.summary = getString(R.string.pref_emergency_summary_disabled)
            return
        }

        val active = EmergencyBypassStore.isActive(ctx)
        val paused = EmergencyBypassStore.isPaused(ctx)
        val usedToday = EmergencyBypassStore.hasUsedToday(ctx)
        val remaining = EmergencyBypassStore.minutesRemaining(ctx)

        // Keep this clickable while active/paused so users can pause/resume.
        pref.isEnabled = active || paused || !usedToday
        pref.summary = when {
            paused -> getString(R.string.pref_emergency_summary_paused, remaining)
            active -> getString(R.string.pref_emergency_summary_active_with_time, remaining)
            usedToday -> getString(R.string.pref_emergency_summary_used)
            else -> getString(R.string.pref_emergency_summary)
        }
    }

    private fun refreshLockUi() {
        val ctx = requireContext()
        val enabled = SwitchModeStore.isEnabled(ctx)
        val requireNfc = SwitchModeStore.isNfcRequiredForDisable(ctx)
        val locked = enabled && requireNfc
        stylePreferenceLocked("pref_switch_mode", locked)
        stylePreferenceLocked("pref_permissions", locked)
    }

    private fun stylePreferenceLocked(key: String, locked: Boolean) {
        val pref = findPreference<Preference>(key) ?: return
        val ctx = requireContext()

        val baseTitle = pref.extras.getString("base_title") ?: pref.title?.toString().orEmpty()
        if (!pref.extras.containsKey("base_title")) pref.extras.putString("base_title", baseTitle)

        val baseSummaryStored = pref.extras.getString("base_summary")
        val baseSummary = baseSummaryStored ?: pref.summary?.toString()
        if (!pref.extras.containsKey("base_summary") && pref.summary != null) {
            pref.extras.putString("base_summary", pref.summary.toString())
        }

        if (!locked) {
            pref.title = baseTitle
            if (baseSummary != null) pref.summary = baseSummary
            return
        }

        val disabledColor = ContextCompat.getColor(ctx, R.color.status_neutral)
        val titleText = "🔒 $baseTitle"
        pref.title = SpannableString(titleText).apply {
            setSpan(ForegroundColorSpan(disabledColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val summaryText = baseSummary ?: ""
        if (summaryText.isNotEmpty()) {
            pref.summary = SpannableString(summaryText).apply {
                setSpan(ForegroundColorSpan(disabledColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    // app restart
    private fun restartAppTask() {
        val i = Intent(requireContext(), MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(i)
        requireActivity().finish()
    }

    // accent helper
    private fun getCurrentAccentColor(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = prefs.getString("pref_accent", "default") ?: "default"
        return if (key == "custom") {
            val hex = prefs.getString("pref_accent_custom", "#2E8B57") ?: "#2E8B57"
            try { hex.toColorInt() } catch (_: IllegalArgumentException) { AccentColor.getAccentColorInt(context) }
        } else {
            AccentColor.getAccentColorInt(context)
        }
    }

    private fun tintCategories() {
        val screen = preferenceScreen ?: return
        val accent = getCurrentAccentColor(requireContext())
        tintGroup(screen, accent)
    }

    private fun ensureDeveloperInfoIconAccent() {
        val ctx = context ?: return
        val pref = findPreference<Preference>("pref_about_developer_info") ?: return
        val accent = getCurrentAccentColor(ctx)
        val base = ContextCompat.getDrawable(ctx, R.drawable.info_24)?.mutate() ?: return
        val wrapped = DrawableCompat.wrap(base)
        DrawableCompat.setTint(wrapped, accent)
        pref.icon = wrapped
    }

    private fun tintGroup(group: PreferenceGroup, accent: Int) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref is PreferenceCategory) {
                val title = pref.title?.toString() ?: ""
                if (title.isNotEmpty()) {
                    categoryTitles.add(title)
                    pref.title = SpannableString(title).apply {
                        setSpan(ForegroundColorSpan(accent), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            } else {
                val title = pref.title?.toString() ?: ""
                if (title.isNotEmpty()) {
                    pref.title = SpannableString(title).apply {
                        setSpan(ForegroundColorSpan(accent), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }

                pref.icon?.let { icon ->
                    val wrapped = DrawableCompat.wrap(icon.mutate())
                    DrawableCompat.setTint(wrapped, accent)
                    pref.icon = wrapped
                }
            }
            if (pref is PreferenceGroup) tintGroup(pref, accent)
        }
    }


    /**
     * Account setting: change the PIN used for Emergency Unlock.
     * Flow:
     * - If no PIN exists yet: directly ask to set one.
     * - If PIN exists: verify current PIN first, then ask to set a new one.
     */
    private fun showChangeEmergencyPinFlow() {
        val ctx = requireContext()
        val sp = ctx.getSharedPreferences(PREFS, 0)
        val storedPin = sp.getString(KEY_EMERGENCY_PIN, null)

        if (storedPin.isNullOrEmpty()) {
            showSetEmergencyPinDialog {
                Toast.makeText(ctx, R.string.emergency_pin_changed, Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Verify current PIN first.
        showEnterEmergencyPinDialog(storedPin) {
            showSetEmergencyPinDialog {
                Toast.makeText(ctx, R.string.emergency_pin_changed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openEmergencyUnlockDirect() {
        showEmergencyUnlockWithPin()
    }

    private fun showEmergencyUnlockWithPin() {
        val ctx = requireContext()
        val sp = ctx.getSharedPreferences(PREFS, 0)
        val storedPin = sp.getString(KEY_EMERGENCY_PIN, null)

        if (!EmergencyBypassStore.isFeatureEnabled(ctx)) {
            val dialog = AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.pref_emergency_title))
                .setMessage(getString(R.string.emergency_disabled_message_controls))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.emergency_open_controls_action)) { _, _ ->
                    startActivity(Intent(ctx, ToggleOptionsActivity::class.java))
                }
                .create()

            dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
            dialog.show()
            return
        }

        val active = EmergencyBypassStore.isActive(ctx)
        val paused = EmergencyBypassStore.isPaused(ctx)
        if (active || paused) {
            showEmergencyManageDialog()
            return
        }

        if (EmergencyBypassStore.hasUsedToday(ctx)) {
            Toast.makeText(ctx, R.string.emergency_used_today, Toast.LENGTH_SHORT).show()
            return
        }

        if (storedPin.isNullOrEmpty()) {
            showSetEmergencyPinDialog { showEmergencyUnlockStartDialog() }
        } else {
            showEnterEmergencyPinDialog(storedPin) { showEmergencyUnlockStartDialog() }
        }
    }

    private fun showEmergencyManageDialog() {
        val ctx = requireContext()
        val active = EmergencyBypassStore.isActive(ctx)
        val paused = EmergencyBypassStore.isPaused(ctx)
        val remaining = EmergencyBypassStore.minutesRemaining(ctx)

        if (!active && !paused) {
            refreshEmergencyPref()
            return
        }

        val title = if (paused) {
            getString(R.string.emergency_manage_title_paused, remaining)
        } else {
            getString(R.string.emergency_manage_title_active, remaining)
        }

        val actions = if (active) {
            listOf(
                getString(R.string.emergency_action_pause),
                getString(R.string.emergency_action_end)
            )
        } else {
            listOf(
                getString(R.string.emergency_action_resume),
                getString(R.string.emergency_action_end)
            )
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setItems(actions.toTypedArray()) { _, which ->
                if (active) {
                    when (which) {
                        0 -> {
                            val ok = EmergencyBypassStore.pause(ctx)
                            if (ok) {
                                SwitchModeStore.clearTemporary(ctx)
                                Toast.makeText(ctx, getString(R.string.emergency_paused_toast), Toast.LENGTH_SHORT).show()
                            }
                        }
                        1 -> {
                            EmergencyBypassStore.cancel(ctx)
                            SwitchModeStore.clearTemporary(ctx)
                            Toast.makeText(ctx, getString(R.string.emergency_ended_toast), Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    when (which) {
                        0 -> {
                            val ok = EmergencyBypassStore.resume(ctx)
                            if (ok) {
                                val remaining = EmergencyBypassStore.minutesRemaining(ctx).coerceAtLeast(1)
                                SwitchModeStore.setTemporarilyDisabled(ctx, remaining * 60_000L)
                                Toast.makeText(ctx, getString(R.string.emergency_resumed_toast), Toast.LENGTH_SHORT).show()
                            }
                        }
                        1 -> {
                            EmergencyBypassStore.cancel(ctx)
                            SwitchModeStore.clearTemporary(ctx)
                            Toast.makeText(ctx, getString(R.string.emergency_ended_toast), Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                BlockingRuntime.ensureRunning(ctx)
                refreshEmergencyPref()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private fun showEmergencyUnlockStartDialog() {
        val ctx = requireContext()
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.pref_emergency_title))
            .setMessage(getString(R.string.emergency_action_start_15))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                triggerEmergencyUnlock()
            }
            .create()

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private fun triggerEmergencyUnlock() {

        val ctx = requireContext()
        val minutes = 15
        val ok = EmergencyBypassStore.enableIfAllowed(ctx, minutes)
        if (ok) {
            SwitchModeStore.setTemporarilyDisabled(ctx, minutes * 60_000L)
            Toast.makeText(ctx, getString(R.string.emergency_enabled_toast, minutes), Toast.LENGTH_SHORT).show()
            BlockingRuntime.ensureRunning(ctx)
        } else {
            Toast.makeText(ctx, getString(R.string.emergency_used_today), Toast.LENGTH_SHORT).show()
        }
        refreshEmergencyPref()
    }

    private fun showSetEmergencyPinDialog(onSuccess: () -> Unit) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.emergency_pin_choose_hint)
            backgroundTintList = AccentColor.getActiveColor(ctx)
        }

        val container = FrameLayout(ctx).apply {
            val margin = (24 * resources.displayMetrics.density).toInt()
            setPadding(margin, 0, margin, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.emergency_pin_title))
            .setMessage(getString(R.string.emergency_pin_message))
            .setView(container)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length < 4) {
                    Toast.makeText(ctx, getString(R.string.emergency_pin_too_short), Toast.LENGTH_SHORT).show()
                } else {
                    ctx.getSharedPreferences(PREFS, 0).edit { putString(KEY_EMERGENCY_PIN, pin) }
                    onSuccess()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private fun showEnterEmergencyPinDialog(expectedPin: String, onSuccess: () -> Unit) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.emergency_pin_enter_hint)
            backgroundTintList = AccentColor.getActiveColor(ctx)
        }

        val container = FrameLayout(ctx).apply {
            val margin = (24 * resources.displayMetrics.density).toInt()
            setPadding(margin, 0, margin, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.emergency_pin_title))
            .setMessage(getString(R.string.emergency_pin_message))
            .setView(container)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val pin = input.text.toString().trim()
                if (pin == expectedPin) onSuccess()
                else Toast.makeText(ctx, getString(R.string.emergency_pin_incorrect), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private fun showDeleteBackupsDialog(vararg _ignored: Any?) {
        showDeleteBackupsDialog()
    }

    private fun showDeleteBackupsDialog() {
        val ctx = requireContext()
        CloudSyncRuntime.listBackups(ctx) { ok, err, backups ->
            if (!ok) {
                Toast.makeText(ctx, getString(R.string.cloud_error_fmt, err ?: getString(R.string.error_unknown)), Toast.LENGTH_SHORT).show()
                return@listBackups
            }
            val list = backups ?: emptyList()
            if (list.isEmpty()) {
                Toast.makeText(ctx, getString(R.string.cloud_no_backups), Toast.LENGTH_SHORT).show()
                return@listBackups
            }

            val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            val labels = list.map { df.format(Date(it.createdAt)) }.toTypedArray()
            val checked = BooleanArray(labels.size)

            val dialog = AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.settings_delete_backups_title))
                .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton(getString(R.string.delete)) { _, _ ->
                    val ids = list.indices.filter { checked[it] }.map { list[it].id }
                    if (ids.isEmpty()) return@setPositiveButton
                    // delete sequentially if API supports it; otherwise ignore
                    ids.forEach { id ->
                        runCatching {
                            CloudSyncRuntime.deleteBackup(ctx, id) { _, _ -> }
                        }
                    }
                    Toast.makeText(ctx, getString(R.string.deleted), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .create()

            dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
            dialog.show()
        }
    }

    private fun showResetAllDataDialog() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.pref_reset_app_data_confirm_title))
            .setMessage(getString(R.string.pref_reset_app_data_confirm_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                resetAllAppDataNow()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .showAccented()
    }

    private fun resetAllAppDataNow() {
        val ctx = requireContext()
        val ok = runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.clearApplicationUserData()
        }.getOrDefault(false)

        if (!ok) {
            // Fallback for OEMs where clearApplicationUserData may fail silently.
            runCatching {
                PreferenceManager.getDefaultSharedPreferences(ctx).edit { clear() }
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
                ctx.getSharedPreferences("switchly_prefs_schedules", Context.MODE_PRIVATE).edit { clear() }
                ctx.deleteDatabase("switchly_db")
                ctx.cacheDir?.deleteRecursively()
                ctx.filesDir?.listFiles()?.forEach { it.deleteRecursively() }
            }
            Toast.makeText(ctx, getString(R.string.pref_reset_app_data_done), Toast.LENGTH_LONG).show()
            restartAppTask()
        }
    }

    companion object {
        private const val ARG_PREFERENCE_ROOT = "androidx.preference.PreferenceFragmentCompat.PREFERENCE_ROOT"
        private const val PREFS = "switchly_prefs"
        private const val KEY_DEV_UNLOCKED = "pref_dev_unlocked"
        private const val KEY_EMERGENCY_PIN = "pref_emergency_pin"
    }
}
