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

package at.saltyy.switchly.security

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.data.prefs.AppLogStore
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Soft Play Integrity probe used for diagnostics only.
 * This intentionally does not block Premium, login, backups, or blocking features.
 * If the Play Integrity SDK dependency is not packaged in a build, the reflection path simply records sdk_missing and exits without crashing.
 * Server-side token verification can be added later.
 * For now the app only logs whether requesting a token succeeded, failed, or is unavailable in the current APK variant.
 */
object PlayIntegrityRuntime {

    private const val TAG = "PlayIntegrity"
    private const val PREFS = "switchly_play_integrity"

    private const val KEY_LAST_REQUEST_MS = "last_request_ms"
    private const val KEY_LAST_SUCCESS_MS = "last_success_ms"
    private const val KEY_LAST_STATUS = "last_status"
    private const val KEY_LAST_REASON = "last_reason"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_LAST_TOKEN_LENGTH = "last_token_length"
    private const val KEY_SDK_AVAILABLE = "sdk_available"

    private const val STATUS_SKIPPED = "skipped"
    private const val STATUS_PENDING = "pending"
    private const val STATUS_SUCCESS = "success"
    private const val STATUS_SDK_MISSING = "sdk_missing"
    private const val STATUS_FAILED = "failed"

    private const val APP_START_THROTTLE_MS = 12L * 60L * 60L * 1000L

    @Volatile
    private var requestRunning = false

    data class Snapshot(
        val enabled: Boolean,
        val sdkAvailable: Boolean,
        val lastStatus: String,
        val lastReason: String,
        val lastRequestMs: Long,
        val lastSuccessMs: Long,
        val lastTokenLength: Int,
        val lastError: String,
    )

    fun isSoftCheckEnabled(): Boolean {
        // Only run this in Play/Billing builds for now.
        // Offline/Firebase-only APKs should not require Play services for diagnostics.
        return BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED
    }

    fun requestSoftCheck(
        context: Context,
        reason: String,
        force: Boolean = false,
    ) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (!isSoftCheckEnabled()) {
            prefs.edit {
                putString(KEY_LAST_STATUS, STATUS_SKIPPED)
                putString(KEY_LAST_REASON, reason.cleanReason())
                putString(KEY_LAST_ERROR, "Play Integrity soft checks disabled for this APK variant")
                putBoolean(KEY_SDK_AVAILABLE, false)
            }
            return
        }

        val now = System.currentTimeMillis()
        val lastRequest = prefs.getLong(KEY_LAST_REQUEST_MS, 0L)
        if (!force && now - lastRequest < APP_START_THROTTLE_MS) return
        if (requestRunning) return

        requestRunning = true
        val cleanReason = reason.cleanReason()

        prefs.edit {
            putLong(KEY_LAST_REQUEST_MS, now)
            putString(KEY_LAST_STATUS, STATUS_PENDING)
            putString(KEY_LAST_REASON, cleanReason)
            putString(KEY_LAST_ERROR, "")
        }
        AppLogStore.append(appContext, TAG, "Soft check requested reason=$cleanReason")

