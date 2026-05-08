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
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.security.AppLockManager
import at.saltyy.switchly.security.AppLockStore
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.ActivityTransitionCompat
import at.saltyy.switchly.util.LocaleHelper

class AppLockActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    private lateinit var etPin: EditText
    private lateinit var btnUnlock: Button
    private lateinit var btnBiometric: Button
    private var unlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)

        if (!AppLockStore.isEnabled(this)) {
            AppLockManager.markUnlocked()
            finish()
            ActivityTransitionCompat.finishWithoutAnimation(this)
            return
        }

        setContentView(R.layout.activity_app_lock)
        CustomAccentApplier.applyIfNeeded(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!unlocked) {
                    AppLockManager.clearPromptFlag()
                    finishAffinity()
                } else {
                    finish()
                }
            }
        })

        findViewById<TextView>(R.id.tvTitle).text = getString(R.string.app_lock_unlock_title)
        findViewById<TextView>(R.id.tvSubtitle).text = getString(R.string.app_lock_unlock_message)
        etPin = findViewById<EditText>(R.id.etPin).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        btnUnlock = findViewById<Button>(R.id.btnUnlock)
        btnBiometric = findViewById<Button>(R.id.btnBiometric)

        btnUnlock.setOnClickListener {
            val pin = etPin.text?.toString().orEmpty()
            if (AppLockStore.matchesPin(this, pin)) {
                unlockSuccess()
            } else {
                AppLogStore.append(this, "AppLock", "Unlock failed reason=pin_mismatch")
                Toast.makeText(this, R.string.app_lock_pin_incorrect, Toast.LENGTH_SHORT).show()
            }
        }

        val biometricAvailable = isBiometricAvailable()
        btnBiometric.isVisible = AppLockStore.isBiometricEnabled(this) && biometricAvailable
        btnBiometric.setOnClickListener { promptBiometric() }

        if (btnBiometric.isVisible && savedInstanceState == null) {
            btnBiometric.post { promptBiometric() }
        }
    }

    private fun isBiometricAvailable(): Boolean {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        return BiometricManager.from(this).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun promptBiometric() {
        if (!isBiometricAvailable()) {
            AppLogStore.append(this, "AppLock", "Unlock failed reason=biometric_unavailable")
            Toast.makeText(this, R.string.app_lock_biometric_not_available, Toast.LENGTH_SHORT).show()
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlockSuccess()
                }
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_lock_biometric_prompt_title))
            .setSubtitle(getString(R.string.app_lock_biometric_prompt_subtitle))
            .setNegativeButtonText(getString(R.string.cancel))
            .build()

        prompt.authenticate(info)
    }

    private fun unlockSuccess() {
        unlocked = true
        AppLockManager.markUnlocked()
        setResult(RESULT_OK)
        finish()
        ActivityTransitionCompat.finishWithoutAnimation(this)
    }

    override fun onDestroy() {
        if (!unlocked) {
            AppLockManager.clearPromptFlag()
        }
        super.onDestroy()
    }
}
