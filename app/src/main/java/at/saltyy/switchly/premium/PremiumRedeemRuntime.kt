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

package at.saltyy.switchly.premium

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.auth.Auth
import at.saltyy.switchly.data.prefs.AppLogStore
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Redeems custom Switchly Premium codes for non-Play builds.
 *
 * Play Store builds intentionally do not expose this flow. They keep Google Play Billing.
 * Firebase/direct builds redeem SWLY codes through the website API.
 * Offline builds validate SALT-OFFLINE codes locally against an allowlist compiled only into that flavor.
 */
object PremiumRedeemRuntime {

    private const val TAG = "PremiumRedeem"
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val INSTALL_ID_PREFS = "premium_redeem_runtime"
    private const val INSTALL_ID_KEY = "install_id"

    private val VALID_SWITCHLY_COMPACT_CODE = Regex("^SWLY[A-Z0-9]{12}$")
    private val VALID_SWITCHLY_DISPLAY_CODE = Regex("^SWLY-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")
    private val VALID_OFFLINE_CODE = Regex("^SALT-OFFLINE-[A-Z0-9]{4}-[A-Z0-9]{4}$")
    private val VALID_OFFLINE_COMPACT_CODE = Regex("^SALTOFFLINE[A-Z0-9]{8}$")

    data class RedeemResult(
        val success: Boolean,
        val normalizedCode: String,
        val message: String? = null,
        val reason: Reason = Reason.UNKNOWN,
    )

    enum class Reason {
        SUCCESS,
        UNSUPPORTED_BUILD,
        SIGN_IN_REQUIRED,
        INVALID_FORMAT,
        INVALID,
        USED,
        REVOKED,
        EXPIRED,
        WRONG_BUILD,
        NETWORK,
        UNKNOWN,
    }

    fun isRedeemSupportedBuild(): Boolean =
        BuildConfig.SWITCHLY_REDEEM_CODES_ENABLED && !BuildConfig.SWITCHLY_PLAY_BILLING_ENABLED &&
            (BuildConfig.SWITCHLY_ONLINE_REDEEM_CODES_ENABLED || BuildConfig.SWITCHLY_OFFLINE_REDEEM_CODES_ENABLED)

    fun isOnlineRedeemBuild(): Boolean =
        isRedeemSupportedBuild() && BuildConfig.SWITCHLY_ONLINE_REDEEM_CODES_ENABLED

    fun isOfflineRedeemBuild(): Boolean =
        isRedeemSupportedBuild() && BuildConfig.SWITCHLY_OFFLINE_REDEEM_CODES_ENABLED

    fun helperMode(): Mode = when {
        isOnlineRedeemBuild() -> Mode.ONLINE_SWITCHLY_CODE
        isOfflineRedeemBuild() -> Mode.OFFLINE_CODE
        else -> Mode.UNSUPPORTED
    }

    enum class Mode {
        ONLINE_SWITCHLY_CODE,
        OFFLINE_CODE,
        UNSUPPORTED,
    }

    fun normalizeCode(raw: String): String {
        val cleaned = raw
            .trim()
            .uppercase(Locale.US)
            .replace(Regex("\\s+"), "")

        val switchlyCompact = cleaned.replace("-", "")
        if (VALID_SWITCHLY_COMPACT_CODE.matches(switchlyCompact)) {
            return buildString {
                append(switchlyCompact.substring(0, 4))
                append('-')
                append(switchlyCompact.substring(4, 8))
                append('-')
                append(switchlyCompact.substring(8, 12))
                append('-')
                append(switchlyCompact.substring(12, 16))
            }
        }

        val offline = cleaned.replace(Regex("-+"), "-")
        if (VALID_OFFLINE_CODE.matches(offline)) {
            return offline
        }

        val offlineCompact = cleaned.replace("-", "")
        if (VALID_OFFLINE_COMPACT_CODE.matches(offlineCompact)) {
            val payload = offlineCompact.removePrefix("SALTOFFLINE")
            return "SALT-OFFLINE-${payload.take(4)}-${payload.drop(4)}"
        }

        return cleaned
    }

    fun hasValidFormat(normalizedCode: String): Boolean = when {
        isOnlineRedeemBuild() -> isSwitchlyCode(normalizedCode) || isOfflineCode(normalizedCode)
        isOfflineRedeemBuild() -> isOfflineCode(normalizedCode) || isSwitchlyCode(normalizedCode)
        else -> isSwitchlyCode(normalizedCode) || isOfflineCode(normalizedCode)
    }

