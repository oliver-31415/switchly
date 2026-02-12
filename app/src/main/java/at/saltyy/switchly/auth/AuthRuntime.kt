package at.saltyy.switchly.auth

import android.app.Activity
import android.content.Context
import android.os.CancellationSignal
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import org.json.JSONObject
import at.saltyy.switchly.R

/**
 * Authentication runtime:
 * - Credential Manager + Google ID (googleid) for sign-in
 * - FirebaseAuth for backend auth
 *
 * IMPORTANT:
 * - This must NOT crash if Firebase isn't configured yet (e.g. missing google-services.json in /app).
 */
object AuthRuntime {

    private const val TAG = "AuthRuntime"

    private data class GoogleServicesInfo(
        val mobilesdkAppId: String,
        val apiKey: String,
        val projectId: String,
        val projectNumber: String?,
        val storageBucket: String?,
        val webClientId: String?
    )

    private fun String?.cleanOrNull(): String? {
        val v = this?.trim()
        return if (v.isNullOrEmpty() || v.equals("null", ignoreCase = true)) null else v
    }

    private fun loadGoogleServicesJsonTextOrNull(context: Context): String? {
        // app/src/main/assets/google-services.json
        val fromAssets = runCatching {
            context.assets.open("google-services.json").bufferedReader().use { it.readText() }
        }.getOrNull()

        return fromAssets.cleanOrNull()
    }

    private fun readGoogleServicesInfoOrNull(context: Context): GoogleServicesInfo? {
        return runCatching {
            val jsonText = loadGoogleServicesJsonTextOrNull(context) ?: return@runCatching null
            val root = JSONObject(jsonText)

            val projectInfo = root.optJSONObject("project_info") ?: return@runCatching null
            val projectNumber = projectInfo.optString("project_number", null).cleanOrNull()
            val projectId = projectInfo.optString("project_id", null).cleanOrNull() ?: return@runCatching null
            val storageBucket = projectInfo.optString("storage_bucket", null).cleanOrNull()

            val clients = root.optJSONArray("client") ?: return@runCatching null
            if (clients.length() == 0) return@runCatching null

            // Prefer client matching runtime package name.
            val pkgName = context.packageName
            var selectedClient: JSONObject? = null

            for (i in 0 until clients.length()) {
                val c = clients.optJSONObject(i) ?: continue
                val candidatePkg = c.optJSONObject("client_info")
                    ?.optJSONObject("android_client_info")
                    ?.optString("package_name", null)
                    .cleanOrNull()

                if (candidatePkg == pkgName) {
                    selectedClient = c
                    break
                }
            }

            if (selectedClient == null) {
                selectedClient = clients.optJSONObject(0)
            }

            val client = selectedClient ?: return@runCatching null
            val clientInfo = client.optJSONObject("client_info") ?: return@runCatching null

            val mobilesdkAppId = clientInfo
                .optString("mobilesdk_app_id", null)
                .cleanOrNull() ?: return@runCatching null

            // First non-empty API key
            var apiKey: String? = null
            val apiKeyArr = client.optJSONArray("api_key")
            if (apiKeyArr != null) {
                for (i in 0 until apiKeyArr.length()) {
                    val k = apiKeyArr.optJSONObject(i)
                        ?.optString("current_key", null)
                        .cleanOrNull()
                    if (k != null) {
                        apiKey = k
                        break
                    }
                }
            }
            apiKey = apiKey.cleanOrNull() ?: return@runCatching null

            // Web OAuth client id (client_type == 3)
            fun findWebClientId(inClient: JSONObject?): String? {
                val oauthClients = inClient?.optJSONArray("oauth_client") ?: return null
                for (i in 0 until oauthClients.length()) {
                    val obj = oauthClients.optJSONObject(i) ?: continue
                    if (obj.optInt("client_type") == 3) {
                        val id = obj.optString("client_id", null).cleanOrNull()
                        if (id != null) return id
                    }
                }
                return null
            }

            var webClientId = findWebClientId(client)
            if (webClientId == null) {
                // Fallback: scan all clients
                for (i in 0 until clients.length()) {
                    webClientId = findWebClientId(clients.optJSONObject(i))
                    if (webClientId != null) break
                }
            }

            GoogleServicesInfo(
                mobilesdkAppId = mobilesdkAppId,
                apiKey = apiKey,
                projectId = projectId,
                projectNumber = projectNumber,
                storageBucket = storageBucket,
                webClientId = webClientId
            )
        }.getOrNull()
    }

