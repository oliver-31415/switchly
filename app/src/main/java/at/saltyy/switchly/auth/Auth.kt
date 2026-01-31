package at.saltyy.switchly.auth

import android.app.Activity
import android.content.Context
import android.widget.Toast
import at.saltyy.switchly.R

/**
 * Simple facade for authentication.
 *
 * All real work is done in [AuthRuntime]. This keeps the rest of the app
 * decoupled from Firebase / Credential Manager details.
 */
object Auth {

    /**
     * Start Google sign-in using Credential Manager.
     *
     * Existing call sites that used:
     *   Auth.startSignIn(activity)
     * will continue to work.
     */
    fun startSignIn(activity: Activity) {
        AuthRuntime.startSignIn(
            activity = activity,
            onResult = { success, error ->
                // Default behavior: just show a toast.
                val msg = if (success) {
                    activity.getString(R.string.auth_sign_in_success)
                } else {
                    activity.getString(
                        R.string.auth_sign_in_failed_fmt,
                        error ?: activity.getString(R.string.error_unknown)
                    )
                }
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        )
    }

    /**
     * Legacy API used from SettingsActivity via onActivityResult.
     *
     * Use this overload to return localized error strings.
     */
    fun handleActivityResult(
        context: Context,
        callback: (Boolean, String?) -> Unit
    ) {
        val loggedIn = uid() != null
        callback(
            loggedIn,
            if (loggedIn) null else context.getString(R.string.settings_google_logged_out)
        )
    }

    /**
     * Sign the user out of Firebase.
     */
    fun signOut(context: Context, onDone: () -> Unit) {
        AuthRuntime.signOut(context, onDone)
    }

    /**
     * Returns the current Firebase UID (or null if not signed in).
     */
    fun uid(): String? = AuthRuntime.uid()
}