    fun isWrongBuildCode(normalizedCode: String): Boolean = when {
        isOnlineRedeemBuild() -> isOfflineCode(normalizedCode)
        isOfflineRedeemBuild() -> isSwitchlyCode(normalizedCode)
        else -> false
    }

    fun expectedFormatDescription(): String = when (helperMode()) {
        Mode.ONLINE_SWITCHLY_CODE -> "SWLY-XXXX-XXXX-XXXX"
        Mode.OFFLINE_CODE -> "SALT-OFFLINE-XXXX-XXXX"
        Mode.UNSUPPORTED -> "SWLY-XXXX-XXXX-XXXX"
    }

    fun redeem(
        context: Context,
        rawCode: String,
        onResult: (RedeemResult) -> Unit,
    ) {
        val appContext = context.applicationContext
        val normalizedCode = normalizeCode(rawCode)

        if (!isRedeemSupportedBuild()) {
            onResult(
                RedeemResult(
                    success = false,
                    normalizedCode = normalizedCode,
                    reason = Reason.UNSUPPORTED_BUILD,
                )
            )
            return
        }

        if (!hasValidFormat(normalizedCode)) {
            onResult(
                RedeemResult(
                    success = false,
                    normalizedCode = normalizedCode,
                    reason = Reason.INVALID_FORMAT,
                )
            )
            return
        }

        if (isWrongBuildCode(normalizedCode)) {
            onResult(
                RedeemResult(
                    success = false,
                    normalizedCode = normalizedCode,
                    reason = Reason.WRONG_BUILD,
                )
            )
            return
        }

        when {
            isOnlineRedeemBuild() -> redeemSwitchlyCodeOnline(appContext, normalizedCode, onResult)
            isOfflineRedeemBuild() -> redeemOfflineCode(appContext, normalizedCode, onResult)
            else -> onResult(
                RedeemResult(
                    success = false,
                    normalizedCode = normalizedCode,
                    reason = Reason.UNSUPPORTED_BUILD,
                )
            )
        }
    }

    private fun redeemSwitchlyCodeOnline(
        context: Context,
        normalizedCode: String,
        onResult: (RedeemResult) -> Unit,
    ) {
        val uid = Auth.uid()
        if (uid == null) {
            onResult(
                RedeemResult(
                    success = false,
                    normalizedCode = normalizedCode,
                    reason = Reason.SIGN_IN_REQUIRED,
                )
            )
            return
        }

        val endpoint = BuildConfig.SWITCHLY_REDEEM_API_URL.trim()
        if (endpoint.isBlank()) {
            onResult(
                RedeemResult(
                    success = false,
                    normalizedCode = normalizedCode,
                    reason = Reason.NETWORK,
                )
            )
            return
        }

        if (BuildConfig.DEBUG) {
            AppLogStore.append(context, TAG, "Redeem online started variant=${BuildConfig.SWITCHLY_APK_VARIANT}")
        }

        thread(name = "SwitchlyPremiumRedeem", isDaemon = true) {
            val result = runCatching {
                postRedeemRequest(context, endpoint, normalizedCode, uid)
            }.getOrElse { error ->
                if (BuildConfig.DEBUG) AppLogStore.append(context, TAG, "Redeem online failed", error)
                RedeemResult(
                    success = false,
                    normalizedCode = normalizedCode,
                    message = error.message,
                    reason = Reason.NETWORK,
                )
            }

            if (result.success) {
                PremiumManager.setPremiumFromSwitchlyRedeemCode(context, normalizedCode)
                if (BuildConfig.DEBUG) {
                    AppLogStore.append(context, TAG, "Redeem online success variant=${BuildConfig.SWITCHLY_APK_VARIANT}")
                }
            }

            onResult(result)
        }
    }

