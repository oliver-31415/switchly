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

package at.saltyy.switchly.feature.settings

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.core.content.edit
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.security.AppLockStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial

class AppLockSettingsActivity : AppCompatActivity() {

    private lateinit var switchEnabled: SwitchMaterial
    private lateinit var switchBiometric: SwitchMaterial
    private lateinit var tvStatus: TextView
    private lateinit var switchUninstallFriction: SwitchMaterial
    private lateinit var rowSetPin: View
    private lateinit var tvSetPinLabel: TextView
    private var ignoreChanges = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_lock_settings)
        CustomAccentApplier.applyIfNeeded(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        switchEnabled = findViewById(R.id.switchAppLockEnabled)
        switchBiometric = findViewById(R.id.switchAppLockBiometric)
        switchUninstallFriction = findViewById(R.id.switchUninstallFriction)
        tvStatus = findViewById(R.id.tvAppLockStatus)
        rowSetPin = findViewById(R.id.rowSetPin)
        tvSetPinLabel = findViewById(R.id.tvSetPinLabel)

        findViewById<View>(R.id.rowAppLockEnabled).setOnClickListener { switchEnabled.toggle() }
        findViewById<View>(R.id.rowAppLockBiometric).setOnClickListener { switchBiometric.toggle() }
        findViewById<View>(R.id.rowUninstallFriction).setOnClickListener { switchUninstallFriction.toggle() }
        rowSetPin.setOnClickListener { showSetPinDialog() }
        findViewById<View>(R.id.rowEmergencyPin).setOnClickListener { showEmergencyPinDialog() }

        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreChanges) return@setOnCheckedChangeListener
            if (isChecked && !AppLockStore.hasPin(this)) {
                ignoreChanges = true
                switchEnabled.isChecked = false
                ignoreChanges = false
                showSetPinDialog(enableAfter = true)
                return@setOnCheckedChangeListener
            }
            AppLockStore.setEnabled(this, isChecked)
            refreshUi()
        }

        switchBiometric.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreChanges) return@setOnCheckedChangeListener
            if (isChecked && !AppLockStore.hasPin(this)) {
                ignoreChanges = true
                switchBiometric.isChecked = false
                ignoreChanges = false
                Toast.makeText(this, R.string.app_lock_setup_pin_first, Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            if (isChecked && !isBiometricAvailable()) {
                ignoreChanges = true
                switchBiometric.isChecked = false
                ignoreChanges = false
                Toast.makeText(this, R.string.app_lock_biometric_not_available, Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            AppLockStore.setBiometricEnabled(this, isChecked)
            refreshUi()
        }

        switchUninstallFriction.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreChanges) return@setOnCheckedChangeListener
            AutomationModeStore.setUninstallFrictionEnabled(this, isChecked)
        }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        ignoreChanges = true
        switchEnabled.isChecked = AppLockStore.isEnabled(this)
        switchBiometric.isChecked = AppLockStore.isBiometricEnabled(this)
        switchUninstallFriction.isChecked = AutomationModeStore.isUninstallFrictionEnabled(this)
        ignoreChanges = false

        tvSetPinLabel.text = getString(
            if (AppLockStore.hasPin(this)) R.string.app_lock_change_pin_title else R.string.app_lock_set_pin_title
        )
        switchBiometric.isEnabled = AppLockStore.hasPin(this)
        findViewById<View>(R.id.rowAppLockBiometric).alpha = if (AppLockStore.hasPin(this)) 1f else 0.65f

        tvStatus.text = when {
            !AppLockStore.isEnabled(this) -> getString(R.string.app_lock_status_off)
            AppLockStore.isBiometricEnabled(this) -> getString(R.string.app_lock_status_pin_biometric)
            else -> getString(R.string.app_lock_status_pin_only)
        }
    }

    private fun showSetPinDialog(enableAfter: Boolean = false) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.app_lock_pin_hint_new)
            backgroundTintList = AccentColor.getActiveColor(this@AppLockSettingsActivity)
        }

        val container = FrameLayout(this).apply {
            val margin = (24 * resources.displayMetrics.density).toInt()
            setPadding(margin, 0, margin, 0)
            addView(input, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_lock_pin_title))
            .setMessage(getString(R.string.app_lock_pin_message))
            .setView(container)
            .setPositiveButton(getString(R.string.save), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener {
            dialog.styleSwitchlyDialogButtons()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text?.toString()?.trim().orEmpty()
                if (pin.length < 4) {
                    Toast.makeText(this, R.string.app_lock_pin_too_short, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                AppLockStore.setPin(this, pin)
                if (enableAfter) {
                    AppLockStore.setEnabled(this, true)
                }
                Toast.makeText(this, R.string.app_lock_pin_set, Toast.LENGTH_SHORT).show()
                refreshUi()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showEmergencyPinDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.emergency_pin_choose_hint)
            backgroundTintList = AccentColor.getActiveColor(this@AppLockSettingsActivity)
        }

        val container = FrameLayout(this).apply {
            val margin = (24 * resources.displayMetrics.density).toInt()
            setPadding(margin, 0, margin, 0)
            addView(input, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.emergency_pin_title))
            .setMessage(getString(R.string.emergency_pin_message))
            .setView(container)
            .setPositiveButton(getString(R.string.save), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener {
            dialog.styleSwitchlyDialogButtons()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text?.toString()?.trim().orEmpty()
                if (pin.length < 4) {
                    Toast.makeText(this, R.string.emergency_pin_too_short, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit { putString(KEY_EMERGENCY_PIN, pin) }
                Toast.makeText(this, R.string.emergency_pin_changed, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun isBiometricAvailable(): Boolean {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        return BiometricManager.from(this).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    companion object {
        private const val PREFS = "switchly_prefs"
        private const val KEY_EMERGENCY_PIN = "pref_emergency_pin"
    }
}