        thread(name = "SwitchlyPlayIntegrity", isDaemon = true) {
            runCatching {
                requestIntegrityTokenReflective(appContext, cleanReason)
            }.onFailure { error ->
                recordFailure(appContext, cleanReason, error)
            }
        }
    }

    fun snapshot(context: Context): Snapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            enabled = isSoftCheckEnabled(),
            sdkAvailable = prefs.getBoolean(KEY_SDK_AVAILABLE, false),
            lastStatus = prefs.getString(KEY_LAST_STATUS, "-").orEmpty().ifBlank { "-" },
            lastReason = prefs.getString(KEY_LAST_REASON, "-").orEmpty().ifBlank { "-" },
            lastRequestMs = prefs.getLong(KEY_LAST_REQUEST_MS, 0L),
            lastSuccessMs = prefs.getLong(KEY_LAST_SUCCESS_MS, 0L),
            lastTokenLength = prefs.getInt(KEY_LAST_TOKEN_LENGTH, 0),
            lastError = prefs.getString(KEY_LAST_ERROR, "").orEmpty(),
        )
    }

    private fun requestIntegrityTokenReflective(context: Context, reason: String) {
        try {
            val requestClass = Class.forName("com.google.android.play.core.integrity.IntegrityTokenRequest")
            val managerFactoryClass = Class.forName("com.google.android.play.core.integrity.IntegrityManagerFactory")
            val successListenerClass = Class.forName("com.google.android.gms.tasks.OnSuccessListener")
            val failureListenerClass = Class.forName("com.google.android.gms.tasks.OnFailureListener")

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putBoolean(KEY_SDK_AVAILABLE, true)
            }

            val nonce = createNonce(reason)
            val builder = requestClass.getMethod("builder").invoke(null)
            builder.javaClass.getMethod("setNonce", String::class.java).invoke(builder, nonce)
            val request = builder.javaClass.getMethod("build").invoke(builder)

            val manager = managerFactoryClass.getMethod("create", Context::class.java).invoke(null, context)
            val task = manager.javaClass.getMethod("requestIntegrityToken", requestClass).invoke(manager, request)

            val successListener = Proxy.newProxyInstance(
                successListenerClass.classLoader,
                arrayOf(successListenerClass),
            ) { _, method, args ->
                if (method.name == "onSuccess") {
                    val token = runCatching {
                        val response = args?.firstOrNull()
                        response?.javaClass?.getMethod("token")?.invoke(response) as? String
                    }.getOrNull().orEmpty()
                    recordSuccess(context, reason, token.length)
                }
                null
            }

            val failureListener = Proxy.newProxyInstance(
                failureListenerClass.classLoader,
                arrayOf(failureListenerClass),
            ) { _, method, args ->
                if (method.name == "onFailure") {
                    val error = args?.firstOrNull() as? Throwable
                        ?: IllegalStateException("Play Integrity request failed")
                    recordFailure(context, reason, error)
                }
                null
            }

            task.javaClass.getMethod("addOnSuccessListener", successListenerClass).invoke(task, successListener)
            task.javaClass.getMethod("addOnFailureListener", failureListenerClass).invoke(task, failureListener)
        } catch (missing: ClassNotFoundException) {
            recordSdkMissing(context, reason, missing)
        } catch (error: Throwable) {
            recordFailure(context, reason, error)
        }
    }

    private fun recordSuccess(context: Context, reason: String, tokenLength: Int) {
        requestRunning = false
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong(KEY_LAST_SUCCESS_MS, now)
            putString(KEY_LAST_STATUS, STATUS_SUCCESS)
            putString(KEY_LAST_REASON, reason)
            putString(KEY_LAST_ERROR, "")
            putInt(KEY_LAST_TOKEN_LENGTH, tokenLength)
            putBoolean(KEY_SDK_AVAILABLE, true)
        }
        AppLogStore.append(context, TAG, "Soft check success reason=$reason tokenLength=$tokenLength")
    }

    private fun recordSdkMissing(context: Context, reason: String, error: Throwable) {
        requestRunning = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_LAST_STATUS, STATUS_SDK_MISSING)
            putString(KEY_LAST_REASON, reason)
            putString(KEY_LAST_ERROR, error.javaClass.simpleName)
            putInt(KEY_LAST_TOKEN_LENGTH, 0)
            putBoolean(KEY_SDK_AVAILABLE, false)
        }
        AppLogStore.append(context, TAG, "Soft check skipped reason=$reason sdk=missing")
    }

    private fun recordFailure(context: Context, reason: String, error: Throwable) {
        requestRunning = false
        val message = error.message?.take(180).orEmpty().ifBlank { error.javaClass.simpleName }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_LAST_STATUS, STATUS_FAILED)
            putString(KEY_LAST_REASON, reason)
            putString(KEY_LAST_ERROR, message)
            putInt(KEY_LAST_TOKEN_LENGTH, 0)
            putBoolean(KEY_SDK_AVAILABLE, true)
        }
        Log.w(TAG, "Soft check failed reason=$reason error=$message", error)
        AppLogStore.append(context, TAG, "Soft check failed reason=$reason error=$message")
    }

    private fun createNonce(reason: String): String {
        val randomBytes = ByteArray(24)
        SecureRandom().nextBytes(randomBytes)
        val randomPart = Base64.encodeToString(
            randomBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val metadata = listOf(
            BuildConfig.APPLICATION_ID,
            BuildConfig.VERSION_CODE.toString(),
            reason.cleanReason(),
            System.currentTimeMillis().toString(),
            Build.VERSION.SDK_INT.toString(),
        ).joinToString(":")
        val metadataPart = Base64.encodeToString(
            metadata.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "$randomPart.$metadataPart"
    }

    private fun String.cleanReason(): String {
        return lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_\\-]+"), "_")
            .trim('_')
            .ifBlank { "unknown" }
            .take(48)
    }
}
