package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileStoreMigrationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefsName = "switchly_prefs"

    @After
    fun tearDown() {
        ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    @Test
    fun legacyCsvBlockedApps_areMigratedToStringSet() {
        val profile = "Work"
        val sp = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val key = "blocked_apps_$profile"

        sp.edit()
            .putString(key, "com.a.one, com.b.two ,  com.c.three")
            .apply()

        val blocked = ProfileStore.getBlockedForProfile(ctx, profile)
        val setInPrefs = sp.getStringSet(key, emptySet()) ?: emptySet()

        val expected = setOf(
            "com.a.one",
            "com.b.two",
            "com.c.three"
        )

        assertEquals(expected, blocked.toSet())
        assertEquals(expected, setInPrefs)
    }

    @Test
    fun emptyProfileName_returnsEmptyList() {
        val blocked = ProfileStore.getBlockedForProfile(ctx, "")
        assertTrue(blocked.isEmpty())
    }
}
