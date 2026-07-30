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

package at.saltyy.switchly.nfc

import android.net.Uri

/**
 * Utility for building and parsing Switchly NFC/deep-link URIs.
 * Canonical URI pattern written by Switchly:
 *   switchly://<action>[?duration=<minutes|ask>][&profile=<profileName>]
 * Examples:
 *   switchly://toggle
 *   switchly://temp_disable?duration=15
 *   switchly://temp_enable?duration=ask
 *   switchly://temp_enable?profile=Work&duration=30
 * Additional readable/compact aliases remain supported for user-generated QR/barcode/NFC tags:
 *   switchly://action?type=temp_enable&profile=Work&duration=30
 *   switchly://a?t=te&p=Work&d=30
 * Legacy URI patterns remain supported for already-written NFC/QR/barcode tags:
 *   switchly://switch/<action>
 *   switchly://profile/<profileName>/<action>
 */
object NfcSchema {

    // Scheme
    private const val SCHEME = "switchly"

    // Hosts
    const val HOST_ACTION = "action" // Readable alias: switchly://action?type=...
    private const val HOST_COMPACT_ACTION = "a" // Compact alias: switchly://a?t=...
    const val HOST_SWITCH = "switch" // Legacy global/universal actions
    const val HOST_PROFILE = "profile" // Legacy profile-specific actions

    // Official base actions (EntryActivity may also handle dynamic tempX actions)
    const val ACTION_ENABLE = "enable"

    // Query keys for action URIs
    private const val QUERY_TYPE = "type"
    private const val QUERY_TYPE_SHORT = "t"
    private const val QUERY_ACTION = "action" // accepted alias for future/backwards flexibility
    private const val QUERY_PROFILE = "profile"
    private const val QUERY_PROFILE_SHORT = "p"
    private const val QUERY_DURATION = "duration"
    private const val QUERY_DURATION_SHORT = "d"
    private const val QUERY_MINUTES = "minutes" // accepted alias
    private const val DURATION_ASK = "ask"
    private const val DURATION_ASK_SHORT = "a"

    private val READABLE_ACTION_HOSTS = setOf(
        "toggle",
        "enable",
        "disable",
        "temp_enable",
        "temp_disable",
        "reentry"
    )

    private val COMPACT_ACTION_TYPES = mapOf(
        "tg" to "toggle",
        "en" to "enable",
        "di" to "disable",
        "te" to "temp_enable",
        "td" to "temp_disable",
        "re" to "reentry"
    )

    // Default temporary disable duration (10 minutes)
    const val DEFAULT_TEMP_DISABLE_MS: Long = 10 * 60 * 1000L

    // Helper for actions like "temp_disable10", "temp_enable30", "reentry120" and legacy "temp10". Bare "temp_enable" and "temp_disable" are valid and ask for a duration at scan time.
    private val TEMP_ACTION_REGEX = Regex("""(?:temp(?:_enable|_disable)?|reentry)(\d{1,4})""")

    // Returns true if the given host is one of the supported hosts.
    fun isKnownHost(host: String?): Boolean {
        val normalized = host?.trim()?.lowercase().orEmpty()
        return normalized == HOST_ACTION ||
            normalized == HOST_COMPACT_ACTION ||
            normalized == HOST_SWITCH ||
            normalized == HOST_PROFILE ||
            normalized in READABLE_ACTION_HOSTS
    }

    /**
     * Strict parser for externally supplied Switchly command URIs.
     * Rejects unknown hosts, fragments, malformed profile/action payloads and unsupported actions early.
     */
    fun parseCommandUri(uri: Uri?): NfcCommand? {
        uri ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) {
            return null
        }
        if (!uri.fragment.isNullOrBlank()) {
            return null
        }

        val host = uri.host?.lowercase() ?: return null
        val segs = uri.pathSegments ?: emptyList()

