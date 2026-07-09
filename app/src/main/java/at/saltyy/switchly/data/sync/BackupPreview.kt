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
import androidx.preference.PreferenceManager
import at.saltyy.switchly.data.prefs.DomainBlockStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.ScheduleStore
import org.json.JSONArray

object BackupPreview {
    private const val FIELD_SWITCHLY_PREFS = "switchly_prefs"
    private const val FIELD_SCHEDULES_PREFS = "schedules_prefs"
    private const val FIELD_PREFS = "prefs"
    private const val FIELD_BACKUP_SCHEMA_VERSION = "backup_schema_version"
    private const val FIELD_CREATED_WITH_VERSION = "created_with_version"
    private const val FIELD_CREATED_WITH_VERSION_CODE = "created_with_version_code"

    fun buildRestorePreviewText(context: Context, payload: Map<*, *>): String {
        val included = BackupCategoryFilter.includedCategoryIdsFromPayload(payload)
        val schema = payload[FIELD_BACKUP_SCHEMA_VERSION]?.toString()?.ifBlank { null } ?: "legacy"
        val version = payload[FIELD_CREATED_WITH_VERSION]?.toString()?.ifBlank { null } ?: "unknown"
        val versionCode = (payload[FIELD_CREATED_WITH_VERSION_CODE] as? Number)?.toInt()

        val lines = mutableListOf<String>()
        lines += "Format: $schema"
        lines += "Created with: $version" + (versionCode?.let { " ($it)" } ?: "")
        lines += "Included: " + includedCategorySummary(included)
        lines += ""
        lines += restoreLine("Profiles", currentProfileCount(context), backupProfileCount(payload, included, BackupCategory.PROFILES))
        lines += restoreLine("Schedules", currentScheduleCount(context), backupScheduleCount(payload, included))
        lines += restoreLine("Website rules", currentWebsiteRuleCount(context), backupWebsiteRuleCount(payload, included))
        lines += restoreLine("In-app rules", currentInAppRuleCount(context), backupInAppRuleCount(payload, included))
        return lines.joinToString(separator = "\n")
    }

    private fun includedCategorySummary(included: Set<String>?): String {
        if (included == null) return "legacy/full backup"
        if (included.isEmpty()) return "none"
        return BackupCategory.values()
            .filter { it.id in included }
            .joinToString { it.displayName }
            .ifBlank { included.joinToString() }
    }

    private fun restoreLine(label: String, current: Int, backup: Int?): String {
        val backupText = backup?.toString() ?: "not included"
        return "$label: $current -> $backupText"
    }

    private fun currentProfileCount(context: Context): Int =
        runCatching { ProfileStore.getProfiles(context).size }.getOrDefault(0)

    private fun currentScheduleCount(context: Context): Int =
        runCatching { ScheduleStore.getAll(context).size }.getOrDefault(0)

    private fun currentWebsiteRuleCount(context: Context): Int {
        return runCatching {
            ProfileStore.getProfiles(context).sumOf { profile ->
                DomainBlockStore.getDomainsForProfile(context, profile).size +
                    DomainBlockStore.getAllowedDomainsForProfile(context, profile).size
            }
        }.getOrDefault(0)
    }

    private fun currentInAppRuleCount(context: Context): Int {
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context).all
        val internal = context.getSharedPreferences("switchly_prefs", Context.MODE_PRIVATE).all
        return countInAppEntries(defaultPrefs) + countInAppEntries(internal)
    }

    private fun backupProfileCount(payload: Map<*, *>, included: Set<String>?, category: BackupCategory): Int? {
        if (included != null && category.id !in included) return null
        val internal = payload[FIELD_SWITCHLY_PREFS] as? Map<*, *> ?: return 0
        return collectionSize(internal["profiles"])
    }

    private fun backupScheduleCount(payload: Map<*, *>, included: Set<String>?): Int? {
        val scheduleCats = setOf(
            BackupCategory.SCHEDULES.id,
            BackupCategory.LOCATION_SCHEDULES.id,
            BackupCategory.WIFI_SCHEDULES.id,
            BackupCategory.BLUETOOTH_SCHEDULES.id
        )
        if (included != null && included.none { it in scheduleCats }) return null
        val schedules = payload[FIELD_SCHEDULES_PREFS] as? Map<*, *> ?: return 0
        val raw = schedules["items"] as? String ?: return 0
        return runCatching { JSONArray(raw).length() }.getOrDefault(0)
    }

    private fun backupWebsiteRuleCount(payload: Map<*, *>, included: Set<String>?): Int? {
        val websiteCats = setOf(BackupCategory.WEBSITE_RULES.id, BackupCategory.WEBSITE_BROWSER_SETTINGS.id)
        if (included != null && included.none { it in websiteCats }) return null
        val prefs = payload[FIELD_PREFS] as? Map<*, *> ?: return 0
        return prefs.entries
            .filter { (key, _) ->
                val k = key as? String ?: return@filter false
                k.startsWith("domain_block_domains") || k.startsWith("domain_allowed_domains")
            }
            .sumOf { (_, value) -> collectionSize(value) }
    }

    private fun backupInAppRuleCount(payload: Map<*, *>, included: Set<String>?): Int? {
        if (included != null && BackupCategory.IN_APP_BLOCKING.id !in included) return null
        val defaultPrefs = payload[FIELD_PREFS] as? Map<*, *> ?: emptyMap<Any, Any>()
        val internal = payload[FIELD_SWITCHLY_PREFS] as? Map<*, *> ?: emptyMap<Any, Any>()
        return countInAppEntries(defaultPrefs) + countInAppEntries(internal)
    }

    private fun countInAppEntries(prefs: Map<*, *>): Int {
        return prefs.entries.count { (key, value) ->
            val k = key as? String ?: return@count false
            val v = value as? Boolean ?: return@count false
            if (!v) return@count false
            isInAppRuleKey(k)
        } + prefs.entries.count { (key, value) ->
            val k = key as? String ?: return@count false
            val v = value as? Number ?: return@count false
            val intValue = v.toInt()
            (k.startsWith("surf_rule__") && intValue != 0) ||
                (k.startsWith("inapp_limit_min__") && intValue > 0)
        }
    }

    private fun isInAppRuleKey(key: String): Boolean {
        if (key == "block_inapp_toggle") return true
        if (key.startsWith("block_yt_") ||
            key.startsWith("block_ig_") ||
            key.startsWith("block_x_") ||
            key.startsWith("block_snap_")
        ) {
            return true
        }
        if (!key.startsWith("p_")) return false
        return key.contains("_block_yt_") ||
            key.contains("_block_ig_") ||
            key.contains("_block_x_") ||
            key.contains("_block_snap_") ||
            key.endsWith("_block_inapp_toggle")
    }

    private fun collectionSize(value: Any?): Int {
        return when (value) {
            is Collection<*> -> value.filterNotNull().size
            is Array<*> -> value.filterNotNull().size
            is String -> if (value.isBlank()) 0 else 1
            else -> 0
        }
    }
}
