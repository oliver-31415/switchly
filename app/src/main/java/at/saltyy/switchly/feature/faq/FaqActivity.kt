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
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import androidx.activity.addCallback
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class FaqActivity : AppCompatActivity() {

    private data class FaqCategory(
        val id: String,
        val title: String,
        val subtitle: String,
        val iconRes: Int,
        val items: List<FaqListItem.Item>
    )

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recycler: RecyclerView
    private lateinit var searchInput: TextInputEditText

    private lateinit var adapter: FaqAdapter
    private lateinit var emptyState: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptyBody: TextView
    private lateinit var clearSearchButton: MaterialButton

    private var categories: List<FaqCategory> = emptyList()
    private var currentCategory: FaqCategory? = null
    private var openedDirectlyToCategory = false
    private var requestedQuestionResId = 0

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
        setupBackHandling()
        loadInitialState()
        renderFaqItems()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (currentCategory != null && !openedDirectlyToCategory) {
            showCategoryOverview(clearSearch = true)
        } else {
            onBackPressedDispatcher.onBackPressed()
        }
        return true
    }

    private fun setupToolbar() {
        toolbar = findViewById(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onSupportNavigateUp() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
        val toolbarColor = toolbarForegroundColor()
        toolbar.navigationIcon?.mutate()?.setTint(toolbarColor)
        toolbar.setTitleTextColor(toolbarColor)
    }

    private fun toolbarForegroundColor(): Int {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (night) {
            Color.WHITE
        } else {
            Color.BLACK
        }
    }

    private fun setupViews() {
        recycler = findViewById(R.id.recycler)
        searchInput = findViewById(R.id.etFaqSearch)
        emptyState = findViewById(R.id.layoutFaqEmpty)
        emptyTitle = findViewById(R.id.tvFaqEmptyTitle)
        emptyBody = findViewById(R.id.tvFaqEmptyBody)
        clearSearchButton = findViewById(R.id.btnFaqClearSearch)

        emptyTitle.text = getString(R.string.faq_empty_title)
        emptyBody.text = getString(R.string.faq_empty_body)
        clearSearchButton.setOnClickListener {
            searchInput.setText("")
            searchInput.requestFocus()
        }
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

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this) {
            if (currentCategory != null && !openedDirectlyToCategory) {
                showCategoryOverview(clearSearch = true)
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun loadInitialState() {
        categories = buildFaqCategories()
        val requestedCategory = intent.getStringExtra(EXTRA_CATEGORY)
        requestedQuestionResId = intent.getIntExtra(EXTRA_QUESTION_RES_ID, 0)
        currentCategory = categories.firstOrNull { it.id == requestedCategory }
        openedDirectlyToCategory = currentCategory != null
        currentCategory?.let { toolbar.title = it.title }
    }

    private fun openCategory(folder: FaqListItem.Folder) {
        val category = categories.firstOrNull { it.id == folder.id } ?: return
        currentCategory = category
        openedDirectlyToCategory = false
        toolbar.title = category.title
        if (!searchInput.text.isNullOrBlank()) {
            searchInput.setText("")
        } else {
            renderFaqItems()
        }
    }

    private fun showCategoryOverview(clearSearch: Boolean) {
        currentCategory = null
        openedDirectlyToCategory = false
        toolbar.title = getString(R.string.faq_screen_title)
        if (clearSearch && !searchInput.text.isNullOrBlank()) {
            searchInput.setText("")
        } else {
            renderFaqItems()
        }
    }

    private fun renderFaqItems(query: String = searchInput.text?.toString()?.trim().orEmpty()) {
        val visibleItems = when {
            query.isBlank() && currentCategory == null -> categoryFolderItems()
            query.isBlank() -> currentCategory?.items.orEmpty()
            currentCategory != null -> filterFaqItems(currentCategory?.items.orEmpty(), query)
            else -> filterFaqItems(searchableItems(), query)
        }

        val initialExpandedPosition = if (requestedQuestionResId != 0) {
            val requestedQuestion = getString(requestedQuestionResId)
            visibleItems.indexOfFirst { item ->
                item is FaqListItem.Item && item.question == requestedQuestion
            }
        } else {
            RecyclerView.NO_POSITION
        }

        adapter = FaqAdapter(
            items = visibleItems,
            onFolderClick = ::openCategory,
            initialExpandedPosition = initialExpandedPosition
        )
        recycler.adapter = adapter

        if (initialExpandedPosition != RecyclerView.NO_POSITION) {
            requestedQuestionResId = 0
            recycler.post { recycler.scrollToPosition(initialExpandedPosition) }
        }

        val showEmpty = query.isNotBlank() && visibleItems.isEmpty()
        emptyState.visibility = if (showEmpty) View.VISIBLE else View.GONE
        recycler.visibility = if (showEmpty) View.GONE else View.VISIBLE
        clearSearchButton.visibility = if (query.isBlank()) View.GONE else View.VISIBLE
    }

    private fun categoryFolderItems(): List<FaqListItem> {
        return categories.map { category ->
            FaqListItem.Folder(
                id = category.id,
                title = category.title,
                subtitle = category.subtitle,
                articleCount = category.items.size,
                iconRes = category.iconRes
            )
        }
    }

    private fun searchableItems(): List<FaqListItem> {
        return categories.flatMap { category ->
            listOf(FaqListItem.Header(category.title)) + category.items
        }
    }

    private fun filterFaqItems(source: List<FaqListItem>, query: String): List<FaqListItem> {
        if (query.isBlank()) {
            return source
        }

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
                is FaqListItem.Folder -> Unit
            }
        }

        flushSection()
        return filteredItems
    }

    private fun buildFaqCategories(): List<FaqCategory> {
        fun item(@StringRes question: Int, @StringRes answer: Int, iconRes: Int? = null): FaqListItem.Item {
            return FaqListItem.Item(
                question = getString(question),
                answer = getString(answer),
                iconRes = iconRes
            )
        }

        fun category(
            id: String,
            @StringRes title: Int,
            @StringRes subtitle: Int,
            iconRes: Int,
            items: List<FaqListItem.Item>
        ): FaqCategory {
            return FaqCategory(
                id = id,
                title = getString(title),
                subtitle = getString(subtitle),
                iconRes = iconRes,
                items = dedupe(items)
            )
        }

        return listOf(
            category(
                id = "start",
                title = R.string.faq_category_getting_started,
                subtitle = R.string.faq_category_getting_started_summary,
                iconRes = R.drawable.play_arrow_24,
                items = listOf(
                    item(R.string.faq_q_get_started, R.string.faq_a_get_started, R.drawable.play_arrow_24),
                    item(R.string.faq_q_nutshell, R.string.faq_a_nutshell, R.drawable.info_24),
                    item(R.string.faq_q_tutorial_what, R.string.faq_a_tutorial_what, R.drawable.help_24),
                    item(R.string.faq_q_profiles, R.string.faq_a_profiles, R.drawable.switch_account_24),
                    item(R.string.faq_q_profile_rule_scopes, R.string.faq_a_profile_rule_scopes, R.drawable.switch_account_24),
                    item(R.string.faq_q_control_mode, R.string.faq_a_control_mode, R.drawable.tune_24),
                    item(R.string.faq_q_idiot_gray, R.string.faq_a_idiot_gray, R.drawable.lock_24)
                )
            ),
            category(
                id = "background",
                title = R.string.faq_category_background_access,
                subtitle = R.string.faq_category_background_access_summary,
                iconRes = R.drawable.battery_24,
                items = listOf(
                    item(R.string.faq_q_background_access_checklist, R.string.faq_a_background_access_checklist, R.drawable.battery_24),
                    item(R.string.faq_q_android_battery_popup, R.string.faq_a_android_battery_popup, R.drawable.battery_24),
                    item(R.string.faq_q_device_background_steps, R.string.faq_a_device_background_steps, R.drawable.battery_24),
                    item(R.string.faq_q_xiaomi_background, R.string.faq_a_xiaomi_background, R.drawable.battery_24),
                    item(R.string.faq_q_battery, R.string.faq_a_battery, R.drawable.info_24),
                    item(R.string.faq_q_battery_permission_still_needed, R.string.faq_a_battery_permission_still_needed, R.drawable.battery_24),
                    item(R.string.faq_q_location_schedules_reliability, R.string.faq_a_location_schedules_reliability, R.drawable.location_on_24),
                    item(R.string.faq_q_maps_empty_gray, R.string.faq_a_maps_empty_gray, R.drawable.location_on_24)
                )
            ),
            category(
                id = "blocking",
                title = R.string.faq_category_blocking,
                subtitle = R.string.faq_category_blocking_summary,
                iconRes = R.drawable.apps_24,
                items = listOf(
                    item(R.string.faq_q_idiot_features, R.string.faq_a_idiot_features, R.drawable.apps_24),
                    item(R.string.faq_q_block_allow_modes, R.string.faq_a_block_allow_modes, R.drawable.tune_24),
                    item(R.string.faq_q_blocked_websites, R.string.faq_a_blocked_websites, R.drawable.language_24),
                    item(R.string.faq_q_website_rule_modes, R.string.faq_a_website_rule_modes, R.drawable.language_24),
                    item(R.string.faq_q_in_app_blocking, R.string.faq_a_in_app_blocking, R.drawable.security_24),
                    item(R.string.faq_q_in_app_supported_apps, R.string.faq_a_in_app_supported_apps, R.drawable.apps_24),
                    item(R.string.faq_q_usage_access, R.string.faq_a_usage_access, R.drawable.security_24),
                    item(R.string.faq_q_overlay, R.string.faq_a_overlay, R.drawable.info_24),
                    item(R.string.faq_q_switchly_disabled_no_blocking, R.string.faq_a_switchly_disabled_no_blocking, R.drawable.toggle_off_24)
                )
            ),
            category(
                id = "notifications",
                title = R.string.faq_category_notifications,
                subtitle = R.string.faq_category_notifications_summary,
                iconRes = R.drawable.notifications_24,
                items = listOf(
                    item(R.string.faq_q_blocked_notifications, R.string.faq_a_blocked_notifications, R.drawable.notifications_24),
                    item(R.string.faq_q_usage_stats, R.string.faq_a_usage_stats, R.drawable.bar_chart_24),
                    item(R.string.faq_q_usage_overall, R.string.faq_a_usage_overall, R.drawable.bar_chart_24),
                    item(R.string.faq_q_stats_storage, R.string.faq_a_stats_storage, R.drawable.cloud_24),
                    item(R.string.faq_q_troubleshooting_first_steps, R.string.faq_a_troubleshooting_first_steps, R.drawable.help_24)
                )
            ),
            category(
                id = "tags",
                title = R.string.faq_category_nfc_qr,
                subtitle = R.string.faq_category_nfc_qr_summary,
                iconRes = R.drawable.nfc_24,
                items = listOf(
                    item(R.string.faq_q_idiot_nfc_how, R.string.faq_a_idiot_nfc_how, R.drawable.nfc_24),
                    item(R.string.faq_q_idiot_nfc, R.string.faq_a_idiot_nfc, R.drawable.nfc_24),
                    item(R.string.faq_q_idiot_qr, R.string.faq_a_idiot_qr, R.drawable.qr_code_24),
                    item(R.string.faq_q_custom_action_uri, R.string.faq_a_custom_action_uri, R.drawable.info_24),
                    item(R.string.faq_q_barcode_setup, R.string.faq_a_barcode_setup, R.drawable.barcode_24),
                    item(R.string.faq_q_nfc_tag_types, R.string.faq_a_nfc_tag_types, R.drawable.nfc_24),
                    item(R.string.faq_q_own_nfc_tag, R.string.faq_a_own_nfc_tag, R.drawable.nfc_24),
                    item(R.string.faq_q_official_switchly_tags, R.string.faq_a_official_switchly_tags, R.drawable.nfc_24),
                    item(R.string.faq_q_nfc_tag_write_fails, R.string.faq_a_nfc_tag_write_fails, R.drawable.info_24),
                    item(R.string.faq_q_nfc_action_examples, R.string.faq_a_nfc_action_examples, R.drawable.nfc_24),
                    item(R.string.faq_q_nfc_uid_pair, R.string.faq_a_nfc_uid_pair, R.drawable.security_24),
                    item(R.string.faq_q_nfc_paired_tags, R.string.faq_a_nfc_paired_tags, R.drawable.nfc_24),
                    item(R.string.faq_q_nfc_emergency_tag, R.string.faq_a_nfc_emergency_tag, R.drawable.lock_open_24),
                    item(R.string.faq_q_nfc_and_qr, R.string.faq_a_nfc_and_qr, R.drawable.sync_24)
                )
            ),
            category(
                id = "schedules",
                title = R.string.faq_category_schedules_limits,
                subtitle = R.string.faq_category_schedules_limits_summary,
                iconRes = R.drawable.schedule_24,
                items = listOf(
                    item(R.string.faq_q_schedule_types, R.string.faq_a_schedule_types, R.drawable.schedule_24),
                    item(R.string.faq_q_schedules_basic, R.string.faq_a_schedules_basic, R.drawable.schedule_24),
                    item(R.string.faq_q_schedules_actions, R.string.faq_a_schedules_actions, R.drawable.schedule_24),
                    item(R.string.faq_q_schedules_wifi, R.string.faq_a_schedules_wifi, R.drawable.wifi_24),
                    item(R.string.faq_q_schedules_bluetooth, R.string.faq_a_schedules_bluetooth, R.drawable.bluetooth_24),
                    item(R.string.faq_q_schedules_location, R.string.faq_a_schedules_location, R.drawable.location_on_24),
                    item(R.string.faq_q_usage_limit_what, R.string.faq_a_usage_limit_what, R.drawable.info_24),
                    item(R.string.faq_q_usage_limit_set, R.string.faq_a_usage_limit_set, R.drawable.schedule_24),
                    item(R.string.faq_q_usage_limit_remove, R.string.faq_a_usage_limit_remove, R.drawable.delete_24)
                )
            ),
            category(
                id = "advanced",
                title = R.string.faq_category_advanced,
                subtitle = R.string.faq_category_advanced_summary,
                iconRes = R.drawable.lock_24,
                items = listOf(
                    item(R.string.faq_q_uninstall_friction, R.string.faq_a_uninstall_friction, R.drawable.lock_24),
                    item(R.string.faq_q_advanced_mode_open, R.string.faq_a_advanced_mode_open, R.drawable.info_24),
                    item(R.string.faq_q_advanced_mode_device_admin, R.string.faq_a_advanced_mode_device_admin, R.drawable.security_24),
                    item(R.string.faq_q_advanced_mode_adb_requirements, R.string.faq_a_advanced_mode_adb_requirements, R.drawable.info_24),
                    item(R.string.faq_q_reentry, R.string.faq_a_reentry, R.drawable.login_24),
                    item(R.string.faq_q_bypass, R.string.faq_a_bypass, R.drawable.lock_open_24),
                    item(R.string.faq_q_require_nfc, R.string.faq_a_require_nfc, R.drawable.security_24),
                    item(R.string.faq_q_disabled_settings, R.string.faq_a_disabled_settings, R.drawable.lock_24)
                )
            ),
            category(
                id = "account",
                title = R.string.faq_category_account_support,
                subtitle = R.string.faq_category_account_support_summary,
                iconRes = R.drawable.cloud_24,
                items = listOf(
                    item(R.string.faq_q_idiot_save_data, R.string.faq_a_idiot_save_data, R.drawable.cloud_24),
                    item(R.string.faq_q_idiot_delete_account, R.string.faq_a_idiot_delete_account, R.drawable.delete_24),
                    item(R.string.faq_q_privacy, R.string.faq_a_privacy, R.drawable.lock_24),
                    item(R.string.faq_q_support_report_contents, R.string.faq_a_support_report_contents, R.drawable.mail_24),
                    item(R.string.faq_q_premium_overview, R.string.faq_a_premium_overview, R.drawable.star_24),
                    item(R.string.faq_q_premium_how, R.string.faq_a_premium_how, R.drawable.star_24),
                    item(R.string.faq_q_support_switchly, R.string.faq_a_support_switchly, R.drawable.star_24),
                    item(R.string.faq_q_contact, R.string.faq_a_contact, R.drawable.mail_24)
                )
            )
        )
    }

    private fun dedupe(items: List<FaqListItem.Item>): List<FaqListItem.Item> {
        val seen = mutableSetOf<String>()
        return items.filter { item ->
            seen.add(item.question.trim().lowercase())
        }
    }
    companion object {
        const val CATEGORY_GETTING_STARTED = "start"
        const val CATEGORY_BACKGROUND_ACCESS = "background"
        private const val EXTRA_CATEGORY = "extra_category"
        private const val EXTRA_QUESTION_RES_ID = "extra_question_res_id"

        fun intent(
            context: Context,
            category: String? = null,
            @StringRes questionResId: Int = 0
        ): Intent = Intent(context, FaqActivity::class.java).apply {
            if (!category.isNullOrBlank()) {
                putExtra(EXTRA_CATEGORY, category)
            }
            if (questionResId != 0) {
                putExtra(EXTRA_QUESTION_RES_ID, questionResId)
            }
        }
    }

}
