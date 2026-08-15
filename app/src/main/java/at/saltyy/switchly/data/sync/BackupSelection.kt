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

package at.saltyy.switchly.data.sync

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

// User-controlled backup categories. Full backup stays the default so existing behavior is unchanged.
enum class BackupCategory(
    val id: String,
    val displayName: String,
    val description: String,
    val sensitive: Boolean = false,
) {
    PROFILES(
        id = "profiles",
        displayName = "Profiles",
        description = "Profile names and active profile"
    ),
    BLOCKED_APPS(
        id = "blocked_apps",
        displayName = "Blocked apps/selected apps",
        description = "Selected apps, app limits and profile app lists",
        sensitive = true
    ),
    WEBSITE_RULES(
        id = "website_rules",
        displayName = "Website rules",
        description = "Blocked websites and website limits",
        sensitive = true
    ),
    WEBSITE_BROWSER_SETTINGS(
        id = "website_browser_settings",
        displayName = "Website/browser blocking setup",
        description = "Website blocking toggle, browser detection and supported-browser setup",
        sensitive = true
    ),
    NOTIFICATION_BLOCKING(
        id = "notification_blocking",
        displayName = "Notification blocking setup",
        description = "Blocked-notification toggle and related notification-blocking settings",
        sensitive = true
    ),
    IN_APP_BLOCKING(
        id = "in_app_blocking",
        displayName = "In-app blocking setup",
        description = "YouTube, Instagram, X/Twitter and Snapchat in-app blocking toggles and limits",
        sensitive = true
    ),
    SCHEDULES(
        id = "schedules",
        displayName = "Schedules",
        description = "Normal time/date schedules"
    ),
    LOCATION_SCHEDULES(
        id = "location_schedules",
        displayName = "Location schedules",
        description = "Schedule rules that contain places or coordinates",
        sensitive = true
    ),
    WIFI_SCHEDULES(
        id = "wifi_schedules",
        displayName = "Wi-Fi schedules",
        description = "Schedule rules that contain Wi-Fi names",
        sensitive = true
    ),
    BLUETOOTH_SCHEDULES(
        id = "bluetooth_schedules",
        displayName = "Bluetooth schedules",
        description = "Schedule rules that contain Bluetooth device names or addresses",
        sensitive = true
    ),
    KEYS(
        id = "keys",
        displayName = "NFC/QR/barcode settings",
        description = "Paired NFC tags, QR codes, barcodes and scan action limits",
        sensitive = true
    ),
    CONTROL_SETTINGS(
        id = "control_settings",
        displayName = "Home action/blocking control settings",
        description = "Allowed control channels, Home actions and blocker settings"
    ),
    STRICT_PROTECTION(
        id = "strict_protection",
        displayName = "Uninstall/settings protection",
        description = "Switchly app access lock, Device Admin uninstall protection and settings-bypass protection"
    ),
    STATISTICS(
        id = "statistics",
        displayName = "Statistics/counters",
        description = "Usage, opens, unlocks, blocks, active time and activity history",
        sensitive = true
    ),
    APP_PREFERENCES(
        id = "app_preferences",
        displayName = "App preferences",
        description = "Theme, language, setup flags and other general app preferences"
    );

    companion object {
        fun fromId(id: String): BackupCategory? = values().firstOrNull { it.id == id }
    }
}

data class BackupSelection(val categoryIds: Set<String>) {
    val isFull: Boolean get() = categoryIds.containsAll(FULL_IDS)

    fun includes(category: BackupCategory): Boolean = category.id in categoryIds

    fun displaySummary(maxItems: Int = 4): String {
        if (isFull) {
            return "Full backup"
        }
        val names = BackupCategory.values()
            .filter { includes(it) }
            .map { it.displayName }
        if (names.isEmpty()) {
            return "No categories selected"
        }
        return if (names.size <= maxItems) {
            names.joinToString()
        } else {
            names.take(maxItems).joinToString() + " +${names.size - maxItems} more"
        }
    }