        return when (host) {
            HOST_ACTION -> parseQueryActionUri(uri)

            HOST_COMPACT_ACTION -> parseQueryActionUri(uri)

            in READABLE_ACTION_HOSTS -> parseReadableActionUri(uri, host)

            HOST_SWITCH -> {
                if (!uri.query.isNullOrBlank()) {
                    return null
                }
                if (segs.size != 1) {
                    return null
                }
                val action = segs[0].trim().lowercase()
                if (!isSupportedAction(action)) {
                    return null
                }
                GlobalCommand(action)
            }

            HOST_PROFILE -> {
                if (!uri.query.isNullOrBlank()) {
                    return null
                }
                if (segs.size != 2) {
                    return null
                }
                val profile = segs[0].trim()
                val action = segs[1].trim().lowercase()
                if (profile.isBlank() || !isSupportedAction(action)) {
                    return null
                }
                ProfileCommand(profile, action)
            }

            else -> null
        }
    }

    fun isSupportedCommandUri(uri: Uri?): Boolean = parseCommandUri(uri) != null

    fun isSupportedAction(action: String?): Boolean {
        val a = action?.trim()?.lowercase().orEmpty()
        if (a.isBlank()) {
            return false
        }
        if (a in setOf("enable", "disable", "toggle", "start", "stop", "on", "off", "activate", "emergency_disable")) {
            return true
        }
        return a.matches(Regex("""(temp_enable|temp_disable|reentry)(\d{1,4})?"""))
    }

    // -------------------------------------------------------------------------
    // Public URI builders
    // -------------------------------------------------------------------------

    /**
     * Builds the short readable global/universal URI such as:
     *  - switchly://toggle
     *  - switchly://temp_disable?duration=10
     *  - switchly://temp_enable?duration=ask
     *  - switchly://temp_disable?duration=ask
     */
    fun uriForGlobalAction(action: String): String =
        buildCanonicalActionUri(action = action, profile = null)

    /**
     * Builds the short readable profile-specific URI such as:
     *  - switchly://enable?profile=Work
     *  - switchly://temp_enable?profile=Home&duration=30
     *  - switchly://temp_enable?profile=Home&duration=ask
     *  - switchly://temp_disable?profile=Home&duration=ask
     */
    fun uriForProfileAction(profile: String, action: String): String =
        buildCanonicalActionUri(action = action, profile = profile)

    // Legacy builder kept for diagnostics/tests only. New tags should use `uriForGlobalAction`.
    fun legacyUriForGlobalAction(action: String): String =
        Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST_SWITCH)
            .appendPath(action)
            .build()
            .toString()

    // Legacy builder kept for diagnostics/tests only. New tags should use `uriForProfileAction`.
    fun legacyUriForProfileAction(profile: String, action: String): String =
        Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST_PROFILE)
            .appendPath(profile)
            .appendPath(action)
            .build()
            .toString()

    // -------------------------------------------------------------------------
    // Parsing helpers
    // -------------------------------------------------------------------------

    // Sealed hierarchy representing what a parsed NFC/deep-link command means.
    sealed interface NfcCommand {
        val action: String

        /**
         * Convenient helper for temp actions. Returns the minutes part, or null.
         * Example:
         *  - action = "temp_disable10" -> 10
         *  - action = "temp_enable120" -> 120
         *  - action = "enable" -> null
         */
        fun tempMinutesOrNull(): Int? = parseTempMinutes(action)
    }

    data class GlobalCommand(
        override val action: String
    ) : NfcCommand

    data class ProfileCommand(
        val profile: String,
        override val action: String
    ) : NfcCommand

    /**
     * Tries to extract the number of minutes from a temp action like "temp_disable10".
     *
     * Examples:
     *  - "temp_disable10" -> 10
     *  - "temp_enable120" -> 120
     *  - "enable" -> null
     */
    fun parseTempMinutes(action: String): Int? {
        val match = TEMP_ACTION_REGEX.matchEntire(action) ?: return null
        val minutesStr = match.groupValues.getOrNull(1) ?: return null
        return minutesStr.toIntOrNull()
    }

    private fun buildCanonicalActionUri(action: String, profile: String?): String {
        val normalized = normalizeCanonicalActionParts(action) ?: CanonicalActionParts(ACTION_ENABLE, null)
        val builder = Uri.Builder()
            .scheme(SCHEME)
            .authority(normalized.type)

        profile
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.appendQueryParameter(QUERY_PROFILE, it) }

        normalized.duration
            ?.let { builder.appendQueryParameter(QUERY_DURATION, it) }

        return builder.build().toString()
    }

    private fun parseReadableActionUri(uri: Uri, typeHost: String): NfcCommand? {
        if ((uri.pathSegments ?: emptyList()).isNotEmpty()) {
            return null
        }

        val action = actionWithDuration(typeHost, durationFromQuery(uri)) ?: return null
        val profile = profileFromQuery(uri).orEmpty()
        if (!isSupportedAction(action)) {
            return null
        }

        return if (profile.isBlank()) {
            GlobalCommand(action)
        } else {
            ProfileCommand(profile, action)
        }
    }

    private fun parseQueryActionUri(uri: Uri): NfcCommand? {
        if ((uri.pathSegments ?: emptyList()).isNotEmpty()) {
            return null
        }

        val action = canonicalActionFromQuery(uri) ?: return null
        val profile = profileFromQuery(uri).orEmpty()
        if (!isSupportedAction(action)) {
            return null
        }

        return if (profile.isBlank()) {
            GlobalCommand(action)
        } else {
            ProfileCommand(profile, action)
        }
    }

    private fun canonicalActionFromQuery(uri: Uri): String? {
        val rawType = (
            uri.getQueryParameter(QUERY_TYPE)
                ?: uri.getQueryParameter(QUERY_TYPE_SHORT)
                ?: uri.getQueryParameter(QUERY_ACTION)
            )
            ?.trim()
            ?.lowercase()
            ?: return null
        if (rawType.isBlank()) {
            return null
        }

        return actionWithDuration(expandCompactActionType(rawType), durationFromQuery(uri))
    }

    private fun durationFromQuery(uri: Uri): String? =
        (uri.getQueryParameter(QUERY_DURATION)
            ?: uri.getQueryParameter(QUERY_DURATION_SHORT)
            ?: uri.getQueryParameter(QUERY_MINUTES))
            ?.trim()
            ?.lowercase()

    private fun profileFromQuery(uri: Uri): String? =
        (uri.getQueryParameter(QUERY_PROFILE) ?: uri.getQueryParameter(QUERY_PROFILE_SHORT))
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun expandCompactActionType(type: String): String =
        COMPACT_ACTION_TYPES[type.trim().lowercase()] ?: type

    private fun normalizeCanonicalActionParts(action: String): CanonicalActionParts? {
        val trimmed = action.trim().lowercase()
        if (trimmed.isBlank()) {
            return null
        }

        val fixedTemp = Regex("""^(temp_enable|temp_disable|reentry)(\d{1,4})$""").matchEntire(trimmed)
        if (fixedTemp != null) {
            val type = fixedTemp.groupValues[1]
            val duration = fixedTemp.groupValues[2]
            return CanonicalActionParts(type = type, duration = duration)
        }

        if (trimmed in setOf("temp_enable", "temp_disable")) {
            return CanonicalActionParts(type = trimmed, duration = DURATION_ASK)
        }

        return CanonicalActionParts(type = trimmed, duration = null)
    }

    private fun actionWithDuration(type: String, duration: String?): String? {
        val normalizedType = expandCompactActionType(type.trim().lowercase())
        val normalizedDuration = duration?.trim()?.lowercase().orEmpty()

        if (normalizedType.matches(Regex("""^(temp_enable|temp_disable|reentry)\d{1,4}$"""))) {
            return normalizedType
        }

        if (normalizedType !in setOf("temp_enable", "temp_disable", "reentry")) {
            return normalizedType.takeIf { isSupportedAction(it) }
        }

        if (normalizedDuration.isBlank() || normalizedDuration == DURATION_ASK || normalizedDuration == DURATION_ASK_SHORT) {
            return normalizedType
        }

        val minutes = normalizedDuration.toIntOrNull()?.coerceIn(1, 1440) ?: return null
        return "$normalizedType$minutes"
    }

    private data class CanonicalActionParts(
        val type: String,
        val duration: String?
    )
}
