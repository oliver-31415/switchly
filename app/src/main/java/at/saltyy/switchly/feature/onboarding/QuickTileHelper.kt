package at.saltyy.switchly.feature.onboarding

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import at.saltyy.switchly.R
import java.util.concurrent.Executors

/**
 * Helper for requesting the Switchly Quick Settings tile.
 */
object QuickTileHelper {

    /**
     * Android 13+ (API 33):
     * Uses the official system dialog to request adding a Quick Settings tile.
     *
     * Returns true if the request could be started successfully.
     */
    fun requestAddTileIfAvailable(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val sb = activity.getSystemService(StatusBarManager::class.java) ?: return false

        val component = ComponentName(activity, "at.saltyy.switchly.platform.tile.SwitchlyTileService")
        val label = activity.getString(R.string.app_name)
        val icon = Icon.createWithResource(activity, R.drawable.app_blocking_surface_24)
        val executor = Executors.newSingleThreadExecutor()

        sb.requestAddTileService(component, label, icon, executor) { result ->
            Handler(Looper.getMainLooper()).post {
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                        Toast.makeText(activity, activity.getString(R.string.qs_added_ok), Toast.LENGTH_SHORT).show()

                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                        Toast.makeText(activity, activity.getString(R.string.qs_added_already), Toast.LENGTH_SHORT).show()

                    else ->
                        // Canceled or any other status
                        Toast.makeText(activity, activity.getString(R.string.qs_added_cancel), Toast.LENGTH_SHORT).show()
                }
            }
        }

        return true
    }
}
