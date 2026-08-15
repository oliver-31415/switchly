/*
 * Switchly
 * Copyright (C) 2025-2026 Saltyy
 * Copyright (C) 2026 Switchly Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package at.saltyy.switchly.auth

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.util.Base64
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
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.util.AppSigningInfo
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import java.security.SecureRandom

/**
 * Authentication runtime:
 * - Credential Manager + Google ID (googleid) for sign-in
 * - FirebaseAuth for backend auth
 */
object AuthRuntime {

    private const val TAG = "AuthRuntime"

    enum class AuthAction {
        GOOGLE_SIGN_IN,
        EMAIL_SIGN_IN,
        CREATE_ACCOUNT,
        RESET_PASSWORD
    }

    private fun ensureFirebaseAppOrNull(context: Context): FirebaseApp? {
        FirebaseApp.getApps(context).firstOrNull()?.let { return it }

        return runCatching {
            FirebaseApp.initializeApp(context.applicationContext)
        }.getOrNull()
    }

    private fun firebaseAuthOrNull(context: Context): FirebaseAuth? {
        if (!BuildConfig.SWITCHLY_FIREBASE_ENABLED) {
            return null
        }

        val app = ensureFirebaseAppOrNull(context)
        if (app == null) {
            Log.w(TAG, "FirebaseApp not initialized.")
            AppLogStore.append(context, TAG, "FirebaseApp not initialized")
            return null
        }

        return runCatching { FirebaseAuth.getInstance(app) }
            .onFailure {
                Log.e(TAG, "Failed to get FirebaseAuth instance", it)
                AppLogStore.append(context, TAG, "Failed to get FirebaseAuth instance", it)
            }
            .getOrNull()
    }

    private fun serverClientIdOrNull(context: Context): String? {
        val raw = BuildConfig.SWITCHLY_GOOGLE_WEB_CLIENT_ID.trim()
        if (raw.isBlank() || raw.startsWith("YOUR_")) {
            Log.w(TAG, "Google Web client ID unavailable")
            AppLogStore.append(context, TAG, "Google Web client ID unavailable")
            return null
        }

        return raw
    }

    fun isGoogleSignInAvailable(context: Context): Boolean {
        return BuildConfig.SWITCHLY_GOOGLE_SIGN_IN_ENABLED &&
            firebaseAuthOrNull(context) != null &&
            serverClientIdOrNull(context) != null
    }

    fun startSignIn(
        activity: Activity,
        onResult: (Boolean, Throwable?) -> Unit
    ) {
        if (!BuildConfig.SWITCHLY_GOOGLE_SIGN_IN_ENABLED) {
            AppLogStore.append(activity, TAG, "Google sign-in unavailable: disabled for this build")
            onResult(false, IllegalStateException(activity.getString(R.string.auth_firebase_not_configured)))
            return
        }

        val auth = firebaseAuthOrNull(activity)
        if (auth == null) {
            AppLogStore.append(activity, TAG, "Google sign-in unavailable: Firebase not configured")
            onResult(false, IllegalStateException(activity.getString(R.string.auth_firebase_not_configured)))
            return
        }

        val serverClientId = serverClientIdOrNull(activity)
        if (serverClientId == null) {
            AppLogStore.append(activity, TAG, "Google sign-in unavailable: missing Google Web client ID")
            onResult(false, IllegalStateException(activity.getString(R.string.auth_missing_google_server_client_id)))
            return
        }

        launchGoogleButtonFlow(
            activity = activity,
            auth = auth,
            serverClientId = serverClientId,
            onResult = onResult
        )
    }

    private val googleIdTokenCredentialClassName = GoogleIdTokenCredential::class.java.name

