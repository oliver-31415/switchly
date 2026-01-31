package at.saltyy.switchly.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SwitchlyTest {

    @Test
    fun actionRefresh_hasExpectedValue() {
        assertEquals("at.saltyy.switchly.ACTION_REFRESH", Switchly.ACTION_REFRESH)
    }

    @Test
    fun actionToggle_hasExpectedValue() {
        assertEquals("at.saltyy.switchly.ACTION_TOGGLE", Switchly.ACTION_TOGGLE)
    }
}
