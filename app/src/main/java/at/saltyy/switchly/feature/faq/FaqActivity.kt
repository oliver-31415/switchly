package at.saltyy.switchly.feature.faq

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import android.content.Context
import at.saltyy.switchly.util.LocaleHelper

class FaqActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: FaqAdapter
    private lateinit var searchInput: TextInputEditText

    private lateinit var allItems: List<FaqListItem>

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_faq)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        recycler = findViewById(R.id.recycler)
        searchInput = findViewById(R.id.etFaqSearch)

        recycler.layoutManager = LinearLayoutManager(this)

        allItems = buildFaqItems()
        adapter = FaqAdapter(allItems)
        recycler.adapter = adapter

        setupSearch()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                val filtered = filterFaqItems(allItems, query)
                adapter = FaqAdapter(filtered)
                recycler.adapter = adapter
            }
        })
    }

    private fun filterFaqItems(all: List<FaqListItem>, query: String): List<FaqListItem> {
        if (query.isBlank()) return all

        val q = query.lowercase()
        val result = mutableListOf<FaqListItem>()

        val currentHeader: FaqListItem.Header? = null
        val buffer = mutableListOf<FaqListItem.Item>()

        fun flushGroup() {
            currentHeader?.let { header ->
                if (buffer.isNotEmpty()) {
                    result += header
                    result.addAll(buffer)
                }
            } ?: run {
                if (buffer.isNotEmpty()) {
                    // items without header (shouldn't really happen, but just in case)
                    result.addAll(buffer)
                }
            }
            buffer.clear()
        }

        for (item in all) {
            when (item) {
                is FaqListItem.Header -> {
                    flushGroup()
                }
                is FaqListItem.Item -> {
                    val text = (item.question + " " + item.answer).lowercase()
                    if (text.contains(q)) {
                        buffer += item
                    }
                }
            }
        }
        flushGroup()

        // If nothing matches, just show empty list (or you could add a "no results" header)
        return if (result.isEmpty()) emptyList() else result
    }

    private fun buildFaqItems(): List<FaqListItem> {
        val list = mutableListOf<FaqListItem>()

        // ----- General -----
        list += FaqListItem.Header(getString(R.string.faq_header_general))
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_languages),
            answer = getString(R.string.faq_a_languages),
            iconRes = R.drawable.language_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_theme),
            answer = getString(R.string.faq_a_theme),
            iconRes = R.drawable.palette_24
        )

        // ----- QR & NFC -----
        list += FaqListItem.Header(getString(R.string.faq_header_qr_nfc))
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_qr_what),
            answer = getString(R.string.faq_a_qr_what),
            iconRes = R.drawable.qr_code_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_qr_generate),
            answer = getString(R.string.faq_a_qr_generate),
            iconRes = R.drawable.add_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_qr_scan),
            answer = getString(R.string.faq_a_qr_scan),
            iconRes = R.drawable.qr_code_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_nfc),
            answer = getString(R.string.faq_a_nfc),
            iconRes = R.drawable.nfc_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_profile_nfc_sync),
            answer = getString(R.string.faq_a_profile_nfc_sync),
            iconRes = R.drawable.switch_account_24
        )

        list += FaqListItem.Item(
            question = getString(R.string.faq_q_nfc_uid_pair),
            answer = getString(R.string.faq_a_nfc_uid_pair),
            iconRes = R.drawable.security_24
        )

        // ----- Modes / Temporary enable/disable -----
        list += FaqListItem.Header(getString(R.string.faq_header_modes))
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_temp_disable),
            answer = getString(R.string.faq_a_temp_disable),
            iconRes = R.drawable.toggle_off_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_temp_enable),
            answer = getString(R.string.faq_a_temp_enable),
            iconRes = R.drawable.toggle_on_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_bypass),
            answer = getString(R.string.faq_a_bypass),
            iconRes = R.drawable.lock_open_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_require_nfc),
            answer = getString(R.string.faq_a_require_nfc),
            iconRes = R.drawable.security_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_disabled_settings),
            answer = getString(R.string.faq_a_disabled_settings),
            iconRes = R.drawable.lock_24
        )

        // ----- Profiles -----
        list += FaqListItem.Header(getString(R.string.faq_header_profiles))
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_profiles),
            answer = getString(R.string.faq_a_profiles),
            iconRes = R.drawable.layers_24
        )

        // ----- Limits & Usage -----
        list += FaqListItem.Header(getString(R.string.faq_header_limits_usage))
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_usage_limit_what),
            answer = getString(R.string.faq_a_usage_limit_what),
            iconRes = R.drawable.alarm_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_usage_limit_set),
            answer = getString(R.string.faq_a_usage_limit_set),
            iconRes = R.drawable.tune_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_usage_limit_remove),
            answer = getString(R.string.faq_a_usage_limit_remove),
            iconRes = R.drawable.delete_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_usage_stats),
            answer = getString(R.string.faq_a_usage_stats),
            iconRes = R.drawable.bar_chart_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_usage_overall),
            answer = getString(R.string.faq_a_usage_overall),
            iconRes = R.drawable.bar_chart_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_backup_stats),
            answer = getString(R.string.faq_a_backup_stats),
            iconRes = R.drawable.cloud_24
        )

        // ----- Schedules / Automation -----
        list += FaqListItem.Header(getString(R.string.faq_header_schedules))
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_schedules_basic),
            answer = getString(R.string.faq_a_schedules_basic),
            iconRes = R.drawable.schedule_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_schedules_wifi),
            answer = getString(R.string.faq_a_schedules_wifi),
            iconRes = R.drawable.wifi_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_schedules_bluetooth),
            answer = getString(R.string.faq_a_schedules_bluetooth),
            iconRes = R.drawable.bluetooth_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_schedules_actions),
            answer = getString(R.string.faq_a_schedules_actions),
            iconRes = R.drawable.dashboard_24
        )

        // ----- Quick Tile -----
        list += FaqListItem.Header(getString(R.string.faq_header_quick_actions))
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_tile),
            answer = getString(R.string.faq_a_tile),
            iconRes = R.drawable.notifications_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_tile_why_missing),
            answer = getString(R.string.faq_a_tile_why_missing),
            iconRes = R.drawable.help_24
        )

        // ----- Permissions / System -----
        list += FaqListItem.Header(getString(R.string.faq_header_permissions))
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_usage_access),
            answer = getString(R.string.faq_a_usage_access),
            iconRes = R.drawable.security_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_overlay),
            answer = getString(R.string.faq_a_overlay),
            iconRes = R.drawable.app_blocking_surface_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_recents_preview),
            answer = getString(R.string.faq_a_recents_preview),
            iconRes = R.drawable.lock_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_battery),
            answer = getString(R.string.faq_a_battery),
            iconRes = R.drawable.info_24
        )

        // ----- Privacy -----
        list += FaqListItem.Header(getString(R.string.faq_header_privacy))
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_privacy),
            answer = getString(R.string.faq_a_privacy),
            iconRes = R.drawable.info_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_oem_limits),
            answer = getString(R.string.faq_a_oem_limits),
            iconRes = R.drawable.help_24
        )

        // ----- Data & Backups -----
        list += FaqListItem.Header(getString(R.string.faq_header_future))
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_backup),
            answer = getString(R.string.faq_a_backup),
            iconRes = R.drawable.cloud_upload_24
        )

        // ----- Premium & Analytics -----
        list += FaqListItem.Header(getString(R.string.faq_header_premium))
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_premium_overview),
            answer = getString(R.string.faq_a_premium_overview),
            iconRes = R.drawable.star_24
        )
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_analytics),
            answer = getString(R.string.faq_a_analytics),
            iconRes = R.drawable.bar_chart_24
        )

        // ----- Support / Donate -----
        list += FaqListItem.Header(getString(R.string.faq_header_support))
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_donate),
            answer = getString(R.string.faq_a_donate),
            iconRes = R.drawable.star_24
        )

        // ----- Contact / Feedback -----
        list += FaqListItem.Header(getString(R.string.faq_header_contact))
        list += FaqListItem.Item(
            question = getString(R.string.faq_q_contact),
            answer = getString(R.string.faq_a_contact),
            iconRes = R.drawable.mail_24 
        )

        return list
    }

}
