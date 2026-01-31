package at.saltyy.switchly.feature.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
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
import androidx.preference.SwitchPreferenceCompat
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.R
import at.saltyy.switchly.auth.AccountDeletion
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.SchedulePlanner
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.sync.CloudSyncRuntime
import at.saltyy.switchly.feature.about.AboutActivity
import at.saltyy.switchly.feature.faq.FaqActivity
import at.saltyy.switchly.feature.premium.PremiumInfoActivity
import at.saltyy.switchly.feature.profiles.ManageProfilesActivity
import at.saltyy.switchly.nfc.NfcWriterActivity
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.PlayStoreUpdatePrompt
import com.google.android.material.color.MaterialColors
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class SettingsFragment : PreferenceFragmentCompat() {

    private val categoryTitles = mutableSetOf<String>()
    private var devVisible: Boolean = false
    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var nextChangedReceiver: BroadcastReceiver? = null

    private data class IconActionItem(val title: String, val iconRes: Int)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_settings, rootKey)

        val ctx = requireContext()
        val sp = ctx.getSharedPreferences(PREFS, 0)
        devVisible = sp.getBoolean(KEY_DEV_UNLOCKED, false)

        tintCategories()

        // Hidden master toggle (dev-only)
        findPreference<SwitchPreferenceCompat>("pref_switch_mode")?.apply {
            isVisible = devVisible
            isChecked = SwitchModeStore.isEnabled(ctx)

            setOnPreferenceClickListener {
                val enabled = SwitchModeStore.isEnabled(ctx)
                val requireNfc = SwitchModeStore.isNfcRequiredForDisable(ctx)
                val emergencyActive = EmergencyBypassStore.isActive(ctx)
                val locked = enabled && requireNfc && !emergencyActive
                if (locked) {
                    Toast.makeText(ctx, getString(R.string.toast_disable_requires_nfc), Toast.LENGTH_SHORT).show()
                }
                false
            }

            setOnPreferenceChangeListener { _, new ->
                val target = new as Boolean
                val currentlyEnabled = SwitchModeStore.isEnabled(ctx)
                val requireNfc = SwitchModeStore.isNfcRequiredForDisable(ctx)

                val emergencyActive = EmergencyBypassStore.isActive(ctx)

                val locked = currentlyEnabled && requireNfc && !target && !emergencyActive
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

        // Theme
        findPreference<Preference>("pref_theme")?.setOnPreferenceClickListener {
            showThemeDialog()
            true
        }

        // Manage profiles
        findPreference<Preference>("pref_manage_profiles")?.apply {
            isVisible = true
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), ManageProfilesActivity::class.java))
                true
            }
        }

        // NFC tag writer (moved from bottom nav)
        findPreference<Preference>("pref_write_tag")?.apply {
            isVisible = true
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), NfcWriterActivity::class.java))
                true
            }
        }

        // Emergency unlock
        findPreference<Preference>("pref_emergency_unlock")?.setOnPreferenceClickListener {
            showEmergencyUnlockWithPin()
            true
        }

        // Emergency feature toggle (defined in XML)
        findPreference<SwitchPreferenceCompat>("pref_emergency_enabled")?.apply {
            isChecked = EmergencyBypassStore.isFeatureEnabled(ctx)
            setOnPreferenceChangeListener { _, newValue ->
                EmergencyBypassStore.setFeatureEnabled(ctx, newValue as Boolean)
                refreshEmergencyPref()
                true
            }
        }

        // Permissions overview
        findPreference<Preference>("pref_permissions")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), PermissionsActivity::class.java))
            true
        }

        // Toggle options activity
        findPreference<Preference>("pref_toggle_options")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), ToggleOptionsActivity::class.java))
            true
        }

        // ABOUT (was "Version")
        findPreference<Preference>("pref_about")?.apply {
            summary = BuildConfig.VERSION_NAME

            // Show a small dot badge (and inline text) if an update is available on Google Play.
            // This is intentionally lightweight and does not spam the user with dialogs.
            PlayStoreUpdatePrompt.checkAvailability(requireActivity()) { available ->
                if (available) {
                    summary = "${BuildConfig.VERSION_NAME} (${getString(R.string.update_available_inline)})"
                    setIcon(R.drawable.sync_24)
                } else {
                    summary = BuildConfig.VERSION_NAME
                    setIcon(R.drawable.info_24)
                }
            }

            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), AboutActivity::class.java))
                true
            }
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

        // Backup as standalone prefs
        findPreference<Preference>("pref_cloud_backup")?.apply {
            setOnPreferenceClickListener {
                confirmAction(
                    title = getString(R.string.settings_confirm_backup_title),
                    message = getString(R.string.settings_confirm_backup_message)
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
                    message = getString(R.string.settings_confirm_restore_message)
                ) {
                    startRestoreFlowWithChoice()
                }
                true
            }
        }

        // Premium
        findPreference<Preference>("pref_premium_upgrade")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), PremiumInfoActivity::class.java))
            true
        }

        // Tutorial 
        findPreference<androidx.preference.Preference>("pref_tutorial")
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
                findPreference<SwitchPreferenceCompat>("pref_switch_mode")?.isChecked =
                    SwitchModeStore.isEnabled(ctx)
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

    // Hide/show backup + restore prefs depending on login state
    private fun updateCloudPrefVisibility() {
        val loggedIn = at.saltyy.switchly.auth.Auth.uid() != null
        findPreference<Preference>("pref_cloud_backup")?.isVisible = loggedIn
        findPreference<Preference>("pref_cloud_restore")?.isVisible = loggedIn
    }

    private fun tintCategoryViewsInList() {
        val list = listView ?: return
        if (categoryTitles.isEmpty()) return
        val accent = getCurrentAccentColor(requireContext())

        fun tintInView(v: View) {
            if (v is TextView) {
                val text = v.text?.toString() ?: return
                if (categoryTitles.contains(text)) v.setTextColor(accent)
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
        list.setPadding(list.paddingLeft, 0, list.paddingRight, list.paddingBottom)
        list.clipToPadding = false
        tintCategoryViewsInList()
    }

    // Next schedule indicator
    private fun updateNextScheduleIndicator() {
        val ctx = requireContext()
        val pref = findPreference<Preference>("pref_schedules_next") ?: return

        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        val show = sp.getBoolean(ToggleOptionsActivity.KEY_SHOW_NEXT_SCHEDULE, false)
        pref.isVisible = show
        if (!show) return

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
        refreshLockUi()
        refreshEmergencyPref()
        updateNextScheduleIndicator()
        updateGooglePrefSummary()
        updateCloudPrefVisibility()

        if (nextChangedReceiver == null) {
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

    private fun showLanguageDialog() {
        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val pref = findPreference<Preference>("pref_language")
        val current = prefs.getString("pref_language", "system") ?: "system"

        val entries = resources.getStringArray(R.array.pref_language_entries)
        val values = resources.getStringArray(R.array.pref_language_values)

        val checked = values.indexOf(current).let { idx -> if (idx >= 0) idx else 0 }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.pref_language_title))
            .setSingleChoiceItems(entries, checked) { d, which ->
                val selected = values[which]
                prefs.edit { putString("pref_language", selected) }
                LocaleHelper.setLanguage(requireActivity().application, selected)
                updateLanguageSummary(pref)
                restartAppTask()
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener { dialog.applyAccentToButtons() }
        dialog.show()
    }

    // Theme Dialog (Mode + Color)
    private fun showThemeDialog() {
        val ctx = requireContext()
        val items = arrayOf(
            getString(R.string.pref_theme_mode_title),
            getString(R.string.pref_theme_color_title)
        )

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.pref_theme_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showThemeModeDialog()
                    1 -> showThemeColorDialog()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener { dialog.applyAccentToButtons() }
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

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.pref_theme_mode_title))
            .setSingleChoiceItems(entries, checked) { dialogInterface, which ->
                val selected = values[which]
                prefs.edit { putString("pref_theme_mode", selected) }

                when (selected) {
                    "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }

                dialogInterface.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener { dialog.applyAccentToButtons() }
        dialog.show()
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

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.pref_theme_color_title))
            .setSingleChoiceItems(entries, checked) { dialogInterface, which ->
                val selected = values[which]
                if (selected == "custom") {
                    dialogInterface.dismiss()
                    showCustomColorPicker()
                } else {
                    prefs.edit { putString("pref_accent", selected) }
                    dialogInterface.dismiss()
                    restartAppTask()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener { dialog.applyAccentToButtons() }
        dialog.show()
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
                restartAppTask()
            }
            .create()

        dialog.setOnShowListener { dialog.applyAccentToButtons() }
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

            dialog.setOnShowListener { dialog.applyAccentToButtons() }
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
                        title = getString(R.string.settings_confirm_sign_out_title),
                        message = getString(R.string.settings_confirm_sign_out_message)
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
                        message = getString(R.string.settings_account_delete_confirm_message)
                    ) {
                        AccountDeletion.deleteAccount(requireActivity())
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener { dialog.applyAccentToButtons() }
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
                Toast.makeText(ctx, getString(R.string.cloud_error_no_backup_found), Toast.LENGTH_SHORT).show()
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
                .setNegativeButton(getString(R.string.cancel), null)
                .create()

            dialog.setOnShowListener { dialog.applyAccentToButtons() }
            dialog.show()
        }
    }

    private fun confirmAction(title: String, message: String, onConfirm: () -> Unit) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.yes)) { _, _ -> onConfirm() }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener { dialog.applyAccentToButtons() }
        dialog.show()
    }

    // Emergency
    private fun refreshEmergencyPref() {
        val pref = findPreference<Preference>("pref_emergency_unlock") ?: return
        val ctx = requireContext()

        val featureEnabled = EmergencyBypassStore.isFeatureEnabled(ctx)
        pref.isVisible = featureEnabled
        if (!featureEnabled) return

        val active = EmergencyBypassStore.isActive(ctx)
        val usedToday = EmergencyBypassStore.hasUsedToday(ctx)

        pref.isEnabled = !active && !usedToday
        pref.summary = when {
            active -> getString(R.string.pref_emergency_summary_active)
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
            }
            if (pref is PreferenceGroup) tintGroup(pref, accent)
        }
    }

    private fun AlertDialog.applyAccentToButtons() {
        val accent = AccentColor.getAccentColorInt(context)
        getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent)
        getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accent)
        getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(accent)
    }

    // emergency pin
    private fun showEmergencyUnlockWithPin() {
        val ctx = requireContext()
        val sp = ctx.getSharedPreferences(PREFS, 0)
        val storedPin = sp.getString(KEY_EMERGENCY_PIN, null)

        if (!EmergencyBypassStore.isFeatureEnabled(ctx)) {
            Toast.makeText(ctx, R.string.emergency_used_today, Toast.LENGTH_SHORT).show()
            return
        }
        if (EmergencyBypassStore.isActive(ctx)) {
            Toast.makeText(ctx, R.string.pref_emergency_summary_active, Toast.LENGTH_SHORT).show()
            return
        }
        if (EmergencyBypassStore.hasUsedToday(ctx)) {
            Toast.makeText(ctx, R.string.emergency_used_today, Toast.LENGTH_SHORT).show()
            return
        }

        if (storedPin.isNullOrEmpty()) {
            showSetEmergencyPinDialog { triggerEmergencyUnlock() }
        } else {
            showEnterEmergencyPinDialog(storedPin) { triggerEmergencyUnlock() }
        }
    }

    private fun triggerEmergencyUnlock() {
        val ctx = requireContext()
        val minutes = 15
        val ok = EmergencyBypassStore.enableIfAllowed(ctx, minutes)
        if (ok) {
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
            .setTitle(getString(R.string.emergency_pin_set_title))
            .setMessage(getString(R.string.emergency_pin_set_message))
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

        dialog.setOnShowListener { dialog.applyAccentToButtons() }
        dialog.show()
    }

    private fun showEnterEmergencyPinDialog(expectedPin: String, onSuccess: () -> Unit) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.emergency_pin_enter_hint)
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

        dialog.setOnShowListener { dialog.applyAccentToButtons() }
        dialog.show()
    }

    companion object {
        private const val PREFS = "switchly_prefs"
        private const val KEY_DEV_UNLOCKED = "pref_dev_unlocked"
        private const val KEY_EMERGENCY_PIN = "pref_emergency_pin"
    }
}
