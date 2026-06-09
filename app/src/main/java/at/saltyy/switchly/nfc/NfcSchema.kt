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
 *
 * Supported URI patterns:
 *
 *  Global actions:
 *    switchly://switch/<action>
 *
 *  Profile-based actions:
 *    switchly://profile/<profileName>/<action>
 *
 * Notes:
 *  - "action" can be one of the standard actions (`enable`, `disable`, `toggle`)
 *    or a temporary action like `temp_disable10`, `temp_enable10`, or `reentry10`.
 *  - All patterns are uniform, which makes parsing straightforward.
 */
object NfcSchema {

    // Scheme
    private const val SCHEME = "switchly"

    // Hosts
    const val HOST_SWITCH = "switch"
    const val HOST_PROFILE = "profile"

    // Official base actions (EntryActivity may also handle dynamic tempX actions)
    const val ACTION_ENABLE = "enable"

    // Default temporary disable duration (10 minutes)
    const val DEFAULT_TEMP_DISABLE_MS: Long = 10 * 60 * 1000L

    // Helper for actions like "temp_disable10", "temp_enable30", "reentry120" and legacy "temp10".
    private val TEMP_ACTION_REGEX = Regex("""(?:temp(?:_enable|_disable)?|reentry)(\d{1,4})""")

    /**
     * Returns true if the given host is one of the supported hosts.
     */
    fun isKnownHost(host: String?): Boolean =
        host.equals(HOST_SWITCH, ignoreCase = true) ||
            host.equals(HOST_PROFILE, ignoreCase = true)

    /**
     * Strict parser for externally supplied Switchly command URIs.
     * Rejects unknown hosts, query/fragment payloads, missing path segments and unsupported actions early.
     */
    fun parseCommandUri(uri: Uri?): NfcCommand? {
        uri ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!uri.query.isNullOrBlank() || !uri.fragment.isNullOrBlank()) return null

        val host = uri.host?.lowercase() ?: return null
        val segs = uri.pathSegments ?: emptyList()

        return when (host) {
            HOST_SWITCH -> {
                if (segs.size != 1) return null
                val action = segs[0].trim().lowercase()
                if (!isSupportedAction(action)) return null
                GlobalCommand(action)
            }

            HOST_PROFILE -> {
                if (segs.size != 2) return null
                val profile = segs[0].trim()
                val action = segs[1].trim().lowercase()
                if (profile.isBlank() || !isSupportedAction(action)) return null
                ProfileCommand(profile, action)
            }

            else -> null
        }
    }

    fun isSupportedCommandUri(uri: Uri?): Boolean = parseCommandUri(uri) != null

    fun isSupportedAction(action: String?): Boolean {
        val a = action?.trim()?.lowercase().orEmpty()
        if (a.isBlank()) return false
        if (a in setOf("enable", "disable", "toggle", "start", "stop", "on", "off", "activate", "emergency_disable")) return true
        return a.matches(Regex("""(temp_enable|temp_disable|reentry)(\d{1,4})?"""))
    }

    // -------------------------------------------------------------------------
    // Public URI builders
    // -------------------------------------------------------------------------

    /**
     * Builds a global URI such as:
     *  - switchly://switch/toggle
     *  - switchly://switch/temp_disable10
     */
    fun uriForGlobalAction(action: String): String =
        Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST_SWITCH)
            .appendPath(action)
            .build()
            .toString()

    /**
     * Builds a profile-specific URI such as:
     *  - switchly://profile/Work/enable
     *  - switchly://profile/Home/temp_enable30
     */
    fun uriForProfileAction(profile: String, action: String): String =
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

    /**
     * Sealed hierarchy representing what a parsed NFC/deep-link command means.
     */
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
}
