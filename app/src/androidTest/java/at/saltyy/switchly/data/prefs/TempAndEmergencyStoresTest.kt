package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TempAndEmergencyStoresTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefsName = "switchly_prefs"

    @After
    fun tearDown() {
        // clear prefs after each test
        ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    @Test
    fun tempAllowStore_isAllowed_returnsTrueWhenFutureTimestamp() {
        val pkg = "com.example.app"
        val sp = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val future = System.currentTimeMillis() + 60_000L

        sp.edit()
            .putLong("temp_allow_$pkg", future)
            .apply()

        assertTrue(TempAllowStore.isAllowed(ctx, pkg))
    }

    @Test
    fun tempAllowStore_isAllowed_returnsFalseAndClearsWhenPastTimestamp() {
        val pkg = "com.example.app"
        val sp = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val past = System.currentTimeMillis() - 60_000L

        sp.edit()
            .putLong("temp_allow_$pkg", past)
            .apply()

        assertFalse(TempAllowStore.isAllowed(ctx, pkg))

        // second check: key should be removed
        val after = sp.getLong("temp_allow_$pkg", 0L)
        assertEquals(0L, after)
    }

    @Test
    fun tempReenableStore_shouldReenableNow_trueWhenDueInPast() {
        val sp = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val past = System.currentTimeMillis() - 1_000L

        sp.edit()
            .putLong("temp_reenable_at", past)
            .apply()

        assertTrue(TempReenableStore.shouldReenableNow(ctx))
    }

    @Test
    fun tempReenableStore_shouldReenableNow_falseWhenNotSet() {
        val sp = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        sp.edit().remove("temp_reenable_at").apply()

        assertFalse(TempReenableStore.shouldReenableNow(ctx))
    }

    @Test
    fun emergencyBypassStore_isActive_trueForFutureTimestamp() {
        val sp = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val future = System.currentTimeMillis() + 5 * 60_000L

        sp.edit()
            .putLong("emergency_bypass_until", future)
            .apply()

        assertTrue(EmergencyBypassStore.isActive(ctx))
    }

    @Test
    fun emergencyBypassStore_minutesRemaining_zeroWhenPast() {
        val sp = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val past = System.currentTimeMillis() - 5 * 60_000L

        sp.edit()
            .putLong("emergency_bypass_until", past)
            .apply()

        assertEquals(0, EmergencyBypassStore.minutesRemaining(ctx))
    }
}
