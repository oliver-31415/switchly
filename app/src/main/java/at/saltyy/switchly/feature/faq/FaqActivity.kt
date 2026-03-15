package at.saltyy.switchly.feature.faq

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText

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
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                val filtered = filterFaqItems(allItems, query)
                adapter = FaqAdapter(filtered)
                recycler.adapter = adapter
            }
        })
    }

    private fun filterFaqItems(source: List<FaqListItem>, query: String): List<FaqListItem> {
        if (query.isBlank()) return source

        val q = query.lowercase()
        val out = mutableListOf<FaqListItem>()

        var currentHeader: FaqListItem.Header? = null
        val buffer = mutableListOf<FaqListItem.Item>()

        fun flush() {
            if (buffer.isNotEmpty()) {
                currentHeader?.let { out += it }
                out += buffer
                buffer.clear()
            }
        }

        for (row in source) {
            when (row) {
                is FaqListItem.Header -> {
                    flush()
                    currentHeader = row
                }

                is FaqListItem.Item -> {
                    val hay = (row.question + "\n" + row.answer).lowercase()
                    if (hay.contains(q)) buffer += row
                }
            }
        }
        flush()

        return out
    }

    private fun buildFaqItems(): List<FaqListItem> {
        val quick = mutableListOf<FaqListItem>()
        val detailed = mutableListOf<FaqListItem>()

        fun MutableList<FaqListItem>.header(@StringRes title: Int) {
            add(FaqListItem.Header(getString(title)))
        }

        fun MutableList<FaqListItem>.item(
            @StringRes question: Int,
            @StringRes answer: Int,
            iconRes: Int? = null
        ) {
            add(
                FaqListItem.Item(
                    question = getString(question),
                    answer = getString(answer),
                    iconRes = iconRes
                )
            )
        }

        // Quick FAQ: beginner/summary answers
        quick.header(R.string.faq_section_idiot_safe)
        quick.item(R.string.faq_q_idiot_nutshell, R.string.faq_a_idiot_nutshell, R.drawable.play_arrow_24)
        quick.item(R.string.faq_q_idiot_nfc_how, R.string.faq_a_idiot_nfc_how, R.drawable.nfc_24)
        quick.item(R.string.faq_q_idiot_nfc, R.string.faq_a_idiot_nfc, R.drawable.nfc_24)
        quick.item(R.string.faq_q_idiot_qr, R.string.faq_a_idiot_qr, R.drawable.qr_code_24)
        quick.item(R.string.faq_q_idiot_both, R.string.faq_a_idiot_both, R.drawable.sync_24)

        // Quick FAQ: core features
        quick.header(R.string.faq_section_features)
        quick.item(R.string.faq_q_idiot_features, R.string.faq_a_idiot_features, R.drawable.apps_24)
        quick.item(R.string.faq_q_reentry, R.string.faq_a_reentry, R.drawable.login_24)
        quick.item(R.string.faq_q_nfc_emergency_tag, R.string.faq_a_nfc_emergency_tag, R.drawable.lock_open_24)
        quick.item(R.string.faq_q_blocked_websites, R.string.faq_a_blocked_websites, R.drawable.language_24)
        quick.item(R.string.faq_q_in_app_blocking, R.string.faq_a_in_app_blocking, R.drawable.security_24)
        quick.item(R.string.faq_q_blocked_notifications, R.string.faq_a_blocked_notifications, R.drawable.notifications_24)
        quick.item(R.string.faq_q_schedule_types, R.string.faq_a_schedule_types, R.drawable.schedule_24)
        quick.item(R.string.faq_q_switchly_lite, R.string.faq_a_switchly_lite, R.drawable.info_24)

        // Quick FAQ: NFC tags
        quick.header(R.string.faq_section_tags)
        quick.item(R.string.faq_q_idiot_tags, R.string.faq_a_idiot_tags, R.drawable.nfc_24)
        quick.item(R.string.faq_q_nfc_tag_types, R.string.faq_a_nfc_tag_types, R.drawable.nfc_24)

        // Quick FAQ: strict/locked modes
        quick.header(R.string.faq_section_gray)
        quick.item(R.string.faq_q_idiot_gray, R.string.faq_a_idiot_gray, R.drawable.lock_24)

        // Quick FAQ: backup and account removal
        quick.header(R.string.faq_section_backup_restore)
        quick.item(R.string.faq_q_idiot_save_data, R.string.faq_a_idiot_save_data, R.drawable.cloud_24)
        quick.item(R.string.faq_q_idiot_delete_account, R.string.faq_a_idiot_delete_account, R.drawable.delete_24)

        // Quick FAQ: premium and support
        quick.header(R.string.faq_header_premium)
        quick.item(R.string.faq_q_premium_how, R.string.faq_a_premium_how, R.drawable.star_24)
        quick.item(R.string.faq_q_donate, R.string.faq_a_donate_clean, R.drawable.star_24)

        // Detailed FAQ: getting started
        detailed.header(R.string.faq_section_switchly_60)
        detailed.item(R.string.faq_q_nutshell, R.string.faq_a_nutshell, R.drawable.play_arrow_24)
        detailed.item(R.string.faq_q_get_started, R.string.faq_a_get_started, R.drawable.help_24)
        detailed.item(R.string.faq_q_tutorial_what, R.string.faq_a_tutorial_what, R.drawable.info_24)

        // Detailed FAQ: quick unlock with NFC
        detailed.header(R.string.faq_section_quick_nfc)
        detailed.item(R.string.faq_q_nfc_basic, R.string.faq_a_nfc_basic, R.drawable.nfc_24)
        detailed.item(R.string.faq_q_nfc_pairing, R.string.faq_a_nfc_pairing, R.drawable.security_24)

        // Detailed FAQ: quick unlock with QR
        detailed.header(R.string.faq_section_quick_qr)
        detailed.item(R.string.faq_q_qr_basic, R.string.faq_a_qr_basic, R.drawable.qr_code_24)

        // Detailed FAQ: combined NFC + QR flows
        detailed.header(R.string.faq_section_nfc_qr_together)
        detailed.item(R.string.faq_q_nfc_and_qr, R.string.faq_a_nfc_and_qr, R.drawable.sync_24)

        // Detailed FAQ: app basics
        detailed.header(R.string.faq_header_general)
        detailed.item(R.string.faq_q_languages, R.string.faq_a_languages, R.drawable.language_24)
        detailed.item(R.string.faq_q_theme, R.string.faq_a_theme, R.drawable.palette_24)

        // Detailed FAQ: QR and NFC
        detailed.header(R.string.faq_header_qr_nfc)
        detailed.item(R.string.faq_q_qr_what, R.string.faq_a_qr_what, R.drawable.qr_code_24)
        detailed.item(R.string.faq_q_qr_generate, R.string.faq_a_qr_generate, R.drawable.add_24)
        detailed.item(R.string.faq_q_qr_scan, R.string.faq_a_qr_scan, R.drawable.qr_code_24)
        detailed.item(R.string.faq_q_nfc, R.string.faq_a_nfc, R.drawable.nfc_24)
        detailed.item(R.string.faq_q_profile_nfc_sync, R.string.faq_a_profile_nfc_sync, R.drawable.switch_account_24)
        detailed.item(R.string.faq_q_nfc_uid_pair, R.string.faq_a_nfc_uid_pair, R.drawable.security_24)
        detailed.item(R.string.faq_q_nfc_paired_tags, R.string.faq_a_nfc_paired_tags, R.drawable.nfc_24)
        detailed.item(R.string.faq_q_nfc_emergency_tag, R.string.faq_a_nfc_emergency_tag, R.drawable.lock_open_24)

        // Detailed FAQ: modes and temporary state changes
        detailed.header(R.string.faq_header_modes)
        detailed.item(R.string.faq_q_temp_disable, R.string.faq_a_temp_disable, R.drawable.toggle_off_24)
        detailed.item(R.string.faq_q_temp_enable, R.string.faq_a_temp_enable, R.drawable.toggle_on_24)
        detailed.item(R.string.faq_q_reentry, R.string.faq_a_reentry, R.drawable.login_24)
        detailed.item(R.string.faq_q_bypass, R.string.faq_a_bypass, R.drawable.lock_open_24)
        detailed.item(R.string.faq_q_require_nfc, R.string.faq_a_require_nfc, R.drawable.security_24)
        detailed.item(R.string.faq_q_disabled_settings, R.string.faq_a_disabled_settings, R.drawable.lock_24)

        // Detailed FAQ: profiles
        detailed.header(R.string.faq_header_profiles)
        detailed.item(R.string.faq_q_profiles, R.string.faq_a_profiles, R.drawable.layers_24)

        // Detailed FAQ: limits and statistics
        detailed.header(R.string.faq_header_limits_usage)
        detailed.item(R.string.faq_q_usage_limit_what, R.string.faq_a_usage_limit_what, R.drawable.alarm_24)
        detailed.item(R.string.faq_q_usage_limit_set, R.string.faq_a_usage_limit_set, R.drawable.tune_24)
        detailed.item(R.string.faq_q_usage_limit_remove, R.string.faq_a_usage_limit_remove, R.drawable.delete_24)
        detailed.item(R.string.faq_q_usage_stats, R.string.faq_a_usage_stats, R.drawable.bar_chart_24)
        detailed.item(R.string.faq_q_usage_overall, R.string.faq_a_usage_overall, R.drawable.bar_chart_24)
        detailed.item(R.string.faq_q_backup_stats, R.string.faq_a_backup_stats, R.drawable.cloud_24)

        // Detailed FAQ: schedules and automations
        detailed.header(R.string.faq_header_schedules)
        detailed.item(R.string.faq_q_schedules_basic, R.string.faq_a_schedules_basic, R.drawable.schedule_24)
        detailed.item(R.string.faq_q_schedules_wifi, R.string.faq_a_schedules_wifi, R.drawable.wifi_24)
        detailed.item(R.string.faq_q_schedules_bluetooth, R.string.faq_a_schedules_bluetooth, R.drawable.bluetooth_24)
        detailed.item(R.string.faq_q_schedules_actions, R.string.faq_a_schedules_actions, R.drawable.dashboard_24)

        // Detailed FAQ: quick settings tile
        detailed.header(R.string.faq_header_quick_actions)
        detailed.item(R.string.faq_q_tile, R.string.faq_a_tile, R.drawable.notifications_24)
        detailed.item(R.string.faq_q_tile_why_missing, R.string.faq_a_tile_why_missing, R.drawable.help_24)

        // Detailed FAQ: permissions and Android system behavior
        detailed.header(R.string.faq_header_permissions)
        detailed.item(R.string.faq_q_usage_access, R.string.faq_a_usage_access, R.drawable.security_24)
        detailed.item(R.string.faq_q_overlay, R.string.faq_a_overlay, R.drawable.app_blocking_black_24)
        detailed.item(R.string.faq_q_recents_preview, R.string.faq_a_recents_preview, R.drawable.lock_24)
        detailed.item(R.string.faq_q_battery, R.string.faq_a_battery, R.drawable.info_24)

        // Detailed FAQ: privacy and device limitations
        detailed.header(R.string.faq_header_privacy)
        detailed.item(R.string.faq_q_privacy, R.string.faq_a_privacy, R.drawable.info_24)
        detailed.item(R.string.faq_q_oem_limits, R.string.faq_a_oem_limits, R.drawable.help_24)

        // Detailed FAQ: backup, restore, and account deletion
        detailed.header(R.string.faq_header_future)
        detailed.item(R.string.faq_q_backup, R.string.faq_a_backup, R.drawable.cloud_upload_24)
        detailed.item(R.string.faq_q_backup_restore, R.string.faq_a_backup_restore, R.drawable.cloud_24)
        detailed.item(R.string.faq_q_delete_account, R.string.faq_a_delete_account, R.drawable.delete_24)

        // Detailed FAQ: premium and analytics
        detailed.header(R.string.faq_header_premium)
        detailed.item(R.string.faq_q_premium_overview, R.string.faq_a_premium_overview, R.drawable.star_24)
        detailed.item(R.string.faq_q_analytics, R.string.faq_a_analytics, R.drawable.bar_chart_24)

        // Detailed FAQ: support and donations
        detailed.header(R.string.faq_header_support)
        detailed.item(R.string.faq_q_donate, R.string.faq_a_donate, R.drawable.star_24)

        // Detailed FAQ: contact and feedback
        detailed.header(R.string.faq_header_contact)
        detailed.item(R.string.faq_q_contact, R.string.faq_a_contact, R.drawable.mail_24)

        return mergeWithoutDuplicateQuestions(primary = quick, secondary = detailed)
    }

    private fun mergeWithoutDuplicateQuestions(
        primary: List<FaqListItem>,
        secondary: List<FaqListItem>
    ): List<FaqListItem> {
        val out = mutableListOf<FaqListItem>()
        val seenQuestions = mutableSetOf<String>()

        fun questionKey(item: FaqListItem.Item): String =
            item.question.trim().lowercase()

        // Keep all primary content as-is, and collect seen questions.
        for (row in primary) {
            out += row
            if (row is FaqListItem.Item) {
                seenQuestions += questionKey(row)
            }
        }

        // Add secondary content only if question is not already shown.
        var pendingHeader: FaqListItem.Header? = null

        for (row in secondary) {
            when (row) {
                is FaqListItem.Header -> pendingHeader = row
                is FaqListItem.Item -> {
                    val key = questionKey(row)
                    if (key !in seenQuestions) {
                        pendingHeader?.let { out += it }
                        pendingHeader = null

                        out += row
                        seenQuestions += key
                    }
                }
            }
        }

        return out
    }
}
