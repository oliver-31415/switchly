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
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import at.saltyy.switchly.BuildConfig
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import at.saltyy.switchly.R
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.premium.PremiumRedeemRuntime
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class PremiumInfoActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusTextView: TextView
    private lateinit var thanksTextView: TextView
    private lateinit var purchaseButton: MaterialButton
    private lateinit var restoreButton: MaterialButton
    private lateinit var redeemButton: MaterialButton

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_premium_info)

        setupToolbar()
        setupViews()
        setupButtons()
        renderState()
    }

    override fun onResume() {
        super.onResume()
        renderState()
    }

    private fun setupToolbar() {
        toolbar = findViewById(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
    }

    private fun setupViews() {
        statusTextView = findViewById(R.id.tvPremiumStatus)
        thanksTextView = findViewById(R.id.tvPremiumThanks)
        purchaseButton = findViewById(R.id.btnPurchasePremium)
        restoreButton = findViewById(R.id.btnRestorePurchases)
        redeemButton = findViewById(R.id.btnRedeemPremiumCode)

        purchaseButton.backgroundTintList = AccentColor.getActiveColor(this)
        purchaseButton.setTextColor(ContextCompat.getColor(this, R.color.font_white))

        restoreButton.isVisible = true
        restoreButton.strokeColor = AccentColor.getActiveColor(this)
        restoreButton.setTextColor(AccentColor.getAccentColorInt(this))

        redeemButton.isVisible = PremiumRedeemRuntime.isRedeemSupportedBuild()
        redeemButton.strokeColor = AccentColor.getActiveColor(this)
        redeemButton.setTextColor(AccentColor.getAccentColorInt(this))
    }

    private fun setupButtons() {
        purchaseButton.setOnClickListener {
            if (PremiumManager.isPremium(this)) {
                Toast.makeText(
                    this,
                    getString(R.string.premium_already_owned),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                PremiumManager.launchPurchase(this, "premium_upgrade")
            }
        }

        restoreButton.setOnClickListener {
            PremiumManager.restorePurchases(this)
        }

        redeemButton.setOnClickListener {
            startActivity(Intent(this, PremiumRedeemActivity::class.java))
        }
    }

    private fun renderState() {
        val isPremium = PremiumManager.isPremium(this)

        if (isPremium) {
            statusTextView.text = getString(R.string.premium_status_active)
            thanksTextView.text = getString(R.string.premium_source_note, PremiumManager.premiumSourceLabel(this))
            thanksTextView.visibility = View.VISIBLE
            purchaseButton.text = getString(R.string.premium_button_thanks)
            purchaseButton.isEnabled = false
            restoreButton.isVisible = !BuildConfig.SWITCHLY_OFFLINE_REDEEM_CODES_ENABLED
            restoreButton.text = if (BuildConfig.SWITCHLY_EXTERNAL_PAYMENTS_ENABLED) {
                getString(R.string.premium_button_manage_external)
            } else {
                getString(R.string.premium_button_restore)
            }
            redeemButton.isVisible = false
        } else {
            statusTextView.text = getString(R.string.premium_status_inactive)
            thanksTextView.visibility = View.GONE

            val premiumSupportedBuild = PremiumManager.isPremiumSupportedBuild()

            if (!premiumSupportedBuild) {
                statusTextView.text = getString(R.string.premium_unavailable_offline_build)
                purchaseButton.text = getString(R.string.premium_button_unavailable_offline)
                purchaseButton.isEnabled = false
                restoreButton.isVisible = false
                redeemButton.isVisible = false
                return
            }

            purchaseButton.text = when {
                BuildConfig.SWITCHLY_EXTERNAL_PAYMENTS_ENABLED ->
                    getString(R.string.premium_button_buy_external, PremiumManager.externalPaymentProviderName())
                BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED ->
                    getString(R.string.premium_button_buy)
                BuildConfig.SWITCHLY_OFFLINE_REDEEM_CODES_ENABLED ->
                    getString(R.string.premium_button_redeem_offline)
                else ->
                    getString(R.string.premium_payments_unavailable)
            }
            purchaseButton.isEnabled = !BuildConfig.SWITCHLY_OFFLINE_REDEEM_CODES_ENABLED

            restoreButton.isVisible = !BuildConfig.SWITCHLY_OFFLINE_REDEEM_CODES_ENABLED
            redeemButton.isVisible = PremiumRedeemRuntime.isRedeemSupportedBuild()
            restoreButton.text = if (BuildConfig.SWITCHLY_EXTERNAL_PAYMENTS_ENABLED) {
                getString(R.string.premium_button_manage_external)
            } else {
                getString(R.string.premium_button_restore)
            }
        }
    }
}