    private fun launchGoogleButtonFlow(
        activity: Activity,
        auth: FirebaseAuth,
        serverClientId: String,
        onResult: (Boolean, Throwable?) -> Unit
    ) {
        val credentialManager = CredentialManager.create(activity)

        val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(serverClientId)
            .setNonce(generateSecureRandomNonce())
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInWithGoogleOption)
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
                            val googleCred = parseGoogleIdCredential(credential.data)
                            firebaseAuthWithGoogle(activity, auth, googleCred.idToken, onResult)
                            return
                        } catch (e: GoogleIdTokenParsingException) {
                            Log.e(TAG, "Google ID token parsing failed", e)
                            AppLogStore.append(activity, TAG, "Google ID token parsing failed", e)
                            val msg = activity.getString(R.string.auth_failed_to_process_google_token)
                            onResult(false, IllegalStateException(msg, e))
                            return
                        } catch (t: Throwable) {
                            Log.e(TAG, "Unexpected error handling Google credential", t)
                            AppLogStore.append(activity, TAG, "Unexpected error handling Google credential", t)
                            val msg = activity.getString(R.string.auth_unexpected_error_sign_in)
                            onResult(false, IllegalStateException(msg, t))
                            return
                        }
                    }

                    Log.w(TAG, "Unsupported or cancelled credential: $credential")
                    AppLogStore.append(
                        activity,
                        TAG,
                        "Unsupported or cancelled credential: ${credential::class.java.simpleName}; expected $googleIdTokenCredentialClassName"
                    )
                    val msg = activity.getString(R.string.auth_sign_in_cancelled)
                    onResult(false, IllegalStateException(msg))
                }

                override fun onError(e: GetCredentialException) {

                    handleCredentialError(activity, e, onResult)
                }
            }
        )
    }

    private fun parseGoogleIdCredential(data: Bundle): GoogleIdTokenCredential {
        return GoogleIdTokenCredential.createFrom(data)
    }

    private fun generateSecureRandomNonce(byteLength: Int = 32): String {
        val randomBytes = ByteArray(byteLength)
        SecureRandom().nextBytes(randomBytes)
        return Base64.encodeToString(
            randomBytes,
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING
        )
    }

    private fun handleCredentialError(
        activity: Activity,
        e: GetCredentialException,
        onResult: (Boolean, Throwable?) -> Unit
    ) {
        val signingSha1 = AppSigningInfo.sha1(activity) ?: "unknown"
        val diagnostic = buildString {
            append("Credential Manager getCredential failed")
            append(" variant=")
            append(BuildConfig.SWITCHLY_APK_VARIANT)
            append(" buildType=")
            append(BuildConfig.BUILD_TYPE)
            append(" package=")
            append(activity.packageName)
            append(" signingSha1=")
            append(signingSha1)
        }

        when {
            isAccountReauthFailure(e) -> {
                Log.e(TAG, diagnostic, e)
                AppLogStore.append(activity, TAG, "$diagnostic accountReauth=true", e)
                val msg = activity.getString(R.string.auth_google_app_verification_failed)
                onResult(false, IllegalStateException(msg, e))
            }
            e is NoCredentialException -> {
                Log.w(TAG, "No credentials available", e)
                AppLogStore.append(activity, TAG, "No Google credentials available", e)
                val msg = activity.getString(R.string.auth_no_matching_google_accounts_found)
                onResult(false, IllegalStateException(msg))
            }
            else -> {
                Log.e(TAG, diagnostic, e)
                AppLogStore.append(activity, TAG, diagnostic, e)
                onResult(false, e)
            }
        }
    }

    private fun isAccountReauthFailure(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("Account reauth failed", ignoreCase = true) ||
            message.contains("[16]", ignoreCase = true)
    }

    private fun firebaseAuthWithGoogle(
        activity: Activity,
        auth: FirebaseAuth,
        idToken: String,
        onResult: (Boolean, Throwable?) -> Unit
    ) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(activity) { task ->
                if (task.isSuccessful) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Firebase sign-in success")
                    AppLogStore.append(activity, TAG, "Firebase Google sign-in success")
                    onResult(true, null)
                } else {
                    val error = task.exception
                    Log.e(TAG, "Firebase sign-in failed", error)
                    AppLogStore.append(activity, TAG, "Firebase Google sign-in failed", error)
                    onResult(false, error)
                }
            }
    }

    fun signInWithEmail(
        context: Context,
        email: String,
        password: String,
        onResult: (Boolean, Throwable?) -> Unit
    ) {
        val auth = firebaseAuthOrNull(context)
        if (auth == null) {
            onResult(false, IllegalStateException(context.getString(R.string.auth_firebase_not_configured)))
            return
        }

        auth.signInWithEmailAndPassword(email.trim(), password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    onResult(true, null)
                } else {
                    onResult(false, task.exception)
                }
            }
    }

    fun createAccountWithEmail(
        context: Context,
        email: String,
        password: String,
        onResult: (Boolean, Throwable?) -> Unit
    ) {
        val auth = firebaseAuthOrNull(context)
        if (auth == null) {
            onResult(false, IllegalStateException(context.getString(R.string.auth_firebase_not_configured)))
            return
        }

        auth.createUserWithEmailAndPassword(email.trim(), password)
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    onResult(false, task.exception)
                    return@addOnCompleteListener
                }

                auth.currentUser?.sendEmailVerification()
                    ?.addOnCompleteListener {
                        onResult(true, null)
                    }
                    ?: onResult(true, null)
            }
    }

    fun sendPasswordResetEmail(
        context: Context,
        email: String,
        onResult: (Boolean, Throwable?) -> Unit
    ) {
        val auth = firebaseAuthOrNull(context)
        if (auth == null) {
            onResult(false, IllegalStateException(context.getString(R.string.auth_firebase_not_configured)))
            return
        }

        auth.sendPasswordResetEmail(email.trim())
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    onResult(true, null)
                } else {
                    onResult(false, task.exception)
                }
            }
    }

    fun userFacingError(context: Context, error: Throwable?, action: AuthAction): String {
        val fallback = when (action) {
            AuthAction.GOOGLE_SIGN_IN, AuthAction.EMAIL_SIGN_IN -> R.string.auth_error_sign_in_generic
            AuthAction.CREATE_ACCOUNT -> R.string.auth_error_create_account_generic
            AuthAction.RESET_PASSWORD -> R.string.auth_error_reset_password_generic
        }

        if (error == null) {
            return context.getString(fallback)
        }

        val direct = error.message?.trim().orEmpty()
        if (error is IllegalStateException && direct.isNotEmpty()) {
            return direct
        }

        return when (error) {
            is NoCredentialException -> context.getString(R.string.auth_sign_in_cancelled)
            is FirebaseNetworkException -> context.getString(R.string.auth_error_network)
            is FirebaseTooManyRequestsException -> context.getString(R.string.auth_error_too_many_requests)
            is FirebaseAuthUserCollisionException -> context.getString(R.string.auth_error_email_already_in_use)
            is FirebaseAuthInvalidUserException -> when (action) {
                AuthAction.RESET_PASSWORD -> context.getString(R.string.auth_error_no_account_for_email)
                else -> context.getString(R.string.auth_error_invalid_login)
            }
            is FirebaseAuthInvalidCredentialsException -> {
                val code = error.errorCode
                when {
                    code.equals("ERROR_INVALID_EMAIL", ignoreCase = true) -> context.getString(R.string.settings_account_invalid_email)
                    code.equals("ERROR_WRONG_PASSWORD", ignoreCase = true) -> context.getString(R.string.auth_error_invalid_login)
                    code.equals("ERROR_INVALID_LOGIN_CREDENTIALS", ignoreCase = true) -> context.getString(R.string.auth_error_invalid_login)
                    code.equals("ERROR_WEAK_PASSWORD", ignoreCase = true) -> context.getString(R.string.auth_error_weak_password)
                    action == AuthAction.RESET_PASSWORD -> context.getString(R.string.auth_error_no_account_for_email)
                    else -> context.getString(R.string.auth_error_invalid_login)
                }
            }
            is FirebaseAuthException -> when (error.errorCode) {
                "ERROR_USER_DISABLED" -> context.getString(R.string.auth_error_account_disabled)
                "ERROR_INVALID_EMAIL" -> context.getString(R.string.settings_account_invalid_email)
                "ERROR_EMAIL_ALREADY_IN_USE" -> context.getString(R.string.auth_error_email_already_in_use)
                "ERROR_WEAK_PASSWORD" -> context.getString(R.string.auth_error_weak_password)
                "ERROR_TOO_MANY_REQUESTS" -> context.getString(R.string.auth_error_too_many_requests)
                "ERROR_USER_NOT_FOUND" -> context.getString(R.string.auth_error_no_account_for_email)
                "ERROR_WRONG_PASSWORD", "ERROR_INVALID_LOGIN_CREDENTIALS", "ERROR_INVALID_CREDENTIAL" -> context.getString(R.string.auth_error_invalid_login)
                else -> context.getString(fallback)
            }
            else -> context.getString(fallback)
        }
    }

    fun signOut(context: Context, onDone: () -> Unit) {
        try {
            firebaseAuthOrNull(context)?.signOut()
        } catch (t: Throwable) {
            Log.e(TAG, "Error during Firebase signOut", t)
            AppLogStore.append(context, TAG, "Error during Firebase signOut", t)
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
                    AppLogStore.append(context, TAG, "Could not clear credential state", e)
                    onDone()
                }
            }
        )
    }

    // Returns current Firebase UID (or null if not signed in not configured).
    fun uid(): String? {
        if (!BuildConfig.SWITCHLY_FIREBASE_ENABLED) {
            return null
        }
        return runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
    }

    // Returns current Firebase email address (or null if not signed in/not configured).
    fun email(): String? {
        if (!BuildConfig.SWITCHLY_FIREBASE_ENABLED) {
            return null
        }
        return runCatching { FirebaseAuth.getInstance().currentUser?.email }.getOrNull()
    }
}
