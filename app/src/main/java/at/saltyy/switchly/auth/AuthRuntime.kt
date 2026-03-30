package at.saltyy.switchly.auth

import android.app.Activity
import android.content.Context
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import at.saltyy.switchly.R

/**
 * Authentication runtime:
 * - Credential Manager + Google ID (googleid) for sign-in
 * - FirebaseAuth for backend auth
 */
object AuthRuntime {

    private const val TAG = "AuthRuntime"

    private fun ensureFirebaseAppOrNull(context: Context): FirebaseApp? {
        FirebaseApp.getApps(context).firstOrNull()?.let { return it }

        return runCatching {
            FirebaseApp.initializeApp(context.applicationContext)
        }.getOrNull()
    }

    private fun firebaseAuthOrNull(context: Context): FirebaseAuth? {
        val app = ensureFirebaseAppOrNull(context)
        if (app == null) {
            Log.w(TAG, "FirebaseApp not initialized.")
            return null
        }

        return runCatching { FirebaseAuth.getInstance(app) }
            .onFailure { Log.e(TAG, "Failed to get FirebaseAuth instance", it) }
            .getOrNull()
    }

    private fun serverClientIdOrNull(activity: Activity): String? {
        val resId = runCatching {
            Class.forName("${activity.packageName}.R\$string")
                .getField("default_web_client_id")
                .getInt(null)
        }.getOrNull() ?: return null

        val raw = runCatching { activity.getString(resId) }.getOrNull()?.trim()
        return raw?.takeIf { it.isNotEmpty() && !it.startsWith("YOUR_") }
    }

    fun startSignIn(
        activity: Activity,
        onResult: (Boolean, String?) -> Unit
    ) {
        val auth = firebaseAuthOrNull(activity)
        if (auth == null) {
            val msg = activity.getString(R.string.auth_firebase_not_configured)
            onResult(false, msg)
            return
        }

        val serverClientId = serverClientIdOrNull(activity)
        if (serverClientId == null) {
            val msg = activity.getString(R.string.auth_missing_google_server_client_id)
            onResult(false, msg)
            return
        }

        // First pass: returning users (previously authorized accounts only)
        launchGoogleFlow(
            activity = activity,
            auth = auth,
            serverClientId = serverClientId,
            authorizedOnly = true,
            onResult = onResult
        )
    }

    private fun launchGoogleFlow(
        activity: Activity,
        auth: FirebaseAuth,
        serverClientId: String,
        authorizedOnly: Boolean,
        onResult: (Boolean, String?) -> Unit
    ) {
        val credentialManager = CredentialManager.create(activity)

        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(authorizedOnly)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val cancellationSignal = CancellationSignal()
        val executor = ContextCompat.getMainExecutor(activity)

        credentialManager.getCredentialAsync(
            activity,
            request,
            cancellationSignal,
            executor,
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val credential = result.credential

                    if (
                        credential is CustomCredential &&
                        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    ) {
                        try {
                            val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
                            firebaseAuthWithGoogle(activity, auth, googleCred.idToken, onResult)
                            return
                        } catch (e: GoogleIdTokenParsingException) {
                            Log.e(TAG, "Google ID token parsing failed", e)
                            val msg = activity.getString(R.string.auth_failed_to_process_google_token)
                            onResult(false, msg)
                            return
                        } catch (t: Throwable) {
                            Log.e(TAG, "Unexpected error handling Google credential", t)
                            val msg = activity.getString(R.string.auth_unexpected_error_sign_in)
                            onResult(false, msg)
                            return
                        }
                    }

                    Log.w(TAG, "Unsupported or cancelled credential: $credential")
                    val msg = activity.getString(R.string.auth_sign_in_cancelled)
                    onResult(false, msg)
                }

                override fun onError(e: GetCredentialException) {
                    // Retry once with all accounts for first-time users.
                    if (e is NoCredentialException && authorizedOnly) {
                        Log.i(TAG, "No authorized Google account found; retrying with all accounts")
                        launchGoogleFlow(
                            activity = activity,
                            auth = auth,
                            serverClientId = serverClientId,
                            authorizedOnly = false,
                            onResult = onResult
                        )
                        return
                    }

                    handleCredentialError(activity, e, onResult)
                }
            }
        )
    }

    private fun handleCredentialError(
        activity: Activity,
        e: GetCredentialException,
        onResult: (Boolean, String?) -> Unit
    ) {
        when (e) {
            is NoCredentialException -> {
                Log.w(TAG, "No credentials available", e)
                val msg = activity.getString(R.string.auth_no_matching_google_accounts_found)
                onResult(false, msg)
            }
            else -> {
                Log.e(TAG, "getCredential failed", e)
                val msg = activity.getString(R.string.auth_sign_in_failed_generic)
                onResult(false, msg)
            }
        }
    }

    private fun firebaseAuthWithGoogle(
        activity: Activity,
        auth: FirebaseAuth,
        idToken: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(activity) { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Firebase sign-in success")
                    onResult(true, null)
                } else {
                    val error = task.exception?.localizedMessage
                        ?: activity.getString(R.string.auth_sign_in_failed_generic)
                    Log.e(TAG, "Firebase sign-in failed: $error", task.exception)
                    onResult(false, error)
                }
            }
    }

    fun signOut(context: Context, onDone: () -> Unit) {
        try {
            firebaseAuthOrNull(context)?.signOut()
        } catch (t: Throwable) {
            Log.e(TAG, "Error during Firebase signOut", t)
        }

        // Also clear provider state kept by Credential Manager.
        val cm = CredentialManager.create(context)
        cm.clearCredentialStateAsync(
            ClearCredentialStateRequest(),
            CancellationSignal(),
            ContextCompat.getMainExecutor(context),
            object : CredentialManagerCallback<Void?, ClearCredentialException> {
                override fun onResult(result: Void?) {
                    onDone()
                }

                override fun onError(e: ClearCredentialException) {
                    Log.w(TAG, "Could not clear credential state", e)
                    onDone()
                }
            }
        )
    }

    /**
     * Returns current Firebase UID (or null if not signed in not configured).
     */
    fun uid(): String? = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
}