    fun includedNames(): String = BackupCategory.values()
        .filter { includes(it) }
        .joinToString(separator = "\n") { "• ${it.displayName}" }
        .ifBlank { "• None" }

    fun excludedNames(): String = BackupCategory.values()
        .filterNot { includes(it) }
        .joinToString(separator = "\n") { "• ${it.displayName}" }

    fun hasExcludedCategories(): Boolean = excludedNames().isNotBlank()

    companion object {
        private val FULL_IDS = BackupCategory.values().map { it.id }.toSet()

        fun full(): BackupSelection = BackupSelection(FULL_IDS)

        fun privacyFocused(): BackupSelection = BackupSelection(
            setOf(
                BackupCategory.PROFILES.id,
                BackupCategory.BLOCKED_APPS.id,
                BackupCategory.WEBSITE_RULES.id,
                BackupCategory.WEBSITE_BROWSER_SETTINGS.id,
                BackupCategory.NOTIFICATION_BLOCKING.id,
                BackupCategory.IN_APP_BLOCKING.id,
                BackupCategory.SCHEDULES.id,
                BackupCategory.KEYS.id,
                BackupCategory.CONTROL_SETTINGS.id,
                BackupCategory.STRICT_PROTECTION.id,
                BackupCategory.APP_PREFERENCES.id,
            )
        )

        fun profilesOnly(): BackupSelection = BackupSelection(
            setOf(BackupCategory.PROFILES.id, BackupCategory.BLOCKED_APPS.id)
        )

        fun fromIds(ids: Set<String>): BackupSelection {
            val valid = ids.mapNotNull { BackupCategory.fromId(it)?.id }.toSet()
            return BackupSelection(valid)
        }
    }
}

object BackupSelectionStore {
    private const val PREFS = "switchly_backup_selection"
    private const val KEY_SELECTED_IDS = "selected_category_ids"

    fun load(ctx: Context): BackupSelection {
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SELECTED_IDS, null)
            ?.toSet()
        return saved?.let(BackupSelection::fromIds)?.takeIf { it.categoryIds.isNotEmpty() } ?: BackupSelection.full()
    }

    fun save(ctx: Context, selection: BackupSelection) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putStringSet(KEY_SELECTED_IDS, selection.categoryIds) }
    }
}

object BackupCategoryFilter {
    const val FIELD_INCLUDED_CATEGORIES = "included_categories"
    const val FIELD_IS_PARTIAL_BACKUP = "partial_backup"
    private const val FIELD_BACKUP_SCHEMA_VERSION = "backup_schema_version"
    private const val FIELD_CREATED_WITH_VERSION = "created_with_version"
    private const val FIELD_CREATED_WITH_VERSION_CODE = "created_with_version_code"
    private const val FIELD_CREATED_AT = "created_at"
    private const val FIELD_PREFS = "prefs"
    private const val FIELD_SWITCHLY_PREFS = "switchly_prefs"
    private const val FIELD_STATS = "stats"
    private const val FIELD_STATS_DATABASE = "stats_database"
    private const val FIELD_SCHEDULES_PREFS = "schedules_prefs"
    private const val FIELD_UI_HINTS_PREFS = "ui_hints_prefs"

    fun includedCategoryIdsFromPayload(payload: Map<*, *>): Set<String>? {
        val raw = payload[FIELD_INCLUDED_CATEGORIES] ?: return null
        return when (raw) {
            is List<*> -> raw.filterIsInstance<String>().mapNotNull { BackupCategory.fromId(it)?.id }.toSet()
            is Set<*> -> raw.filterIsInstance<String>().mapNotNull { BackupCategory.fromId(it)?.id }.toSet()
            else -> null
        }
    }

    fun isPartialBackup(payload: Map<*, *>): Boolean = when (val raw = payload[FIELD_IS_PARTIAL_BACKUP]) {
        is Boolean -> raw
        else -> includedCategoryIdsFromPayload(payload)?.let { !BackupSelection.fromIds(it).isFull } ?: false
    }

