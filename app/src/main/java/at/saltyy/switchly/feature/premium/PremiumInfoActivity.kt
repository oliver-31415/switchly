package at.saltyy.switchly.feature.premium

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import at.saltyy.switchly.R
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class PremiumInfoActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvThanks: TextView
    private lateinit var btnPurchase: MaterialButton
    private lateinit var btnRestore: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_premium_info)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        tvStatus = findViewById(R.id.tvPremiumStatus)
        tvThanks = findViewById(R.id.tvPremiumThanks)
        btnPurchase = findViewById(R.id.btnPurchasePremium)
        btnRestore = findViewById(R.id.btnRestorePurchases)

        // Purchase button: filled using the accent color
        btnPurchase.backgroundTintList = AccentColor.getActiveColor(this)
        btnPurchase.setTextColor(ContextCompat.getColor(this, R.color.font_white))

        // Keep restore visible (placed below the support note area).
        btnRestore.isVisible = true
        btnRestore.strokeColor = AccentColor.getActiveColor(this)
        btnRestore.setTextColor(AccentColor.getAccentColorInt(this))

        btnPurchase.setOnClickListener {
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

        btnRestore.setOnClickListener {
            PremiumManager.refreshFromPlay(this)
            Toast.makeText(
                this,
                getString(R.string.premium_checking_purchases),
                Toast.LENGTH_SHORT
            ).show()
        }

        updateUi()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun updateUi() {
        val isPremium = PremiumManager.isPremium(this)

        if (isPremium) {
            tvStatus.text = getString(R.string.premium_status_active)
            tvThanks.visibility = View.VISIBLE
            btnPurchase.text = getString(R.string.premium_button_thanks)
            btnPurchase.isEnabled = false
        } else {
            tvStatus.text = getString(R.string.premium_status_inactive)
            tvThanks.visibility = View.GONE
            btnPurchase.text = getString(R.string.premium_button_buy)
            btnPurchase.isEnabled = true
        }

    }
}
