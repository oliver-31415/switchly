package at.saltyy.switchly.platform.receiver.logic

import at.saltyy.switchly.data.prefs.WifiRuleStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WifiTriggerReceiverLogicTest {

    @Test
    fun noSsidReturnsNull() {
        val rules = listOf(WifiRuleStore.WifiRule(1L, "HomeWifi", "home", enabled = true))
        assertNull(WifiTriggerReceiverLogic.matchProfileForSsid(null, rules))
        assertNull(WifiTriggerReceiverLogic.matchProfileForSsid("   ", rules))
    }

    @Test
    fun disabledRulesAreIgnored() {
        val rules = listOf(WifiRuleStore.WifiRule(1L, "HomeWifi", "home", enabled = false))
        assertNull(WifiTriggerReceiverLogic.matchProfileForSsid("HomeWifi", rules))
        assertFalse(WifiTriggerReceiverLogic.hasActiveRules(rules))
    }

    @Test
    fun ssidMatchIsCaseInsensitiveAndTrims() {
        val rules = listOf(
            WifiRuleStore.WifiRule(1L, "HomeWifi", "home", enabled = true),
            WifiRuleStore.WifiRule(2L, "Office", "work", enabled = true)
        )

        val match = WifiTriggerReceiverLogic.matchProfileForSsid("  homewifi  ", rules)
        assertNotNull(match)
        assertEquals(1L, match!!.ruleId)
        assertEquals("home", match.profile)
    }

    @Test
    fun firstMatchingRuleWins() {
        val rules = listOf(
            WifiRuleStore.WifiRule(1L, "Cafe", "A", enabled = true),
            WifiRuleStore.WifiRule(2L, "Cafe", "B", enabled = true)
        )

        val match = WifiTriggerReceiverLogic.matchProfileForSsid("Cafe", rules)
        assertEquals("A", match!!.profile)
    }
}