    fun filterPayloadForRestore(payload: Map<*, *>, selection: BackupSelection): Map<String, Any?> {
        val prefsMap = filterDefaultPrefs(stringKeyMap(payload[FIELD_PREFS]), selection)
        val internalMap = filterInternalPrefs(stringKeyMap(payload[FIELD_SWITCHLY_PREFS]), selection)
        val schedulesMap = filterSchedulesPrefs(stringKeyMap(payload[FIELD_SCHEDULES_PREFS]), selection)
        val uiHintsMap = filterUiHintsPrefs(stringKeyMap(payload[FIELD_UI_HINTS_PREFS]), selection)
        val statsMap = filterStats(stringKeyMap(payload[FIELD_STATS]), selection)
        val statsDatabase = if (selection.includes(BackupCategory.STATISTICS)) {
            payload[FIELD_STATS_DATABASE]
        } else {
            null
        }

        return mapOf(
            FIELD_BACKUP_SCHEMA_VERSION to payload[FIELD_BACKUP_SCHEMA_VERSION],
            FIELD_CREATED_WITH_VERSION to payload[FIELD_CREATED_WITH_VERSION],
            FIELD_CREATED_WITH_VERSION_CODE to payload[FIELD_CREATED_WITH_VERSION_CODE],
            FIELD_CREATED_AT to payload[FIELD_CREATED_AT],
            FIELD_PREFS to prefsMap,
            FIELD_SWITCHLY_PREFS to internalMap,
            FIELD_STATS to statsMap,
            FIELD_STATS_DATABASE to statsDatabase,
            FIELD_SCHEDULES_PREFS to schedulesMap,
            FIELD_UI_HINTS_PREFS to uiHintsMap,
            FIELD_INCLUDED_CATEGORIES to selection.categoryIds.toList().sorted(),
            FIELD_IS_PARTIAL_BACKUP to !selection.isFull,
        )
    }

    fun filterDefaultPrefs(src: Map<String, Any?>, selection: BackupSelection): Map<String, Any?> =
        src.filterKeys { key -> selection.matchesAny(categoriesForDefaultPrefsKey(key)) }

    fun filterInternalPrefs(src: Map<String, Any?>, selection: BackupSelection): Map<String, Any?> =
        src.filterKeys { key -> selection.matchesAny(categoriesForInternalPrefsKey(key)) }

    fun filterUiHintsPrefs(src: Map<String, Any?>, selection: BackupSelection): Map<String, Any?> =
        src.filterKeys { key -> selection.matchesAny(categoriesForUiHintsPrefsKey(key)) }

    fun filterStats(src: Map<String, Any?>, selection: BackupSelection): Map<String, Any?> =
        if (selection.includes(BackupCategory.STATISTICS)) src else emptyMap()

    fun filterSchedulesPrefs(src: Map<String, Any?>, selection: BackupSelection): Map<String, Any?> {
        if (!selection.includesAny(
                BackupCategory.SCHEDULES,
                BackupCategory.LOCATION_SCHEDULES,
                BackupCategory.WIFI_SCHEDULES,
                BackupCategory.BLUETOOTH_SCHEDULES,
            )
        ) {
            return emptyMap()
        }

        return src.mapNotNull { (key, value) ->
            val filteredValue = if (key == "items" && value is String) {
                filterScheduleItemsJson(value, selection)
            } else {
                value
            }
            key to filteredValue
        }.toMap()
    }

    private fun filterScheduleItemsJson(raw: String, selection: BackupSelection): String {
        return runCatching {
            val input = JSONArray(raw)
            val output = JSONArray()
            for (i in 0 until input.length()) {
                val item = input.optJSONObject(i) ?: continue
                val isLocation = item.hasNonBlank("locationTrigger") ||
                    !item.isNull("locationLat") ||
                    !item.isNull("locationLng")
                val isWifi = item.hasNonBlank("wifiSsid")
                val isBluetooth = item.hasNonBlank("btDeviceName") || item.hasNonBlank("btDeviceAddress")
                val isPlainTimeSchedule = !isLocation && !isWifi && !isBluetooth

                val include = (isPlainTimeSchedule && selection.includes(BackupCategory.SCHEDULES)) ||
                    (isLocation && selection.includes(BackupCategory.LOCATION_SCHEDULES)) ||
                    (isWifi && selection.includes(BackupCategory.WIFI_SCHEDULES)) ||
                    (isBluetooth && selection.includes(BackupCategory.BLUETOOTH_SCHEDULES))

                if (include) output.put(item)
            }
            output.toString()
        }.getOrDefault(raw)
    }

