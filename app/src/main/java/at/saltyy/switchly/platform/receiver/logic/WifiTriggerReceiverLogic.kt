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

package at.saltyy.switchly.platform.receiver.logic

import at.saltyy.switchly.data.prefs.WifiRuleStore

// Pure Wi‑Fi rule evaluation logic.
object WifiTriggerReceiverLogic {

    data class Match(
        val ruleId: Long,
        val profile: String
    )

    // @return the first enabled rule that matches the given SSID (case-insensitive), or null if no rule matches/SSID is blank.
    fun matchProfileForSsid(
        ssid: String?,
        rules: List<WifiRuleStore.WifiRule>
    ): Match? {
        val s = ssid?.trim().orEmpty()
        if (s.isEmpty()) return null

        val rule = rules
            .asSequence()
            .filter { it.enabled }
            .firstOrNull { it.ssid.trim().equals(s, ignoreCase = true) }
            ?: return null

        return Match(ruleId = rule.id, profile = rule.profile)
    }

    fun hasActiveRules(rules: List<WifiRuleStore.WifiRule>): Boolean =
        rules.any { it.enabled }
}
