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

package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit

object LastBlockReasonStore {

    private const val PREFS = "switchly_last_block_reason"
    private const val KEY_TIME = "time_ms"
    private const val KEY_PKG = "pkg"
    private const val KEY_LABEL = "label"
    private const val KEY_PROFILE = "profile"
    private const val KEY_RULE = "rule"
    private const val KEY_MODE = "mode"
    private const val KEY_SOURCE = "source"
    private const val KEY_MATCHED = "matched"
    private const val KEY_RESULT = "result"
    private const val KEY_DETAILS = "details"

    data class Snapshot(
        val timeMillis: Long,
        val pkg: String,
        val label: String,
        val profile: String,
        val rule: String,
        val mode: String,
        val source: String,
        val matched: String,
        val result: String,
        val details: String
    ) {
        fun isFresh(maxAgeMs: Long = 10L * 60L * 1000L): Boolean {
            val age = System.currentTimeMillis() - timeMillis
            return timeMillis > 0L && age in 0..maxAgeMs
        }
    }

    fun mark(
        context: Context,
        pkg: String,
        label: String? = null,
        profile: String? = null,
        rule: String,
        mode: String? = null,
        source: String,
        matched: String? = null,
        result: String? = null,
        details: String? = null
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong(KEY_TIME, System.currentTimeMillis())
            putString(KEY_PKG, pkg)
            putString(KEY_LABEL, label.orEmpty())
            putString(KEY_PROFILE, profile.orEmpty())
            putString(KEY_RULE, rule)
            putString(KEY_MODE, mode.orEmpty())
            putString(KEY_SOURCE, source)
            putString(KEY_MATCHED, matched.orEmpty())
            putString(KEY_RESULT, result.orEmpty())
            putString(KEY_DETAILS, details.orEmpty())
        }
    }

    fun snapshot(context: Context): Snapshot? {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = sp.getLong(KEY_TIME, 0L)
        val pkg = sp.getString(KEY_PKG, null).orEmpty()
        if (time <= 0L || pkg.isBlank()) return null
        return Snapshot(
            timeMillis = time,
            pkg = pkg,
            label = sp.getString(KEY_LABEL, null).orEmpty(),
            profile = sp.getString(KEY_PROFILE, null).orEmpty(),
            rule = sp.getString(KEY_RULE, null).orEmpty(),
            mode = sp.getString(KEY_MODE, null).orEmpty(),
            source = sp.getString(KEY_SOURCE, null).orEmpty(),
            matched = sp.getString(KEY_MATCHED, null).orEmpty(),
            result = sp.getString(KEY_RESULT, null).orEmpty(),
            details = sp.getString(KEY_DETAILS, null).orEmpty()
        )
    }

    fun debugLines(context: Context, maxAgeMs: Long = Long.MAX_VALUE): List<String> {
        val s = snapshot(context) ?: return emptyList()
        if (maxAgeMs != Long.MAX_VALUE && !s.isFresh(maxAgeMs)) return emptyList()
        return buildList {
            add("Blocked by:")
            if (s.profile.isNotBlank()) add("Profile: ${s.profile}")
            if (s.rule.isNotBlank()) add("Rule: ${s.rule}")
            if (s.mode.isNotBlank()) add("Mode: ${s.mode}")
            if (s.source.isNotBlank()) add("Source: ${s.source}")
            if (s.matched.isNotBlank()) add("Matched: ${s.matched}")
            if (s.result.isNotBlank()) add("Result: ${s.result}")
            if (s.details.isNotBlank()) add("Details: ${s.details}")
        }
    }
}
