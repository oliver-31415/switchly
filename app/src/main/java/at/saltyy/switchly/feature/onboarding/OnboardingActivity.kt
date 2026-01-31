package at.saltyy.switchly.feature.onboarding

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.viewpager2.widget.ViewPager2
import at.saltyy.switchly.R
import at.saltyy.switchly.data.onboarding.OnboardingPage
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.onboarding.adapters.OnboardingPagerAdapter
import at.saltyy.switchly.feature.picker.AppPickerActivity
import at.saltyy.switchly.feature.premium.PremiumInfoActivity
import at.saltyy.switchly.feature.profiles.ManageProfilesActivity
import at.saltyy.switchly.feature.settings.PermissionsActivity
import at.saltyy.switchly.feature.settings.ToggleOptionsActivity
import at.saltyy.switchly.nfc.NfcWriterActivity
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.getIntCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

class OnboardingActivity : ComponentActivity() {

    companion object {
        private const val PREFS = "switchly_prefs"
        private const val KEY_DONE = "onboarding_done"
        private const val KEY_VERSION = "onboarding_version"
        private const val KEY_TILE_INFO = "onboard_enable_tile"
        const val EXTRA_FROM_ONBOARDING = "extra_from_onboarding"
        const val EXTRA_FORCE = "extra_force_tutorial"
        /**
         * Bump this whenever the onboarding flow changes in a way that requires existing users to go through it again.
         * v1: initial release
         * v2: change app blocking core permission to Accessibility.
         */
        const val ONBOARDING_VERSION = 2
        
    }

