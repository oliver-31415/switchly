package at.saltyy.switchly.blocking

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppWatcherServiceTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun ensureRunning_doesNotThrow_andStopIsCallable() {
        AppWatcherService.ensureRunning(ctx)
        AppWatcherService.stop(ctx)
    }
}
