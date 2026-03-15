package at.saltyy.switchly.premium

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Workaround for a rare Play Billing crash where ProxyBillingActivity can be started without the required PendingIntent extras.
 * Strategy:
 *  - Keep ProxyBillingActivity disabled by default.
 *  - Enable it only right before starting a purchase flow.
 *  - Disable it again after the flow completes (or times out).
 */
object BillingProxyActivityGate {

    private const val TAG = "BillingProxyGate"
    private const val PROXY_ACTIVITY = "com.android.billingclient.api.ProxyBillingActivity"

    fun enable(context: Context) = setEnabled(context, true)

    fun disable(context: Context) = setEnabled(context, false)

    private fun setEnabled(context: Context, enabled: Boolean) {
        val cn = ComponentName(context.packageName, PROXY_ACTIVITY)
        val pm = context.packageManager

        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

        runCatching {
            pm.setComponentEnabledSetting(cn, newState, PackageManager.DONT_KILL_APP)
        }.onFailure { t ->
            // If the component is missing (e.g. stripped builds) or PM rejects the call, don't crash the app.
            Log.w(TAG, "Failed to set ProxyBillingActivity enabled=$enabled", t)
        }
    }
}
