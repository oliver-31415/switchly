package at.saltyy.switchly

import android.content.Context
import at.saltyy.switchly.blocking.BlockingRuntime

/**
 * Central helper to start the blocking watcher.
 */
object SwitchlyCore {

    fun ensureRunning(context: Context) {
        // always use appContext so we don't leak an Activity
        val appCtx = context.applicationContext
        BlockingRuntime.ensureRunning(appCtx)
    }
}
