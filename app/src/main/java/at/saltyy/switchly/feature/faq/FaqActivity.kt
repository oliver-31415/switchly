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

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recycler: RecyclerView
    private lateinit var searchInput: TextInputEditText

    private lateinit var adapter: FaqAdapter
    private var allItems: List<FaqListItem> = emptyList()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_faq)

        setupToolbar()
        setupViews()
        setupRecycler()
        setupSearch()
        loadInitialState()
        renderFaqItems()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
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
        recycler = findViewById(R.id.recycler)
        searchInput = findViewById(R.id.etFaqSearch)
    }

    private fun setupRecycler() {
        recycler.layoutManager = LinearLayoutManager(this)
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderFaqItems(s?.toString()?.trim().orEmpty())
            }
        })
    }

    private fun loadInitialState() {
        allItems = buildFaqItems()
    }

    private fun renderFaqItems(query: String = "") {
        val visibleItems = filterFaqItems(allItems, query)
        adapter = FaqAdapter(visibleItems)
        recycler.adapter = adapter
    }

    private fun filterFaqItems(source: List<FaqListItem>, query: String): List<FaqListItem> {
        if (query.isBlank()) return source

        val lowerCaseQuery = query.lowercase()
        val filteredItems = mutableListOf<FaqListItem>()

        var currentHeader: FaqListItem.Header? = null
        val sectionItems = mutableListOf<FaqListItem.Item>()

        fun flushSection() {
            if (sectionItems.isNotEmpty()) {
                currentHeader?.let { filteredItems += it }
                filteredItems += sectionItems
                sectionItems.clear()
            }
        }

        for (row in source) {
            when (row) {
                is FaqListItem.Header -> {
                    flushSection()
                    currentHeader = row
                }
                is FaqListItem.Item -> {
                    val haystack = (row.question + "\n" + row.answer).lowercase()
                    if (haystack.contains(lowerCaseQuery)) {
                        sectionItems += row
                    }
                }
            }
        }

        flushSection()
        return filteredItems
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

        quick.header(R.string.faq_section_idiot_safe)
        quick.item(R.string.faq_q_idiot_nutshell, R.string.faq_a_idiot_nutshell, R.drawable.play_arrow_24)
        quick.item(R.string.faq_q_idiot_nfc_how, R.string.faq_a_idiot_nfc_how, R.drawable.nfc_24)
        quick.item(R.string.faq_q_idiot_nfc, R.string.faq_a_idiot_nfc, R.drawable.nfc_24)
        quick.item(R.string.faq_q_idiot_qr, R.string.faq_a_idiot_qr, R.drawable.qr_code_24)
        quick.item(R.string.faq_q_idiot_both, R.string.faq_a_idiot_both, R.drawable.sync_24)

        quick.header(R.string.faq_section_features)
        quick.item(R.string.faq_q_idiot_features, R.string.faq_a_idiot_features, R.drawable.apps_24)
        quick.item(R.string.faq_q_control_mode, R.string.faq_a_control_mode, R.drawable.tune_24)
        quick.item(R.string.faq_q_reentry, R.string.faq_a_reentry, R.drawable.login_24)
        quick.item(R.string.faq_q_nfc_emergency_tag, R.string.faq_a_nfc_emergency_tag, R.drawable.lock_open_24)
        quick.item(R.string.faq_q_blocked_websites, R.string.faq_a_blocked_websites, R.drawable.language_24)
        quick.item(R.string.faq_q_in_app_blocking, R.string.faq_a_in_app_blocking, R.drawable.security_24)
        quick.item(R.string.faq_q_blocked_notifications, R.string.faq_a_blocked_notifications, R.drawable.notifications_24)
        quick.item(R.string.faq_q_schedule_types, R.string.faq_a_schedule_types, R.drawable.schedule_24)
        quick.item(R.string.faq_q_switchly_lite, R.string.faq_a_switchly_lite, R.drawable.info_24)

        quick.header(R.string.faq_section_tags)
        quick.item(R.string.faq_q_idiot_tags, R.string.faq_a_idiot_tags, R.drawable.nfc_24)
        quick.item(R.string.faq_q_nfc_tag_types, R.string.faq_a_nfc_tag_types, R.drawable.nfc_24)
        quick.item(R.string.faq_q_nfc_auto_pairing, R.string.faq_a_nfc_auto_pairing, R.drawable.nfc_24)

        quick.header(R.string.faq_section_gray)
        quick.item(R.string.faq_q_idiot_gray, R.string.faq_a_idiot_gray, R.drawable.lock_24)
        quick.item(R.string.faq_q_battery_permission_still_needed, R.string.faq_a_battery_permission_still_needed, R.drawable.battery_24)

        quick.header(R.string.faq_section_backup_restore)
        quick.item(R.string.faq_q_idiot_save_data, R.string.faq_a_idiot_save_data, R.drawable.cloud_24)
        quick.item(R.string.faq_q_idiot_delete_account, R.string.faq_a_idiot_delete_account, R.drawable.delete_24)

        quick.header(R.string.faq_header_premium)
        quick.item(R.string.faq_q_premium_how, R.string.faq_a_premium_how, R.drawable.star_24)
        quick.item(R.string.faq_q_donate, R.string.faq_a_donate_clean, R.drawable.star_24)

        detailed.header(R.string.faq_section_switchly_60)
        detailed.item(R.string.faq_q_nutshell, R.string.faq_a_nutshell, R.drawable.play_arrow_24)
        detailed.item(R.string.faq_q_get_started, R.string.faq_a_get_started, R.drawable.help_24)
        detailed.item(R.string.faq_q_tutorial_what, R.string.faq_a_tutorial_what, R.drawable.info_24)

        detailed.header(R.string.faq_section_quick_nfc)
        detailed.item(R.string.faq_q_nfc_basic, R.string.faq_a_nfc_basic, R.drawable.nfc_24)
        detailed.item(R.string.faq_q_nfc_pairing, R.string.faq_a_nfc_pairing, R.drawable.security_24)

        detailed.header(R.string.faq_section_quick_qr)
        detailed.item(R.string.faq_q_qr_basic, R.string.faq_a_qr_basic, R.drawable.qr_code_24)

        detailed.header(R.string.faq_section_nfc_qr_together)
        detailed.item(R.string.faq_q_nfc_and_qr, R.string.faq_a_nfc_and_qr, R.drawable.sync_24)

        detailed.header(R.string.faq_header_general)
        detailed.item(R.string.faq_q_languages, R.string.faq_a_languages, R.drawable.language_24)
        detailed.item(R.string.faq_q_theme, R.string.faq_a_theme, R.drawable.palette_24)

        detailed.header(R.string.faq_header_qr_nfc)
        detailed.item(R.string.faq_q_qr_what, R.string.faq_a_qr_what, R.drawable.qr_code_24)
        detailed.item(R.string.faq_q_qr_generate, R.string.faq_a_qr_generate, R.drawable.add_24)
        detailed.item(R.string.faq_q_qr_scan, R.string.faq_a_qr_scan, R.drawable.qr_code_24)
        detailed.item(R.string.faq_q_nfc, R.string.faq_a_nfc, R.drawable.nfc_24)
        detailed.item(R.string.faq_q_profile_nfc_sync, R.string.faq_a_profile_nfc_sync, R.drawable.switch_account_24)
        detailed.item(R.string.faq_q_nfc_uid_pair, R.string.faq_a_nfc_uid_pair, R.drawable.security_24)
        detailed.item(R.string.faq_q_nfc_auto_pairing, R.string.faq_a_nfc_auto_pairing, R.drawable.nfc_24)
        detailed.item(R.string.faq_q_nfc_paired_tags, R.string.faq_a_nfc_paired_tags, R.drawable.nfc_24)
        detailed.item(R.string.faq_q_nfc_emergency_tag, R.string.faq_a_nfc_emergency_tag, R.drawable.lock_open_24)

        detailed.header(R.string.faq_header_modes)
        detailed.item(R.string.faq_q_control_mode, R.string.faq_a_control_mode, R.drawable.tune_24)
        detailed.item(R.string.faq_q_mode_schedule, R.string.faq_a_mode_schedule, R.drawable.schedule_24)
        detailed.item(R.string.faq_q_mode_nfc, R.string.faq_a_mode_nfc, R.drawable.nfc_24)
        detailed.item(R.string.faq_q_mode_qr, R.string.faq_a_mode_qr, R.drawable.qr_code_24)
        detailed.item(R.string.faq_q_mode_mixed, R.string.faq_a_mode_mixed, R.drawable.sync_24)
        detailed.item(R.string.faq_q_manual_controls, R.string.faq_a_manual_controls, R.drawable.toggle_on_24)
        detailed.item(R.string.faq_q_temp_disable, R.string.faq_a_temp_disable, R.drawable.toggle_off_24)
        detailed.item(R.string.faq_q_temp_enable, R.string.faq_a_temp_enable, R.drawable.toggle_on_24)
        detailed.item(R.string.faq_q_reentry, R.string.faq_a_reentry, R.drawable.login_24)
        detailed.item(R.string.faq_q_bypass, R.string.faq_a_bypass, R.drawable.lock_open_24)
        detailed.item(R.string.faq_q_require_nfc, R.string.faq_a_require_nfc, R.drawable.security_24)
        detailed.item(R.string.faq_q_disabled_settings, R.string.faq_a_disabled_settings, R.drawable.lock_24)

        detailed.header(R.string.faq_header_profiles)
        detailed.item(R.string.faq_q_profiles, R.string.faq_a_profiles, R.drawable.switch_account_24)

        detailed.header(R.string.faq_header_schedules)
        detailed.item(R.string.faq_q_schedules_basic, R.string.faq_a_schedules_basic, R.drawable.schedule_24)
        detailed.item(R.string.faq_q_schedules_actions, R.string.faq_a_schedules_actions, R.drawable.schedule_24)
        detailed.item(R.string.faq_q_schedules_wifi, R.string.faq_a_schedules_wifi, R.drawable.wifi_24)
        detailed.item(R.string.faq_q_schedules_bluetooth, R.string.faq_a_schedules_bluetooth, R.drawable.bluetooth_24)

        detailed.header(R.string.faq_header_limits_usage)
        detailed.item(R.string.faq_q_usage_limit_what, R.string.faq_a_usage_limit_what, R.drawable.info_24)
        detailed.item(R.string.faq_q_usage_limit_set, R.string.faq_a_usage_limit_set, R.drawable.schedule_24)
        detailed.item(R.string.faq_q_usage_limit_remove, R.string.faq_a_usage_limit_remove, R.drawable.delete_24)
        detailed.item(R.string.faq_q_usage_overall, R.string.faq_a_usage_overall, R.drawable.bar_chart_24)

        detailed.header(R.string.faq_header_general)
        detailed.item(R.string.faq_q_usage_access, R.string.faq_a_usage_access, R.drawable.security_24)
        detailed.item(R.string.faq_q_usage_stats, R.string.faq_a_usage_stats, R.drawable.bar_chart_24)
        detailed.item(R.string.faq_q_overlay, R.string.faq_a_overlay, R.drawable.info_24)
        detailed.item(R.string.faq_q_oem_limits, R.string.faq_a_oem_limits, R.drawable.info_24)
        detailed.item(R.string.faq_q_xiaomi_background, R.string.faq_a_xiaomi_background, R.drawable.battery_24)
        detailed.item(R.string.faq_q_battery, R.string.faq_a_battery, R.drawable.info_24)
        detailed.item(R.string.faq_q_battery_permission_still_needed, R.string.faq_a_battery_permission_still_needed, R.drawable.battery_24)
        detailed.item(R.string.faq_q_tile, R.string.faq_a_tile, R.drawable.dashboard_24)
        detailed.item(R.string.faq_q_tile_why_missing, R.string.faq_a_tile_why_missing, R.drawable.dashboard_24)

        detailed.header(R.string.faq_header_premium)
        detailed.item(R.string.faq_q_privacy, R.string.faq_a_privacy, R.drawable.lock_24)
        detailed.item(R.string.faq_q_premium_overview, R.string.faq_a_premium_overview, R.drawable.star_24)
        detailed.item(R.string.faq_q_premium_how, R.string.faq_a_premium_how, R.drawable.star_24)
        detailed.item(R.string.faq_q_analytics, R.string.faq_a_analytics, R.drawable.bar_chart_24)

        detailed.header(R.string.faq_header_support)
        detailed.item(R.string.faq_q_donate, R.string.faq_a_donate, R.drawable.star_24)

        detailed.header(R.string.faq_header_contact)
        detailed.item(R.string.faq_q_contact, R.string.faq_a_contact, R.drawable.mail_24)
        detailed.item(R.string.faq_q_issue_board, R.string.faq_a_issue_board, R.drawable.info_24)

        return mergeWithoutDuplicateQuestions(primary = quick, secondary = detailed)
    }

    private fun mergeWithoutDuplicateQuestions(
        primary: List<FaqListItem>,
        secondary: List<FaqListItem>
    ): List<FaqListItem> {
        val mergedItems = mutableListOf<FaqListItem>()
        val seenQuestions = mutableSetOf<String>()

        fun questionKey(item: FaqListItem.Item): String =
            item.question.trim().lowercase()

        for (row in primary) {
            mergedItems += row
            if (row is FaqListItem.Item) {
                seenQuestions += questionKey(row)
            }
        }

        var pendingHeader: FaqListItem.Header? = null

        for (row in secondary) {
            when (row) {
                is FaqListItem.Header -> pendingHeader = row
                is FaqListItem.Item -> {
                    val key = questionKey(row)
                    if (key !in seenQuestions) {
                        pendingHeader?.let { mergedItems += it }
                        pendingHeader = null
                        mergedItems += row
                        seenQuestions += key
                    }
                }
            }
        }

        return mergedItems
    }
}