    private fun ensureFirebaseAppOrNull(context: Context): FirebaseApp? {
        // 1) normal init from generated resources (google-services plugin)
        FirebaseApp.getApps(context).firstOrNull()?.let { return it }

        val appContext = context.applicationContext
        val fromResources = runCatching { FirebaseApp.initializeApp(appContext) }.getOrNull()
        if (fromResources != null) return fromResources

        // 2) fallback from assets/google-services.json
        val info = readGoogleServicesInfoOrNull(appContext) ?: return null

        val options = FirebaseOptions.Builder()
            .setApplicationId(info.mobilesdkAppId)
            .setApiKey(info.apiKey)
            .setProjectId(info.projectId)
            .apply {
                info.projectNumber?.let { setGcmSenderId(it) }
                info.storageBucket?.let { setStorageBucket(it) }
            }
            .build()

        return runCatching { FirebaseApp.initializeApp(appContext, options) }.getOrNull()
    }

    private fun firebaseAuthOrNull(context: Context): FirebaseAuth? {
        return try {
            FirebaseAuth.getInstance()
        } catch (e: IllegalStateException) {
            val app = ensureFirebaseAppOrNull(context)
            if (app == null) {
                Log.w(TAG, "FirebaseApp not initialized (missing generated resources and/or assets/google-services.json).")
                null
            } else {
                runCatching { FirebaseAuth.getInstance(app) }.getOrNull()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "firebaseAuthOrNull unexpected error", t)
            null
        }
    }

    private fun serverClientIdOrNull(activity: Activity): String? {
        // Generated string from google-services plugin.
        val resId = activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName)
        if (resId != 0) {
            val value = activity.getString(resId).cleanOrNull()
            if (value != null && !value.startsWith("YOUR_")) return value
        }

        // Fallback: read from assets/google-services.json
        return readGoogleServicesInfoOrNull(activity)?.webClientId
    }

    fun startSignIn(
        activity: Activity,
        onResult: (Boolean, String?) -> Unit
    ) {
        val auth = firebaseAuthOrNull(activity)
        if (auth == null) {
            val msg = activity.getString(R.string.auth_firebase_not_configured)
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
            onResult(false, msg)
            return
        }

        val serverClientId = serverClientIdOrNull(activity)
        if (serverClientId == null) {
            val msg = activity.getString(R.string.auth_missing_google_server_client_id)
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
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
                            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                            onResult(false, msg)
                            return
                        } catch (t: Throwable) {
                            Log.e(TAG, "Unexpected error handling Google credential", t)
                            val msg = activity.getString(R.string.auth_unexpected_error_sign_in)
                            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                            onResult(false, msg)
                            return
                        }
                    }

                    Log.w(TAG, "Unsupported or cancelled credential: $credential")
                    val msg = activity.getString(R.string.auth_sign_in_cancelled)
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
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
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                onResult(false, msg)
            }
            else -> {
                Log.e(TAG, "getCredential failed", e)
                val msg = activity.getString(R.string.auth_sign_in_failed_generic)
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, context.getString(R.string.settings_signed_out), Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.e(TAG, "Error during signOut", t)
        }
        onDone()
    }

    /**
     * Returns current Firebase UID (or null if not signed in not configured).
     */
    fun uid(): String? = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
}