    private fun isInAppBlockingPrefsKey(key: String): Boolean {
        fun isInAppBaseKey(baseKey: String): Boolean {
            return baseKey == "block_inapp_toggle" ||
                baseKey.startsWith("block_yt_") ||
                baseKey.startsWith("block_ig_") ||
                baseKey.startsWith("block_x_") ||
                baseKey.startsWith("block_snap_") ||
                baseKey.startsWith("in_app_")
        }

        if (isInAppBaseKey(key)) {
            return true
        }

        if (!key.startsWith("p_")) {
            return false
        }
        val rawKey = key.removePrefix("p_")
        val blockSuffix = rawKey.substringAfter("_block_", missingDelimiterValue = "")
        if (blockSuffix.isBlank()) {
            return false
        }
        return isInAppBaseKey("block_$blockSuffix")
    }

    private fun isNotificationBlockingPrefsKey(key: String): Boolean =
        key == "block_notifications_enabled" ||
            key.startsWith("notification_block") ||
            key.startsWith("blocked_notification")

    private fun isWebsiteBrowserSettingsKey(key: String): Boolean =
        key == "block_websites_toggle" ||
            key.startsWith("browser_") ||
            key.startsWith("pref_browser") ||
            key.startsWith("website_browser_") ||
            key.startsWith("web_browser_") ||
            key.startsWith("web_detect") ||
            key.startsWith("website_detect") ||
            key.startsWith("supported_browser")

    private fun isStrictProtectionPrefsKey(key: String): Boolean =
        key.startsWith("pref_app_lock_") ||
            key == "pref_uninstall_friction" ||
            key.startsWith("settings_bypass") ||
            key.startsWith("strict_") ||
            key.startsWith("uninstall_friction")

