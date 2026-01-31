package at.saltyy.switchly.feature.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AutostartStore
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.NotificationBlockStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.feature.onboarding.QuickTileHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial

class ToggleOptionsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_toggle_options)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        val ctx = this
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)

        var ignoreNfcRequiredListener = false

        // --- Switches (Additional features)
        val switchRequireNfcUnlock = findViewById<SwitchMaterial>(R.id.switchRequireNfcUnlock)
        val switchShowQrButton = findViewById<SwitchMaterial>(R.id.switchShowQrButton)

        // --- Switches (Protection)
        val switchBlockNotifications = findViewById<SwitchMaterial>(R.id.switchBlockNotifications)
        val switchAutostart = findViewById<SwitchMaterial>(R.id.switchAutostart)

        // --- Switches (UI & Info)
        val switchEmergency = findViewById<SwitchMaterial>(R.id.switchEmergency)
        val switchShowNext = findViewById<SwitchMaterial>(R.id.switchShowNextSchedule)

        // Rows clickable
        val rowRequireNfcUnlock = findViewById<android.view.View>(R.id.rowRequireNfcUnlock)
        val rowShowQrButton = findViewById<android.view.View>(R.id.rowShowQrButton)
        val rowQuickTile = findViewById<android.view.View>(R.id.rowQuickTile)
        val switchQuickTile = findViewById<SwitchMaterial>(R.id.switchQuickTile)

        val rowBlockNotifs = findViewById<android.view.View>(R.id.rowBlockNotifications)
        val rowAutostart = findViewById<android.view.View>(R.id.rowAutostart)

        val rowEmergency = findViewById<android.view.View>(R.id.rowEmergency)
        val rowShowNext = findViewById<android.view.View>(R.id.rowShowNextSchedule)

        // -------------------------
        // Initial state
        // -------------------------
        switchRequireNfcUnlock.isChecked = SwitchModeStore.isNfcRequiredForDisable(ctx)
        switchShowQrButton.isChecked = sp.getBoolean(KEY_SHOW_QR_CODE, false)

        switchBlockNotifications.isChecked = NotificationBlockStore.isEnabled(ctx)
        switchAutostart.isChecked = AutostartStore.isEnabled(ctx)

        switchEmergency.isChecked = EmergencyBypassStore.isFeatureEnabled(ctx)
        switchShowNext.isChecked = sp.getBoolean(KEY_SHOW_NEXT_SCHEDULE, false)

        // Quick tile is not a "real" enable/disable setting (Android doesn't allow removing tiles programmatically).
        // We store whether the user wants the tile / has requested it.
        switchQuickTile.isChecked = sp.getBoolean(KEY_QS_TILE_REQUESTED, false)

        // Row click toggles switch
        rowRequireNfcUnlock.setOnClickListener { switchRequireNfcUnlock.toggle() }
        rowShowQrButton.setOnClickListener { switchShowQrButton.toggle() }

        // Quick Tile: switch triggers add flow, disabling shows how-to-remove hint
        val addQuickTile: () -> Unit = {
            val requested = QuickTileHelper.requestAddTileIfAvailable(this)
            if (!requested) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.qs_tile_add_hint),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
        rowQuickTile.setOnClickListener { switchQuickTile.toggle() }

        // Switch behavior
        switchQuickTile.setOnCheckedChangeListener { _, isChecked ->
            sp.edit { putBoolean(KEY_QS_TILE_REQUESTED, isChecked) }

            if (isChecked) {
                addQuickTile()
            } else {
                // Can't remove tiles programmatically -> guide user
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.qs_tile_remove_hint),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

        rowBlockNotifs.setOnClickListener { switchBlockNotifications.toggle() }
        rowAutostart.setOnClickListener { switchAutostart.toggle() }

        rowEmergency.setOnClickListener { switchEmergency.toggle() }
        rowShowNext.setOnClickListener { switchShowNext.toggle() }

        // -------------------------
        // Listeners
        // -------------------------

        // Require NFC to unlock (Hard Lock)
        switchRequireNfcUnlock.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreNfcRequiredListener) return@setOnCheckedChangeListener

            val enabled = SwitchModeStore.isEnabled(ctx)
            val emergencyActive = EmergencyBypassStore.isActive(ctx)

            // While Switchly is enabled, don't allow disabling NFC-required unless emergency bypass is active.
            if (enabled && !isChecked && !emergencyActive) {
                Toast.makeText(
                    ctx,
                    getString(R.string.toast_disable_requires_nfc),
                    Toast.LENGTH_SHORT
                ).show()

                // revert toggle (without retriggering listener)
                ignoreNfcRequiredListener = true
                switchRequireNfcUnlock.isChecked = true
                ignoreNfcRequiredListener = false
                return@setOnCheckedChangeListener
            }

            val msg = if (isChecked) {
                getString(R.string.nfc_required_confirm_enable_msg)
            } else {
                getString(R.string.nfc_required_confirm_disable_msg)
            }

            MaterialAlertDialogBuilder(ctx)
                .setTitle(getString(R.string.nfc_required_confirm_title))
                .setMessage(msg)
                .setPositiveButton(if (isChecked) R.string.nfc_action_enable else R.string.nfc_action_disable) { _, _ ->
                    SwitchModeStore.setNfcRequiredForDisable(ctx, isChecked)
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    ignoreNfcRequiredListener = true
                    switchRequireNfcUnlock.isChecked = !isChecked
                    ignoreNfcRequiredListener = false
                }
                .setOnCancelListener {
                    ignoreNfcRequiredListener = true
                    switchRequireNfcUnlock.isChecked = !isChecked
                    ignoreNfcRequiredListener = false
                }
                .show()
        }

        // QR toggle = nur UI (Button oben sichtbar)
        switchShowQrButton.setOnCheckedChangeListener { _, isChecked ->
            sp.edit { putBoolean(KEY_SHOW_QR_CODE, isChecked) }
        }

        // Autostart
        switchAutostart.setOnCheckedChangeListener { _, isChecked ->
            AutostartStore.setEnabled(ctx, isChecked)
        }

        // Block notifications
        switchBlockNotifications.setOnCheckedChangeListener { _, isChecked ->
            NotificationBlockStore.setEnabled(ctx, isChecked)

            if (isChecked && !NotificationBlockStore.hasListenerAccess(ctx)) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.toast_notification_listener_required),
                    Snackbar.LENGTH_LONG
                )
                    .setAction(R.string.permissions_btn_permissions) {
                        runCatching {
                            startActivity(Intent(ctx, PermissionsActivity::class.java))
                        }.onFailure {
                            runCatching {
                                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        }
                    }
                    .show()
            }
        }

        // Emergency unlock
        switchEmergency.setOnCheckedChangeListener { _, isChecked ->
            EmergencyBypassStore.setFeatureEnabled(ctx, isChecked)
        }

        // Show next schedule
        switchShowNext.setOnCheckedChangeListener { _, isChecked ->
            sp.edit { putBoolean(KEY_SHOW_NEXT_SCHEDULE, isChecked) }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        const val KEY_SHOW_NEXT_SCHEDULE = "pref_show_next_schedule"
        const val KEY_SHOW_QR_CODE = "pref_show_qr_code"
        const val KEY_QS_TILE_REQUESTED = "pref_qs_tile_requested"

        fun start(context: Context) {
            context.startActivity(Intent(context, ToggleOptionsActivity::class.java))
        }
    }
}
