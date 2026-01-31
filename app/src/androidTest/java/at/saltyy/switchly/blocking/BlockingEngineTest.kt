package at.saltyy.switchly.blocking

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.TempAllowStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockingEngineTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefsName = "switchly_prefs"
    private val engine = BlockingEngine()

    @After
    fun tearDown() {
        ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    @Test
    fun shouldBlock_returnsFalseIfTopPackageIsNullOrBlank() {
        SwitchModeStore.setEnabled(ctx, true)

        assertFalse(engine.shouldBlock(ctx, null))
        assertFalse(engine.shouldBlock(ctx, ""))
        assertFalse(engine.shouldBlock(ctx, "   "))
    }

    @Test
    fun shouldBlock_returnsFalseWhenSwitchlyDisabled() {
        SwitchModeStore.setEnabled(ctx, false)
        assertFalse(engine.shouldBlock(ctx, "com.example.app"))
    }

    @Test
    fun shouldBlock_returnsTrueWhenPackageInBlockedListOfActiveProfile() {
        val profileName = "Work"
        ProfileStore.addProfile(ctx, profileName)
        ProfileStore.setCurrent(ctx, profileName)

        SwitchModeStore.setEnabled(ctx, true)
        ProfileStore.setBlockedForProfile(ctx, profileName, setOf("com.social.app"))

        assertTrue(engine.shouldBlock(ctx, "com.social.app"))
        // prefix match
        assertTrue(engine.shouldBlock(ctx, "com.social.app.lite"))
    }

    @Test
    fun shouldBlock_returnsFalseWhenTempAllowStoreAllowsPackage() {
        val profileName = "Work"
        val pkg = "com.social.app"

        ProfileStore.addProfile(ctx, profileName)
        ProfileStore.setCurrent(ctx, profileName)
        SwitchModeStore.setEnabled(ctx, true)
        ProfileStore.setBlockedForProfile(ctx, profileName, setOf(pkg))

        TempAllowStore.allow(ctx, pkg, durationMillis = 60_000L)

        assertFalse(engine.shouldBlock(ctx, pkg))
    }
}
