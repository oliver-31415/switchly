package at.saltyy.switchly.feature.tools

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.inbox.BlockedInboxActivity
import at.saltyy.switchly.feature.profiles.ManageProfilesActivity
import at.saltyy.switchly.feature.qr.QrGenerateActivity
import at.saltyy.switchly.feature.schedule.SchedulesActivity
import at.saltyy.switchly.feature.settings.ManageBarcodesActivity
import at.saltyy.switchly.feature.settings.ManagePairedTagsActivity
import at.saltyy.switchly.feature.settings.SettingsActivity
import at.saltyy.switchly.feature.settings.ToggleOptionsActivity
import at.saltyy.switchly.feature.usage.ScreenTimeDashboardActivity
import at.saltyy.switchly.nfc.NfcWriterActivity
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.util.SwitchlyAppAccessGuard
import at.saltyy.switchly.util.EditingLockGuard
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors

class ToolsHubActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tools_hub)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar, bottomNav = bottomNav)
        EdgeToEdgeUtils.applyBottomNavGestureInset(bottomNav)
        window.statusBarColor = ContextCompat.getColor(this, android.R.color.black)
        window.navigationBarColor = ContextCompat.getColor(this, android.R.color.black)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        toolbar.navigationIcon = null
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        listOf(
            R.id.ivSchedulesIcon,
            R.id.ivProfilesIcon,
            R.id.ivPairedTagsIcon,
            R.id.ivWriteNfcIcon,
            R.id.ivManageQrIcon,
            R.id.ivManageBarcodesIcon,
            R.id.ivBlockedNotificationsIcon,
            R.id.ivEmergencyIcon,
            R.id.ivInsightsIcon,
        ).forEach { id ->
            findViewById<ImageView>(id).imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0)
            )
        }

        reorderToolCards()

        findViewById<View>(R.id.cardSchedules).setOnClickListener {
            startActivity(Intent(this, SchedulesActivity::class.java))
        }
        findViewById<View>(R.id.cardProfiles).setOnClickListener {
            if (isProfileManagementLocked()) {
                val msgRes = if (isNfcLockedForProtectedEdits()) {
                    R.string.toast_cannot_change_profile_while_locked
                } else {
                    R.string.edit_locked_manage_profiles
                }
                EditingLockGuard.showLockedDialog(this, msgRes)
            } else {
                startActivity(Intent(this, ManageProfilesActivity::class.java))
            }
        }
        findViewById<View>(R.id.cardBlockedNotifications).setOnClickListener {
            startActivity(Intent(this, BlockedInboxActivity::class.java))
        }
        findViewById<View>(R.id.cardEmergency).setOnClickListener {
            showEmergencyQuickSheet()
        }
        findViewById<View>(R.id.cardPairedTags).setOnClickListener {
            if (EditingLockGuard.isLocked(this)) {
                EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_paired_tags)
            } else {
                startActivity(Intent(this, ManagePairedTagsActivity::class.java))
            }
        }
        findViewById<View>(R.id.cardWriteNfc).setOnClickListener {
            if (isNfcTagWritingLocked()) {
                EditingLockGuard.showLockedDialog(this, R.string.edit_locked_write_nfc_tags)
            } else {
                startActivity(Intent(this, NfcWriterActivity::class.java))
            }
        }
        findViewById<View>(R.id.cardManageQr).setOnClickListener {
            startActivity(Intent(this, QrGenerateActivity::class.java))
        }
        findViewById<View>(R.id.cardManageBarcodes).setOnClickListener {
            if (EditingLockGuard.isLocked(this)) {
                EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_barcodes)
            } else {
                startActivity(Intent(this, ManageBarcodesActivity::class.java))
            }
        }
        findViewById<View>(R.id.cardInsights).setOnClickListener {
            startActivity(ScreenTimeDashboardActivity.intent(this))
        }

        syncOptionalFeatureVisibility()

        bottomNav.selectedItemId = R.id.nav_tools
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                    finish()
                    true
                }
                R.id.nav_tools -> true
                R.id.nav_settings -> {
                    if (SwitchlyAppAccessGuard.isLocked(this)) {
                        SwitchlyAppAccessGuard.showLockedToast(this)
                        false
                    } else {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        finish()
                        true
                    }
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncOptionalFeatureVisibility()
    }

    private fun isNfcLockedForProtectedEdits(): Boolean {
        return SwitchModeStore.isEnabled(this) && SwitchModeStore.isNfcRequiredForDisable(this)
    }

    private fun isProfileManagementLocked(): Boolean {
        return if (isNfcLockedForProtectedEdits()) {
            true
        } else {
            SwitchModeStore.isBaseEnabled(this) &&
                !AutomationModeStore.isProfileSwitchingAllowedWhileEnabled(this)
        }
    }

    private fun isNfcTagWritingLocked(): Boolean {
        return SwitchModeStore.isEnabled(this) &&
            !AutomationModeStore.isNfcTagWritingAllowedWhileEnabled(this)
    }

    private fun syncOptionalFeatureVisibility() {
        val defaultSp = PreferenceManager.getDefaultSharedPreferences(this)
        val pairedTagsEnabled = defaultSp.getBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, false)
        val qrEnabled = AutomationModeStore.shouldShowQrTools(this)
        val barcodeEnabled = AutomationModeStore.shouldShowBarcodeTools(this)

        findViewById<View>(R.id.cardPairedTags).visibility =
            if (pairedTagsEnabled) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardManageQr).visibility =
            if (qrEnabled) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardManageBarcodes).visibility =
            if (barcodeEnabled) View.VISIBLE else View.GONE
    }

    private fun reorderToolCards() {
        val content = findViewById<LinearLayout>(R.id.toolsContent)
        val managementHeader = findViewById<View>(R.id.tvToolsSectionManagement)
        val qrCard = findViewById<View>(R.id.cardManageQr)
        val barcodeCard = findViewById<View>(R.id.cardManageBarcodes)

        listOf(qrCard, barcodeCard).forEach { card ->
            (card.parent as? LinearLayout)?.removeView(card)
        }

        val insertIndex = content.indexOfChild(managementHeader)
        content.addView(qrCard, insertIndex)
        content.addView(barcodeCard, insertIndex + 1)
    }

    private fun showEmergencyQuickSheet() {
        val featureEnabled = EmergencyBypassStore.isFeatureEnabled(this)
        if (!featureEnabled) {
            AlertDialog.Builder(this)
                .setTitle(R.string.pref_emergency_title)
                .setMessage(R.string.emergency_disabled_message_controls)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.emergency_open_controls_action) { _, _ ->
                    startActivity(Intent(this, ToggleOptionsActivity::class.java))
                }
                .showAccented()
            return
        }

        val active = EmergencyBypassStore.isActive(this)
        val paused = EmergencyBypassStore.isPaused(this)
        val usedToday = EmergencyBypassStore.hasUsedToday(this)
        val remaining = EmergencyBypassStore.minutesRemaining(this)

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun addAction(label: String, action: () -> Unit) { labels += label; actions += action }

        if (active) {
            addAction(getString(R.string.emergency_action_pause)) {
                if (EmergencyBypassStore.pause(this)) {
                    SwitchModeStore.clearTemporary(this)
                    Toast.makeText(this, getString(R.string.emergency_paused_toast), Toast.LENGTH_SHORT).show()
                    BlockingRuntime.ensureRunning(this)
                }
            }
            addAction(getString(R.string.emergency_action_end)) {
                EmergencyBypassStore.cancel(this)
                SwitchModeStore.clearTemporary(this)
                Toast.makeText(this, getString(R.string.emergency_ended_toast), Toast.LENGTH_SHORT).show()
                BlockingRuntime.ensureRunning(this)
            }
        } else if (paused) {
            addAction(getString(R.string.emergency_action_resume)) {
                if (EmergencyBypassStore.resume(this)) {
                    val mins = EmergencyBypassStore.minutesRemaining(this).coerceAtLeast(1)
                    SwitchModeStore.setTemporarilyDisabled(this, mins * 60_000L)
                    Toast.makeText(this, getString(R.string.emergency_resumed_toast), Toast.LENGTH_SHORT).show()
                    BlockingRuntime.ensureRunning(this)
                }
            }
            addAction(getString(R.string.emergency_action_end)) {
                EmergencyBypassStore.cancel(this)
                SwitchModeStore.clearTemporary(this)
                Toast.makeText(this, getString(R.string.emergency_ended_toast), Toast.LENGTH_SHORT).show()
                BlockingRuntime.ensureRunning(this)
            }
        } else if (!usedToday) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.pref_emergency_title))
                .setMessage(getString(R.string.emergency_action_start_15))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok) { _, _ ->
                    if (EmergencyBypassStore.enableIfAllowed(this, 15)) {
                        SwitchModeStore.setTemporarilyDisabled(this, 15 * 60_000L)
                        Toast.makeText(this, getString(R.string.emergency_enabled_toast, 15), Toast.LENGTH_SHORT).show()
                        BlockingRuntime.ensureRunning(this)
                    } else {
                        Toast.makeText(this, getString(R.string.emergency_used_today), Toast.LENGTH_SHORT).show()
                    }
                }
                .showAccented()
            return
        }

        val title = when {
            active -> getString(R.string.emergency_manage_title_active, remaining)
            paused -> getString(R.string.emergency_manage_title_paused, remaining)
            else -> getString(R.string.pref_emergency_title)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setNegativeButton(R.string.cancel, null)
        if (labels.isNotEmpty()) {
            builder.setItems(labels.toTypedArray()) { _, which -> runCatching { actions[which].invoke() } }
        }
        if (!active && !paused && usedToday) builder.setMessage(R.string.emergency_used_today)
        builder.showAccented()
    }
}
