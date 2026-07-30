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

package at.saltyy.switchly.feature.premium

import android.content.Context
import android.os.Bundle
import android.text.InputFilter
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import at.saltyy.switchly.R
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.premium.PremiumRedeemRuntime
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class PremiumRedeemActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var codeLayout: TextInputLayout
    private lateinit var codeInput: TextInputEditText
    private lateinit var statusText: TextView
    private lateinit var redeemButton: MaterialButton

    private var redeemInProgress = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_premium_redeem)

        toolbar = findViewById(R.id.toolbar)
        codeLayout = findViewById(R.id.inputRedeemCodeLayout)
        codeInput = findViewById(R.id.inputRedeemCode)
        statusText = findViewById(R.id.tvRedeemStatus)
        redeemButton = findViewById(R.id.btnRedeemPremiumCode)

        setupToolbar()
        setupInput()
        setupButton()
        renderSupportedState()
    }

    private fun setupToolbar() {
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
    }

    private fun setupInput() {
        codeInput.filters = arrayOf(InputFilter.AllCaps())
        codeLayout.helperText = getString(
            when (PremiumRedeemRuntime.helperMode()) {
                PremiumRedeemRuntime.Mode.ONLINE_SWITCHLY_CODE -> R.string.premium_redeem_code_format_switchly
                PremiumRedeemRuntime.Mode.OFFLINE_CODE -> R.string.premium_redeem_code_format_offline
                PremiumRedeemRuntime.Mode.UNSUPPORTED -> R.string.premium_redeem_code_format
            }
        )
        codeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                redeemCurrentCode()
                true
            } else {
                false
            }
        }
        codeInput.doAfterTextChanged {
            codeLayout.error = null
            statusText.isVisible = false
        }
    }

    private fun setupButton() {
        redeemButton.backgroundTintList = AccentColor.getActiveColor(this)
        redeemButton.setTextColor(ContextCompat.getColor(this, R.color.font_white))
        redeemButton.setOnClickListener { redeemCurrentCode() }
    }

    private fun renderSupportedState() {
        if (PremiumManager.isPremium(this)) {
            codeInput.isEnabled = false
            redeemButton.isEnabled = false
            showStatus(
                getString(R.string.premium_redeem_current_active, PremiumManager.premiumSourceLabel(this)),
                isError = false,
            )
            return
        }

        if (!PremiumRedeemRuntime.isRedeemSupportedBuild()) {
            codeInput.isEnabled = false
            redeemButton.isEnabled = false
            showStatus(getString(R.string.premium_redeem_unsupported_build), isError = true)
        }
    }

    private fun redeemCurrentCode() {
        if (redeemInProgress) {
            return
        }
        if (!PremiumRedeemRuntime.isRedeemSupportedBuild()) {
            showStatus(getString(R.string.premium_redeem_unsupported_build), isError = true)
            return
        }

        val normalized = PremiumRedeemRuntime.normalizeCode(codeInput.text?.toString().orEmpty())
        codeInput.setText(normalized)
        codeInput.setSelection(codeInput.text?.length ?: 0)

        if (!PremiumRedeemRuntime.hasValidFormat(normalized)) {
            codeLayout.error = getString(
                R.string.premium_redeem_error_invalid_format_dynamic,
                PremiumRedeemRuntime.expectedFormatDescription(),
            )
            return
        }

        if (PremiumRedeemRuntime.isWrongBuildCode(normalized)) {
            showStatus(getString(R.string.premium_redeem_error_wrong_build), isError = true)
            return
        }

        setLoading(true)
        PremiumRedeemRuntime.redeem(this, normalized) { result ->
            runOnUiThread {
                setLoading(false)
                if (result.success) {
                    setResult(RESULT_OK)
                    showStatus(getString(R.string.premium_redeem_success), isError = false)
                    redeemButton.text = getString(R.string.close)
                    redeemButton.setOnClickListener { finish() }
                } else {
                    showStatus(messageForReason(result.reason), isError = true)
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        redeemInProgress = loading
        codeInput.isEnabled = !loading
        redeemButton.isEnabled = !loading
        redeemButton.text = if (loading) {
            getString(R.string.premium_redeem_checking)
        } else {
            getString(R.string.premium_redeem_button)
        }
    }

    private fun showStatus(message: String, isError: Boolean) {
        statusText.text = message
        statusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (isError) R.color.status_error else R.color.status_ok,
            )
        )
        statusText.isVisible = true
    }

    private fun messageForReason(reason: PremiumRedeemRuntime.Reason): String = when (reason) {
        PremiumRedeemRuntime.Reason.SIGN_IN_REQUIRED -> getString(R.string.premium_redeem_error_sign_in_required)
        PremiumRedeemRuntime.Reason.INVALID_FORMAT,
        PremiumRedeemRuntime.Reason.INVALID -> getString(R.string.premium_redeem_error_invalid)
        PremiumRedeemRuntime.Reason.USED -> getString(R.string.premium_redeem_error_used)
        PremiumRedeemRuntime.Reason.REVOKED -> getString(R.string.premium_redeem_error_revoked)
        PremiumRedeemRuntime.Reason.EXPIRED -> getString(R.string.premium_redeem_error_expired)
        PremiumRedeemRuntime.Reason.WRONG_BUILD -> getString(R.string.premium_redeem_error_wrong_build)
        PremiumRedeemRuntime.Reason.UNSUPPORTED_BUILD -> getString(R.string.premium_redeem_unsupported_build)
        PremiumRedeemRuntime.Reason.NETWORK,
        PremiumRedeemRuntime.Reason.UNKNOWN,
        PremiumRedeemRuntime.Reason.SUCCESS -> getString(R.string.premium_redeem_error_network)
    }
}
