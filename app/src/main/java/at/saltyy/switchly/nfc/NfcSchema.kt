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
 *    or a temporary-action like `temp10` (minutes).
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

    // Regex for actions like "temp5", "temp30", "temp120", etc.
    private val TEMP_ACTION_REGEX = Regex("""temp(\d{1,4})""")

    /**
     * Returns true if the given host is one of the supported hosts.
     */
    fun isKnownHost(host: String?): Boolean =
        host.equals(HOST_SWITCH, ignoreCase = true) ||
            host.equals(HOST_PROFILE, ignoreCase = true)

    // -------------------------------------------------------------------------
    // Public URI builders
    // -------------------------------------------------------------------------

    /**
     * Builds a global URI such as:
     *  - switchly://switch/toggle
     *  - switchly://switch/temp10
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
     *  - switchly://profile/Home/temp30
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
         * Convenient helper for temp actions. Returns the "X" in tempX, or null.
         * Example:
         *  - action = "temp10" -> 10
         *  - action = "temp120" -> 120
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
     * Tries to extract the number of minutes from a temp action like "temp10".
     *
     * Examples:
     *  - "temp10"  -> 10
     *  - "temp120" -> 120
     *  - "enable"  -> null
     */
    fun parseTempMinutes(action: String): Int? {
        val match = TEMP_ACTION_REGEX.matchEntire(action) ?: return null
        val minutesStr = match.groupValues.getOrNull(1) ?: return null
        return minutesStr.toIntOrNull()
    }
}
