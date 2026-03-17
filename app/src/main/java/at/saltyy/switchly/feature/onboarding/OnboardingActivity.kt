package at.saltyy.switchly.feature.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.viewpager2.widget.ViewPager2
import at.saltyy.switchly.R
import at.saltyy.switchly.data.onboarding.OnboardingPage
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.blocking.SwitchlyAccessibilityService
import at.saltyy.switchly.feature.onboarding.adapters.OnboardingPagerAdapter
import at.saltyy.switchly.feature.picker.AppPickerActivity
import at.saltyy.switchly.feature.premium.PremiumInfoActivity
import at.saltyy.switchly.feature.profiles.ManageProfilesActivity
import at.saltyy.switchly.feature.settings.PermissionsActivity
import at.saltyy.switchly.feature.settings.ToggleOptionsActivity
import at.saltyy.switchly.nfc.NfcWriterActivity
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.PermissionUtils
import at.saltyy.switchly.util.getIntCompat
import at.saltyy.switchly.feature.usage.UsageStatsRepo
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
         * v3: add required Usage Access step + 7-day usage summary.
         * v4: make battery optimization required + improved permission completion states.
         * v5: hide quick summary until Usage Access is granted + add second opt-in confirm for NFC-required optional toggle.
         * v6: onboarding optional-features NFC toggle now shows the same immediate confirm popup as Settings.
         */
        const val ONBOARDING_VERSION = 8
        
    }

    private var forced: Boolean = false

    private lateinit var pager: ViewPager2
    private lateinit var pages: List<OnboardingPage>
    private lateinit var pagerAdapter: OnboardingPagerAdapter

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

        pager = findViewById(R.id.viewPager)
        val btnNext = findViewById<MaterialButton>(R.id.btn_next)
        val btnSkip = findViewById<MaterialButton>(R.id.btn_skip)

        applyFooterButtonAccent(btnSkip, btnNext)

        pages = buildPages()
        pagerAdapter = OnboardingPagerAdapter(activity = this, pages = pages)
        pager.adapter = pagerAdapter

        // Start at the first incomplete required step (e.g. missing permissions)
        val firstIncompleteRequired = pages.indexOfFirst {
            it.level == OnboardingPage.Level.REQUIRED && it.completionCheck != null && !it.completionCheck.invoke(this)
        }
        if (firstIncompleteRequired >= 0) {
            pager.setCurrentItem(firstIncompleteRequired, false)
        }

        fun updateButtons(pos: Int) {
            val last = pos == pages.lastIndex
            btnNext.text = if (last) getString(R.string.onb_done) else getString(R.string.onb_next)
        }

        updateButtons(pager.currentItem)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateButtons(position)
            }
        })

        btnSkip.setOnClickListener {
            val firstIncompleteRequired = pages.indexOfFirst {
                it.level == OnboardingPage.Level.REQUIRED && it.completionCheck != null && !it.completionCheck.invoke(this)
            }
            if (firstIncompleteRequired >= 0) {
                pager.setCurrentItem(firstIncompleteRequired, true)
                Toast.makeText(
                    this,
                    pages[firstIncompleteRequired].requiredMessage ?: getString(R.string.onb_required_step),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            if (!forced) markDone()
            finishToMain()
        }

        btnNext.setOnClickListener {
            val pos = pager.currentItem
            val page = pages.getOrNull(pos)

            // Gate required permission steps
            if (page != null && page.level == OnboardingPage.Level.REQUIRED && page.completionCheck != null) {
                val ok = page.completionCheck.invoke(this)
                if (!ok) {
                    val msg = page.requiredMessage ?: getString(R.string.onb_required_step)
                    MaterialAlertDialogBuilder(this)
                        .setTitle(page.title)
                        .setMessage(msg)
                        .setPositiveButton(R.string.onb_open) { _, _ ->
                            page.action?.invoke(this)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .showAccented()
                    return@setOnClickListener
                }
            }

            if (pos < pages.lastIndex) {
                pager.currentItem = pos + 1
            } else {
                if (!forced) markDone()
                finishToMain()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<MaterialButton>(R.id.btn_skip)?.let { skip ->
            findViewById<MaterialButton>(R.id.btn_next)?.let { next ->
                applyFooterButtonAccent(skip, next)
            }
        }

        // Rebuild pages if Usage Access changed so Quick Summary is only shown when permission is granted.
        val shouldShowSummary = UsageStatsRepo.hasUsageAccess(this)
        val hasSummaryPage = pages.any { it.type == OnboardingPage.Type.USAGE_SUMMARY }
        if (shouldShowSummary != hasSummaryPage) {
            val oldPos = pager.currentItem
            val oldPage = pages.getOrNull(oldPos)

            pages = buildPages()
            pagerAdapter = OnboardingPagerAdapter(activity = this, pages = pages)
            pager.adapter = pagerAdapter

            val target = oldPage?.let { previous ->
                pages.indexOfFirst { now -> now.type == previous.type && now.title == previous.title }
                    .takeIf { it >= 0 }
            } ?: oldPos.coerceIn(0, pages.lastIndex)
            pager.setCurrentItem(target, false)
        }

        // Refresh current page (permission states can change in Settings)
        val pos = pager.currentItem.coerceIn(0, pages.lastIndex)
        pagerAdapter.notifyItemChanged(pos)

        // Refresh usage summary page when it exists.
        val summaryIdx = pages.indexOfFirst { it.type == OnboardingPage.Type.USAGE_SUMMARY }
        if (summaryIdx >= 0) pagerAdapter.notifyItemChanged(summaryIdx)

        // If the current page was a required permission step and it is now complete, auto-advance once.
        val page = pages.getOrNull(pos) ?: return
        if (page.level == OnboardingPage.Level.REQUIRED && page.completionCheck?.invoke(this) == true) {
            if (pos < pages.lastIndex) {
                pager.post { pager.currentItem = pos + 1 }
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
        swQr.visibility = View.GONE
        (view as? android.view.ViewGroup)?.let { container ->
            val idx = container.indexOfChild(swQr)
            if (idx >= 0 && idx + 1 < container.childCount) {
                container.getChildAt(idx + 1).visibility = View.GONE
            }
        }

        val initialNfcRequired = SwitchModeStore.isNfcRequiredForDisable(this)
        var ignoreNfcListener = false
        var nfcEnableConfirmedInDialog = initialNfcRequired

        // current values
        swNfc.isChecked = initialNfcRequired
        swQr.isChecked = sp.getBoolean(ToggleOptionsActivity.KEY_SHOW_QR_CODE, false)

        // Onboarding should have the same explicit opt-in safety confirmation as Settings.
        swNfc.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreNfcListener) return@setOnCheckedChangeListener

            if (isChecked && !nfcEnableConfirmedInDialog) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.nfc_required_confirm_title)
                    .setMessage(R.string.nfc_required_confirm_enable_msg)
                    .setPositiveButton(R.string.nfc_action_enable) { _, _ ->
                        nfcEnableConfirmedInDialog = true
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        ignoreNfcListener = true
                        swNfc.isChecked = false
                        ignoreNfcListener = false
                    }
                    .setOnCancelListener {
                        ignoreNfcListener = true
                        swNfc.isChecked = false
                        ignoreNfcListener = false
                    }
                    .showAccented()
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.onb_optional_features_title))
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val requestedNfc = swNfc.isChecked
                val requestedQr = swQr.isChecked

                // If user somehow checked NFC without explicit opt-in in this dialog, keep it off and still persist QR preference.
                if (requestedNfc && !nfcEnableConfirmedInDialog) {
                    applyOptionalFeatureSelection(requestedNfc = false, requestedQr = requestedQr)
                } else {
                    applyOptionalFeatureSelection(requestedNfc = requestedNfc, requestedQr = requestedQr)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .showAccented()
    }

    private fun applyOptionalFeatureSelection(requestedNfc: Boolean, requestedQr: Boolean) {
        val sp = PreferenceManager.getDefaultSharedPreferences(this)

        // If Switchly is enabled, do NOT allow disabling the NFC requirement
        val enabled = SwitchModeStore.isEnabled(this)
        val emergencyActive = EmergencyBypassStore.isActive(this)
        val finalNfc = if (enabled && !requestedNfc && !emergencyActive) {
            Toast.makeText(this, R.string.toast_disable_requires_nfc, Toast.LENGTH_SHORT).show()
            true
        } else {
            requestedNfc
        }

        SwitchModeStore.setNfcRequiredForDisable(this, finalNfc)
        sp.edit {
            putBoolean(ToggleOptionsActivity.KEY_SHOW_QR_CODE, requestedQr)
        }
    }

    private fun applyFooterButtonAccent(btnSkip: MaterialButton, btnNext: MaterialButton) {
        val accent = AccentColor.getAccentColorInt(this)
        val onAccent = readableOnColor(accent)

        btnNext.backgroundTintList = ColorStateList.valueOf(accent)
        btnNext.setTextColor(onAccent)
        btnNext.iconTint = ColorStateList.valueOf(onAccent)

        btnSkip.strokeColor = ColorStateList.valueOf(accent)
        btnSkip.setTextColor(accent)
        btnSkip.iconTint = ColorStateList.valueOf(accent)
    }

    private fun readableOnColor(color: Int): Int {
        val black = ColorUtils.calculateContrast(Color.BLACK, color)
        val white = ColorUtils.calculateContrast(Color.WHITE, color)
        return if (black >= white) Color.BLACK else Color.WHITE
    }

    private fun isBatteryOptimizationIgnored(ctx: Context): Boolean {
        val pm: PowerManager = ctx.getSystemService() ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)
    }

    private fun areNotificationsAllowed(ctx: Context): Boolean {
        val channelEnabled = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        if (!channelEnabled) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun isQuickTileRequested(ctx: Context): Boolean {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_TILE_INFO, false)
    }

    private fun isCorePermissionsReady(ctx: Context): Boolean {
        val accessibility = PermissionUtils.isAccessibilityServiceEnabled(ctx, SwitchlyAccessibilityService::class.java)
        val usageAccess = UsageStatsRepo.hasUsageAccess(ctx)
        val batteryIgnored = isBatteryOptimizationIgnored(ctx)
        return accessibility && usageAccess && batteryIgnored
    }

    private fun buildPages(): List<OnboardingPage> = listOfNotNull(
        // 1) Quick setup overview (scan-friendly)
        OnboardingPage(
            iconRes = R.drawable.schedule_24,
            title = getString(R.string.onb_quick_setup_title),
            desc = getString(R.string.onb_quick_setup_desc),
            level = OnboardingPage.Level.INFO,
            actionLabel = null,
            action = null
        ),

        // 2) Core permissions in one hub (required)
        OnboardingPage(
            iconRes = R.drawable.security_24,
            title = getString(R.string.onb_permissions_hub_title),
            desc = getString(R.string.onb_permissions_hub_streamlined_desc),
            level = OnboardingPage.Level.REQUIRED,
            actionLabel = getString(R.string.onb_open),
            action = { act ->
                act.startActivity(
                    Intent(act, PermissionsActivity::class.java)
                        .putExtra(PermissionsActivity.EXTRA_FROM_ONBOARDING, true)
                )
            },
            completionCheck = { ctx -> isCorePermissionsReady(ctx) },
            requiredMessage = getString(R.string.onb_required_permissions_hub)
        ),

        // 3) Create profile
        OnboardingPage(
            iconRes = R.drawable.account_box_24,
            title = getString(R.string.onb_profile_title),
            desc = getString(R.string.onb_profile_desc),
            level = OnboardingPage.Level.RECOMMENDED,
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

        // 4) Pick apps to block
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

        // 5) Advanced settings (single door)
        OnboardingPage(
            iconRes = R.drawable.dashboard_24,
            title = getString(R.string.onb_advanced_setup_title),
            desc = getString(R.string.onb_advanced_setup_desc),
            level = OnboardingPage.Level.OPTIONAL,
            actionLabel = getString(R.string.onb_open),
            action = { act ->
                act.startActivity(Intent(act, ToggleOptionsActivity::class.java))
            }
        ),

        // 6) Finish
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
