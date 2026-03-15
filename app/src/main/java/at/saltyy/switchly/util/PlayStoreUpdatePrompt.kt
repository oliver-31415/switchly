package at.saltyy.switchly.util

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AppPreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import at.saltyy.switchly.ui.dialog.showAccented
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Lightweight "Update available" prompt.
 * Uses Play Core to detect if an update is available on Google Play.
 * When one is available, we show a dialog and open the Play Store listing.
 */
object PlayStoreUpdatePrompt {

    fun check(activity: Activity) {
        // Only works when installed from Google Play. For sideload/dev installs this can fail.
        runCatching {
            val mgr = AppUpdateManagerFactory.create(activity)
            val task = mgr.appUpdateInfo

            task.addOnSuccessListener { info ->
                val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                if (!available) return@addOnSuccessListener

                // If Play doesn't allow flexible update checks here, we still can open Play Store.
                val allowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ||
                    info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                if (!allowed) return@addOnSuccessListener

                val currentVersionCode = activity.packageManager
                    .getPackageInfo(activity.packageName, 0)
                    .let { pi ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            pi.longVersionCode
                        } else {
                            pi.versionCode.toLong()
                        }
                    }

                // Store the "prompted" version in DataStore (single source of truth).
                val owner = activity as? LifecycleOwner
                if (owner == null) {
                    // Very defensive fallback (shouldn't happen for AppCompatActivity).
                    return@addOnSuccessListener
                }

                owner.lifecycleScope.launch {
                    val prefs = AppPreferences(activity.applicationContext)
                    val lastPrompted = prefs.lastUpdatePromptedVersionCode.first()

                    // Avoid spamming the user: show once per installed version.
                    if (lastPrompted == currentVersionCode) return@launch

                    MaterialAlertDialogBuilder(activity)
                        .setTitle(activity.getString(R.string.update_available_title))
                        .setMessage(activity.getString(R.string.update_available_message))
                        .setPositiveButton(activity.getString(R.string.update_available_cta)) { _, _ ->
                            openPlayStore(activity)
                        }
                        .setNegativeButton(activity.getString(R.string.not_now), null)
                        .showAccented()

                    prefs.setLastUpdatePromptedVersionCode(currentVersionCode)
                }
            }

            task.addOnFailureListener {
                // ignore
            }
        }
    }

    /**
     * Checks if an update is available on Google Play.
     * This is useful for UI hints (e.g. showing "(update available)" in About).
     */
    fun checkAvailability(activity: Activity, onResult: (available: Boolean) -> Unit) {
        runCatching {
            val mgr = AppUpdateManagerFactory.create(activity)
            mgr.appUpdateInfo
                .addOnSuccessListener { info ->
                    onResult(info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE)
                }
                .addOnFailureListener {
                    onResult(false)
                }
        }.onFailure {
            onResult(false)
        }
    }

    /**
     * Manual/user initiated update prompt.
     * - If an update is available: shows the dialog.
     * - If not: shows a small "up to date" toast.
     */
    fun promptNow(activity: Activity) {
        checkAvailability(activity) { available ->
            if (available) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(activity.getString(R.string.update_available_title))
                    .setMessage(activity.getString(R.string.update_available_message))
                    .setPositiveButton(activity.getString(R.string.update_available_cta)) { _, _ ->
                        openPlayStore(activity)
                    }
                    .setNegativeButton(activity.getString(R.string.cancel), null)
                    .showAccented()
            } else {
                android.widget.Toast.makeText(
                    activity,
                    activity.getString(R.string.switchly_update_up_to_date),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun openPlayStore(activity: Activity) {
        val pkg = activity.packageName
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=$pkg".toUri())
        val web = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$pkg".toUri())

        if (market.resolveActivity(activity.packageManager) != null) {
            activity.startActivity(market)
        } else {
            activity.startActivity(web)
        }
    }
}