    private var forced: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        forced = intent.getBooleanExtra(EXTRA_FORCE, false)

        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)

        // Cloud restore may rehydrate integer prefs as Long (Firestore stores integral numbers as Long).
        // SharedPreferences crashes with ClassCastException if the stored type differs from the requested one.
        // Read defensively and "heal" it back to Int.
        val seenVersion = sp.getIntCompat(KEY_VERSION, 0)

        // Gate: show onboarding again if the onboarding version changed
        if (!forced && seenVersion >= ONBOARDING_VERSION) {
            finishToMain()
            return
        }

        enableEdgeToEdge()

        val pager = findViewById<ViewPager2>(R.id.viewPager)
        val btnNext = findViewById<MaterialButton>(R.id.btn_next)
        val btnSkip = findViewById<MaterialButton>(R.id.btn_skip)

        val pages = buildPages()
        pager.adapter = OnboardingPagerAdapter(activity = this, pages = pages)

        fun updateButtons(pos: Int) {
            val last = pos == pages.lastIndex
            btnNext.text = if (last) getString(R.string.onb_done) else getString(R.string.onb_next)
        }

        updateButtons(0)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateButtons(position)
            }
        })

        btnSkip.setOnClickListener {
            if (!forced) markDone()
            finishToMain()
        }

        btnNext.setOnClickListener {
            val pos = pager.currentItem
            if (pos < pages.lastIndex) {
                pager.currentItem = pos + 1
            } else {
                if (!forced) markDone()
                finishToMain()
            }
        }
    }

    /**
     * Onboarding dialog: configure BOTH toggles directly
     * - Require NFC to unlock (Hard Lock)
     * - Show QR Button (Top bar)
     */
    private fun showOptionalFeaturesDialog() {
        val sp = PreferenceManager.getDefaultSharedPreferences(this)

        val view = layoutInflater.inflate(R.layout.dialog_onboarding_optional_features, null)
        val swNfc = view.findViewById<SwitchMaterial>(R.id.swRequireNfcUnlock)
        val swQr = view.findViewById<SwitchMaterial>(R.id.swShowQrButton)

        // current values
        swNfc.isChecked = SwitchModeStore.isNfcRequiredForDisable(this)
        swQr.isChecked = sp.getBoolean(ToggleOptionsActivity.KEY_SHOW_QR_CODE, false)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.onb_optional_features_dialog_title))
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->

                // If Switchly is enabled, do NOT allow disabling the NFC requirement
                val enabled = SwitchModeStore.isEnabled(this)
                val emergencyActive = EmergencyBypassStore.isActive(this)
                if (enabled && !swNfc.isChecked && !emergencyActive) {
                    Toast.makeText(this, R.string.toast_disable_requires_nfc, Toast.LENGTH_SHORT).show()
                    // force keep enabled
                    SwitchModeStore.setNfcRequiredForDisable(this, true)
                } else {
                    SwitchModeStore.setNfcRequiredForDisable(this, swNfc.isChecked)
                }

                sp.edit {
                    putBoolean(ToggleOptionsActivity.KEY_SHOW_QR_CODE, swQr.isChecked)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun buildPages(): List<OnboardingPage> = listOf(
        // Accessibility
        OnboardingPage(
            iconRes = R.drawable.security_24,
            title = getString(R.string.onb_accessibility_title),
            desc = getString(R.string.onb_accessibility_desc),
            level = OnboardingPage.Level.REQUIRED,
            actionLabel = getString(R.string.onb_open),
            action = { act ->
                act.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        ),

        // Profiles
        OnboardingPage(
            iconRes = R.drawable.account_box_24,
            title = getString(R.string.onb_profile_title),
            desc = getString(R.string.onb_profile_desc),
            level = OnboardingPage.Level.OPTIONAL,
            actionLabel = getString(R.string.onb_open),
            action = { act ->
                val profiles = ProfileStore.getProfiles(act).toMutableSet()
                if (profiles.isEmpty()) {
                    ProfileStore.addProfile(act, "Default")
                    ProfileStore.setCurrent(act, "Default")
                }
                act.startActivity(Intent(act, ManageProfilesActivity::class.java))
            }
        ),

        // Pick apps
        OnboardingPage(
            iconRes = R.drawable.apps_24,
            title = getString(R.string.onb_pick_title),
            desc = getString(R.string.onb_pick_desc),
            level = OnboardingPage.Level.RECOMMENDED,
            actionLabel = getString(R.string.onb_open),
            action = { act ->
                act.startActivity(
                    Intent(act, AppPickerActivity::class.java)
                        .putExtra(EXTRA_FROM_ONBOARDING, true)
                )
            }
        ),

        // Notifications
        OnboardingPage(
            iconRes = R.drawable.notifications_24,
            title = getString(R.string.onb_notif_title),
            desc = getString(R.string.onb_notif_desc),
            level = OnboardingPage.Level.RECOMMENDED,
            actionLabel = getString(R.string.onb_open),
            action = { act ->
                act.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, act.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        ),

        // Battery optimizations
        OnboardingPage(
            iconRes = R.drawable.battery_24,
            title = getString(R.string.onb_battery_title),
            desc = getString(R.string.onb_battery_desc),
            level = OnboardingPage.Level.RECOMMENDED,
            actionLabel = getString(R.string.onb_open),
            action = { act ->
                runCatching {
                    act.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure {
                    Toast.makeText(act, R.string.onb_battery_fallback_toast, Toast.LENGTH_LONG).show()
                }
            }
        ),

        // NFC (write tag)
        OnboardingPage(
            iconRes = R.drawable.nfc_24,
            title = getString(R.string.onb_nfc_title),
            desc = getString(R.string.onb_nfc_desc),
            level = OnboardingPage.Level.OPTIONAL,
            actionLabel = getString(R.string.onb_open),
            action = { act ->
                act.startActivity(Intent(act, NfcWriterActivity::class.java))
            }
        ),

        // Optional features (DIRECT toggles in dialog)
        OnboardingPage(
            iconRes = R.drawable.tune_24,
            title = getString(R.string.onb_optional_features_title),
            desc = getString(R.string.onb_optional_features_desc),
            level = OnboardingPage.Level.OPTIONAL,
            actionLabel = getString(R.string.onb_configure),
            action = { _ ->
                showOptionalFeaturesDialog()
            }
        ),

        // Quick tile
        OnboardingPage(
            iconRes = R.drawable.dashboard_24,
            title = getString(R.string.onb_tile_title),
            desc = getString(R.string.onb_tile_desc),
            level = OnboardingPage.Level.OPTIONAL,
            actionLabel = getString(R.string.onb_tile_enable_label),
            action = { act ->
                act.getSharedPreferences(PREFS, MODE_PRIVATE).edit { putBoolean(KEY_TILE_INFO, true) }

                val requested = QuickTileHelper.requestAddTileIfAvailable(act)
                if (!requested) {
                    Toast.makeText(act, getString(R.string.qs_tile_add_hint), Toast.LENGTH_LONG).show()
                }
            }
        ),

        // Permissions hub
        OnboardingPage(
            iconRes = R.drawable.security_24,
            title = getString(R.string.onb_permissions_hub_title),
            desc = getString(R.string.onb_permissions_hub_desc),
            level = OnboardingPage.Level.RECOMMENDED,
            actionLabel = getString(R.string.onb_open),
            action = { act ->
                act.startActivity(Intent(act, PermissionsActivity::class.java))
            }
        ),

        // Toggle options (after permissions)
        OnboardingPage(
            iconRes = R.drawable.tune_24,
            title = getString(R.string.onb_toggle_options_title),
            desc = getString(R.string.onb_toggle_options_desc),
            level = OnboardingPage.Level.OPTIONAL,
            actionLabel = getString(R.string.onb_open),
            action = { act ->
                act.startActivity(Intent(act, ToggleOptionsActivity::class.java))
            }
        ),

        // Premium info
        OnboardingPage(
            iconRes = R.drawable.star_24,
            title = getString(R.string.onb_premium_title),
            desc = getString(R.string.onb_premium_desc),
            level = OnboardingPage.Level.OPTIONAL,
            actionLabel = getString(R.string.onb_premium_open),
            action = { act ->
                act.startActivity(Intent(act, PremiumInfoActivity::class.java))
            }
        ),

        // Finish
        OnboardingPage(
            iconRes = R.drawable.play_arrow_24,
            title = getString(R.string.onb_start_title),
            desc = getString(R.string.onb_start_desc),
            level = OnboardingPage.Level.INFO,
            actionLabel = null,
            action = null
        )
    )

    private fun markDone() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit {
            putBoolean(KEY_DONE, true)
            putInt(KEY_VERSION, ONBOARDING_VERSION)
        }
    }

    private fun finishToMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}