    private fun categoriesForDefaultPrefsKey(key: String): Set<BackupCategory> = when {
        key.startsWith("usage_limit_reset__") ||
            key.startsWith("usage_limit_min__") ||
            key.startsWith("usage_limit_ever__") ||
            key.startsWith("session_limit_min__") ||
            key.startsWith("session_limit_ever__") ||
            key.startsWith("attempt_limit__") ||
            key.startsWith("attempt_limit_ever__") -> setOf(BackupCategory.BLOCKED_APPS)

        isNotificationBlockingPrefsKey(key) -> setOf(BackupCategory.NOTIFICATION_BLOCKING)

        isInAppBlockingPrefsKey(key) ||
            key.startsWith("inapp_limit_min__") ||
            key.startsWith("surf_rule__") ||
            key.startsWith("surface_limit_") -> setOf(BackupCategory.IN_APP_BLOCKING)

        isWebsiteBrowserSettingsKey(key) -> setOf(BackupCategory.WEBSITE_BROWSER_SETTINGS)

        isStrictProtectionPrefsKey(key) -> setOf(BackupCategory.STRICT_PROTECTION)

        key.startsWith("usage_limit_session_runtime__") ||
            key.startsWith("usage_") ||
            key.startsWith("blocked_") ||
            key.startsWith("runtime_") ||
            key.startsWith("profile_usage_") ||
            key.startsWith("web_usage_") ||
            key.startsWith("surf_usage_day_") ||
            key.startsWith("surface_usage_") ||
            key.startsWith("open_count_") ||
            key.startsWith("app_launch_count_") ||
            key.startsWith("screen_unlock_") ||
            key.startsWith("limit_hit_") ||
            key.startsWith("switchly_runtime_ms_") ||
            key.startsWith("switchly_active_") ||
            key.startsWith("switch_action_count_") ||
            key == "switch_mode_active_since_ms" ||
            key == "switch_mode_limit_session_generation" ||
            key.startsWith("emergency_unlock_count_") ||
            key.startsWith("nfc_scan_count_") ||
            key.startsWith("qr_scan_count_") ||
            key.startsWith("barcode_scan_count_") ||
            key.startsWith("temp_enable_count_") ||
            key.startsWith("scan_code_last_used_") ||
            key.startsWith("scan_code_count_") ||
            key.startsWith("qr_temp_last_") ||
            key.startsWith("qr_temp_count_") ||
            key.startsWith("nfc_td_last_") ||
            key.startsWith("nfc_td_count_") -> setOf(BackupCategory.STATISTICS)

        key.startsWith("domain_block_") ||
            key.startsWith("domain_allowed_") ||
            key.startsWith("domain_rule_") ||
            key.startsWith("domain_limit_") ||
            key.startsWith("website_rule_") ||
            key.startsWith("website_limit_") ||
            key.startsWith("website_block_") ||
            key.startsWith("web_rule_") -> setOf(BackupCategory.WEBSITE_RULES)

        key.startsWith("nfc_td_cfg_daily_") ||
            key.startsWith("nfc_td_cfg_cooldown_") ||
            key.startsWith("nfc_") ||
            key.startsWith("qr_") ||
            key.startsWith("barcode_") ||
            key.startsWith("scan_code_") ||
            key == "enable_paired_uids" ||
            key == "auto_pair_on_write" ||
            key == "enable_reentry_in_write" ||
            key == "enable_emergency_in_write" ||
            key == "limit_temp_disable_tags" ||
            key == "limit_temp_qr_codes" -> setOf(BackupCategory.KEYS)

        key == "home_layout_mode" ||
            key == "home_layout_detailed" ||
            key.startsWith("home_custom_") ||
            key.startsWith("home_quick_tile_") ||
            key == "home_quick_actions_expanded" ||
            key.startsWith("pref_home_") ||
            key.startsWith("pref_show_") -> setOf(BackupCategory.CONTROL_SETTINGS)

        key.startsWith("pref_show_") ||
            key.startsWith("pref_qs_tile_") ||
            key.startsWith("pref_qr_qs_tile_") ||
            key.startsWith("pref_barcode_qs_tile_") ||
            key.startsWith("blocking_") ||
            key.contains("block", ignoreCase = true) -> setOf(BackupCategory.CONTROL_SETTINGS)

        else -> setOf(BackupCategory.APP_PREFERENCES)
    }

