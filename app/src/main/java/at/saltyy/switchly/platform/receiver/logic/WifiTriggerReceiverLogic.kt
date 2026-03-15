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