    private fun postRedeemRequest(
        context: Context,
        endpoint: String,
        normalizedCode: String,
        uid: String,
    ): RedeemResult {
        val params = linkedMapOf(
            "code" to normalizedCode,
            "firebase_uid" to uid,
            "email" to Auth.email().orEmpty(),
            "build_variant" to redeemBuildVariant(),
            "app_id" to BuildConfig.APPLICATION_ID,
            "app_version" to BuildConfig.VERSION_NAME,
            "version_code" to BuildConfig.VERSION_CODE.toString(),
            "device_id" to installIdentifier(context),
            "device_manufacturer" to Build.MANUFACTURER.orEmpty(),
            "device_model" to Build.MODEL.orEmpty(),
            "android_sdk" to Build.VERSION.SDK_INT.toString(),
        )

        val postBody = params.entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            connection.outputStream.use { stream ->
                stream.write(postBody.toByteArray(StandardCharsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val body = readStream(
                if (responseCode in 200..299) connection.inputStream else connection.errorStream
            )
            parseRedeemResponse(normalizedCode, responseCode, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRedeemResponse(
        normalizedCode: String,
        responseCode: Int,
        body: String,
    ): RedeemResult {
        val json = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val success = json.optBoolean("success", false) || json.optBoolean("premium_enabled", false)
        val status = json.optString("status", "").lowercase(Locale.US)
        val message = json.optString("message", "").ifBlank { null }
        val codeType = json.optString("code_type", "").lowercase(Locale.US)

        if (success) {
            return RedeemResult(
                success = true,
                normalizedCode = normalizedCode,
                message = message,
                reason = Reason.SUCCESS,
            )
        }

        val reason = when {
            codeType == "offline_license_code" || codeType == "google_play_promo_code" -> Reason.WRONG_BUILD
            responseCode == 404 -> Reason.INVALID
            responseCode == 409 -> Reason.USED
            responseCode == 403 -> Reason.WRONG_BUILD
            responseCode !in 200..299 -> reasonFromStatus(status).takeUnless { it == Reason.UNKNOWN } ?: Reason.NETWORK
            else -> reasonFromStatus(status).takeUnless { it == Reason.UNKNOWN } ?: Reason.INVALID
        }

        return RedeemResult(
            success = false,
            normalizedCode = normalizedCode,
            message = message,
            reason = reason,
        )
    }

    private fun redeemOfflineCode(
        context: Context,
        normalizedCode: String,
        onResult: (RedeemResult) -> Unit,
    ) {
        if (offlineAllowlist().contains(normalizedCode)) {
            PremiumManager.setPremiumFromOfflineCode(context, normalizedCode)
            if (BuildConfig.DEBUG) AppLogStore.append(context, TAG, "Redeem offline code success")
            onResult(
                RedeemResult(
                    success = true,
                    normalizedCode = normalizedCode,
                    reason = Reason.SUCCESS,
                )
            )
        } else {
            if (BuildConfig.DEBUG) AppLogStore.append(context, TAG, "Redeem offline code invalid")
            onResult(
                RedeemResult(
                    success = false,
                    normalizedCode = normalizedCode,
                    reason = Reason.INVALID,
                )
            )
        }
    }

    private fun reasonFromStatus(status: String): Reason = when (status.lowercase(Locale.US)) {
        "invalid", "not_found", "not-found", "missing", "unknown" -> Reason.INVALID
        "used", "already_used", "already-used", "redeemed_before", "redeemed-before" -> Reason.USED
        "revoked" -> Reason.REVOKED
        "expired" -> Reason.EXPIRED
        "wrong_build", "wrong-build", "wrong_channel", "wrong-channel", "wrong_type", "wrong-type", "google_play", "offline" -> Reason.WRONG_BUILD
        "network", "unavailable" -> Reason.NETWORK
        else -> Reason.UNKNOWN
    }

    private fun isSwitchlyCode(code: String): Boolean =
        VALID_SWITCHLY_DISPLAY_CODE.matches(code) || VALID_SWITCHLY_COMPACT_CODE.matches(code.replace("-", ""))

    private fun isOfflineCode(code: String): Boolean = VALID_OFFLINE_CODE.matches(code)

    private fun offlineAllowlist(): Set<String> = BuildConfig.SWITCHLY_OFFLINE_REDEEM_CODE_ALLOWLIST
        .split(',')
        .map { it.trim().uppercase(Locale.US) }
        .filter { it.isNotBlank() }
        .toSet()

    private fun redeemBuildVariant(): String = when (BuildConfig.SWITCHLY_APK_VARIANT) {
        "firebase-email" -> "firebase"
        "offline" -> "offline"
        else -> BuildConfig.SWITCHLY_APK_VARIANT
    }

    private fun installIdentifier(context: Context): String = synchronized(this) {
        val prefs = context.applicationContext.getSharedPreferences(INSTALL_ID_PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(INSTALL_ID_KEY, null).orEmpty()
        if (existing.isNotBlank()) return@synchronized existing

        val generated = UUID.randomUUID().toString()
        prefs.edit { putString(INSTALL_ID_KEY, generated) }
        generated
    }

    private fun readStream(stream: InputStream?): String {
        if (stream == null) {
            return ""
        }
        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())
}