    private fun categoriesForInternalPrefsKey(key: String): Set<BackupCategory> = when {
        key == "profiles" || key == "current_profile" ||
            key.startsWith("profile_rule_mode__") ||
            key.startsWith("profile_rule_allow_essentials__") -> setOf(BackupCategory.PROFILES, BackupCategory.BLOCKED_APPS)

        key.startsWith("profile_website_rule_mode__") ->
            setOf(BackupCategory.PROFILES, BackupCategory.WEBSITE_BROWSER_SETTINGS)

        isNotificationBlockingPrefsKey(key) -> setOf(BackupCategory.NOTIFICATION_BLOCKING)

        isInAppBlockingPrefsKey(key) ||
            key.startsWith("inapp_limit_min__") ||
            key.startsWith("surf_rule__") ||
            key.startsWith("surface_limit_") -> setOf(BackupCategory.IN_APP_BLOCKING)

        isWebsiteBrowserSettingsKey(key) -> setOf(BackupCategory.WEBSITE_BROWSER_SETTINGS)

        isStrictProtectionPrefsKey(key) -> setOf(BackupCategory.STRICT_PROTECTION)

        key.startsWith("blocked_apps_") ||
            key.startsWith("allowed_apps_") ||
            key.startsWith("auto_block_new_apps_") ||
            key.startsWith("auto_block_known_apps_") ||
            key.startsWith("usage_limit_min__") ||
            key.startsWith("usage_limit_reset__") ||
            key.startsWith("usage_limit_ever__") ||
            key.startsWith("session_limit_min__") ||
            key.startsWith("session_limit_ever__") ||
            key.startsWith("attempt_limit__") ||
            key.startsWith("attempt_limit_ever__") -> setOf(BackupCategory.BLOCKED_APPS)

        key.startsWith("usage_limit_session_runtime__") ||
            key.startsWith("usage_day_") ||
            key.startsWith("blocked_ms_") ||
            key.startsWith("blocked_count_") ||
            key.startsWith("blocked_attempt_") ||
            key.startsWith("switchly_runtime_ms_") ||
            key.startsWith("switchly_active_") ||
            key.startsWith("switch_action_count_") ||
            key.startsWith("profile_usage_day_") ||
            key.startsWith("surf_usage_day_") ||
            key.startsWith("surface_usage_") ||
            key.startsWith("open_count_") ||
            key.startsWith("app_launch_count_") ||
            key.startsWith("screen_unlock_") ||
            key.startsWith("limit_hit_") ||
            key.startsWith("schedule_exec_count_") ||
            key == "switch_mode_active_since_ms" ||
            key == "switch_mode_limit_session_generation" ||
            key.startsWith("emergency_unlock_count_") ||
            key.startsWith("nfc_scan_count_") ||
            key.startsWith("qr_scan_count_") ||
            key.startsWith("barcode_scan_count_") ||
            key.startsWith("temp_enable_count_") ||
            key.startsWith("scan_code_last_used_") ||
            key.startsWith("scan_code_count_") ||
            key == "blocked_inbox_events" ||
            key == "blocked_inbox_events_updated_at" -> setOf(BackupCategory.STATISTICS)

        key.startsWith("domain_block_") ||
            key.startsWith("domain_allowed_") ||
            key.startsWith("domain_rule_") ||
            key.startsWith("domain_limit_") ||
            key.startsWith("website_rule_") ||
            key.startsWith("website_limit_") ||
            key.startsWith("website_block_") ||
            key.startsWith("web_rule_") -> setOf(BackupCategory.WEBSITE_RULES)

        key.startsWith("nfc_") ||
            key.startsWith("qr_") ||
            key.startsWith("barcode_") ||
            key.startsWith("scan_code_") -> setOf(BackupCategory.KEYS)

        key == "home_layout_mode" ||
            key == "home_layout_detailed" ||
            key.startsWith("home_custom_") ||
            key.startsWith("home_quick_tile_") ||
            key == "home_quick_actions_expanded" ||
            key.startsWith("pref_home_") -> setOf(BackupCategory.CONTROL_SETTINGS)

        key.startsWith("automation_") ||
            key.startsWith("switch_mode_") ||
            key.startsWith("pref_show_") ||
            key.startsWith("temp_") ||
            key.startsWith("emergency_") -> setOf(BackupCategory.CONTROL_SETTINGS)

        else -> setOf(BackupCategory.APP_PREFERENCES)
    }

    private fun categoriesForUiHintsPrefsKey(key: String): Set<BackupCategory> = when {
        key == "home_quick_actions_expanded" ||
            key.startsWith("home_quick_tile_") ||
            key == "temp_mode_discovered" -> setOf(BackupCategory.CONTROL_SETTINGS)

        key == "primary_toggle_tap_count" -> setOf(BackupCategory.STATISTICS)

        else -> setOf(BackupCategory.APP_PREFERENCES)
    }

    private fun BackupSelection.matchesAny(categories: Set<BackupCategory>): Boolean =
        categories.any { includes(it) }

    private fun BackupSelection.includesAny(vararg categories: BackupCategory): Boolean =
        categories.any { includes(it) }

    private fun stringKeyMap(raw: Any?): Map<String, Any?> {
        val map = raw as? Map<*, *> ?: return emptyMap()
        return map.mapNotNull { (key, value) ->
            val stringKey = key as? String ?: return@mapNotNull null
            stringKey to value
        }.toMap()
    }

    private fun org.json.JSONObject.hasNonBlank(key: String): Boolean =
        optString(key, "").isNotBlank()
}
