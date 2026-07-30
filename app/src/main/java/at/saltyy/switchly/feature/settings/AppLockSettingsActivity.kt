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

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.EmergencyPinStore
import at.saltyy.switchly.receiver.DPMReceiver
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

    private lateinit var toolbar: MaterialToolbar
    private lateinit var switchEnabled: SwitchMaterial
    private lateinit var switchBiometric: SwitchMaterial
    private lateinit var switchStrictProtection: SwitchMaterial
    private lateinit var rowSetPin: View
    private lateinit var tvSetPinLabel: TextView
    private var ignoreChanges = false
    private var pendingStrictProtectionEnable = false

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val enabled = isDeviceAdminOrManagedOwnerActive()
        if (pendingStrictProtectionEnable && enabled) {
            AppLockStore.setStrictProtectionEnabled(this, true)
            Toast.makeText(this, R.string.app_lock_strict_protection_enabled, Toast.LENGTH_SHORT).show()
        } else if (pendingStrictProtectionEnable) {
            AppLockStore.setStrictProtectionEnabled(this, false)
            Toast.makeText(this, R.string.app_lock_strict_protection_not_granted, Toast.LENGTH_SHORT).show()
        }
        pendingStrictProtectionEnable = false
        refreshUi()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_lock_settings)
        CustomAccentApplier.applyIfNeeded(this)

        toolbar = findViewById(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        switchEnabled = findViewById(R.id.switchAppLockEnabled)
        switchBiometric = findViewById(R.id.switchAppLockBiometric)
        switchStrictProtection = findViewById(R.id.switchStrictProtection)
        rowSetPin = findViewById(R.id.rowSetPin)
        tvSetPinLabel = findViewById(R.id.tvSetPinLabel)

        findViewById<View>(R.id.rowAppLockEnabled).setOnClickListener { switchEnabled.toggle() }
        findViewById<View>(R.id.rowAppLockBiometric).setOnClickListener { switchBiometric.toggle() }
        findViewById<View>(R.id.rowStrictProtection).setOnClickListener { switchStrictProtection.toggle() }
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

        switchStrictProtection.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreChanges) return@setOnCheckedChangeListener
            if (isChecked) {
                enableStrictProtection()
            } else {
                requestDisableStrictProtection()
            }
        }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        ignoreChanges = true
        val appLockEnabled = AppLockStore.isEnabled(this)
        val strictProtectionEnabled = isStrictProtectionEnabled()
        switchEnabled.isChecked = appLockEnabled
        switchBiometric.isChecked = AppLockStore.isBiometricEnabled(this)
        switchStrictProtection.isChecked = strictProtectionEnabled
        switchStrictProtection.isEnabled = true
        findViewById<View>(R.id.rowStrictProtection).alpha = 1f
        ignoreChanges = false

        tvSetPinLabel.text = getString(
            if (AppLockStore.hasPin(this)) R.string.app_lock_change_pin_title else R.string.app_lock_set_pin_title
        )
        switchBiometric.isEnabled = AppLockStore.hasPin(this)
        findViewById<View>(R.id.rowAppLockBiometric).alpha = if (AppLockStore.hasPin(this)) 1f else 0.65f

        toolbar.subtitle = getString(
            if (appLockEnabled) R.string.state_enabled else R.string.state_disabled
        )
    }

    private fun isStrictProtectionEnabled(): Boolean {
        return AppLockStore.isStrictProtectionEnabled(this) &&
            isDeviceAdminOrManagedOwnerActive()
    }

    private fun isDeviceAdminOrManagedOwnerActive(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java) ?: return false
        val adminComponent = ComponentName(this, DPMReceiver::class.java)
        return dpm.isAdminActive(adminComponent) ||
            dpm.isProfileOwnerApp(packageName) ||
            dpm.isDeviceOwnerApp(packageName)
    }

    private fun enableStrictProtection() {
        if (!AppLockStore.hasPin(this)) {
            refreshUi()
            showSetPinDialog(enableUninstallProtectionAfter = true)
            return
        }

        if (isDeviceAdminOrManagedOwnerActive()) {
            AppLockStore.setStrictProtectionEnabled(this, true)
            refreshUi()
            return
        }

        pendingStrictProtectionEnable = true
        val adminComponent = ComponentName(this, DPMReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.app_lock_strict_protection_admin_explanation)
            )
        }
        refreshUi()
        deviceAdminLauncher.launch(intent)
    }

    private fun requestDisableStrictProtection() {
        refreshUi()
        if (!AppLockStore.hasPin(this)) {
            completeDisableStrictProtection()
            return
        }

        if (AppLockStore.isBiometricEnabled(this) && isBiometricAvailable()) {
            promptBiometricForProtectionDisable()
        } else {
            showPinConfirmationForProtectionDisable()
        }
    }

    private fun promptBiometricForProtectionDisable() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    completeDisableStrictProtection()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        showPinConfirmationForProtectionDisable()
                    }
                }
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_lock_uninstall_disable_auth_title))
            .setSubtitle(getString(R.string.app_lock_uninstall_disable_auth_message))
            .setNegativeButtonText(getString(R.string.app_lock_uninstall_use_pin))
            .build()

        prompt.authenticate(info)
    }

    private fun showPinConfirmationForProtectionDisable() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.app_lock_pin_hint_enter)
            backgroundTintList = AccentColor.getActiveColor(this@AppLockSettingsActivity)
        }
        val container = FrameLayout(this).apply {
            val margin = (24 * resources.displayMetrics.density).toInt()
            setPadding(margin, 0, margin, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_lock_uninstall_disable_auth_title))
            .setMessage(getString(R.string.app_lock_uninstall_disable_auth_message))
            .setView(container)
            .setPositiveButton(getString(R.string.app_lock_uninstall_disable_action), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener {
            dialog.styleSwitchlyDialogButtons()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!AppLockStore.matchesPin(this, input.text?.toString().orEmpty())) {
                    Toast.makeText(this, R.string.app_lock_pin_incorrect, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                completeDisableStrictProtection()
            }
        }
        dialog.setOnDismissListener { refreshUi() }
        dialog.show()
    }

    private fun completeDisableStrictProtection() {
        disableStrictProtection(removeDeviceAdmin = true)
        Toast.makeText(this, R.string.app_lock_strict_protection_disabled, Toast.LENGTH_SHORT).show()
        refreshUi()
    }

    private fun disableStrictProtection(removeDeviceAdmin: Boolean) {
        pendingStrictProtectionEnable = false
        val wasStrictProtectionConfigured = AppLockStore.isStrictProtectionEnabled(this)
        AppLockStore.setStrictProtectionEnabled(this, false)
        if (!removeDeviceAdmin || !wasStrictProtectionConfigured) return

        val dpm = getSystemService(DevicePolicyManager::class.java) ?: return
        if (dpm.isDeviceOwnerApp(packageName) || dpm.isProfileOwnerApp(packageName)) {
            return
        }

        val adminComponent = ComponentName(this, DPMReceiver::class.java)
        if (dpm.isAdminActive(adminComponent)) {
            runCatching { dpm.removeActiveAdmin(adminComponent) }
        }
    }

    private fun showSetPinDialog(
        enableAfter: Boolean = false,
        enableUninstallProtectionAfter: Boolean = false,
    ) {
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
                dialog.dismiss()
                refreshUi()
                if (enableUninstallProtectionAfter) {
                    enableStrictProtection()
                }
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
                EmergencyPinStore.setPin(this, pin)
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
}
