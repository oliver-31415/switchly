package at.saltyy.switchly.data.prefs

/**
 * Preference keys for toggleable blocking options (in-app & websites).
 */
object BlockingToggleKeys {
    // Master toggles
    const val KEY_BLOCK_WEBSITES = "block_websites_toggle"
    const val KEY_BLOCK_INAPP = "block_inapp_toggle"

    // YouTube
    const val KEY_BLOCK_YT_SHORTS = "block_yt_shorts"
    const val KEY_BLOCK_YT_SEARCH = "block_yt_search"
    const val KEY_BLOCK_YT_COMMENTS = "block_yt_comments"
    const val KEY_BLOCK_YT_PIP = "block_yt_pip"

    // Instagram
    const val KEY_BLOCK_IG_REELS = "block_ig_reels"
    const val KEY_BLOCK_IG_EXPLORE = "block_ig_explore"
    const val KEY_BLOCK_IG_STORIES = "block_ig_stories"
    const val KEY_BLOCK_IG_COMMENTS = "block_ig_comments"

    // NFC writer
    const val KEY_ENABLE_REENTRY_IN_WRITE = "enable_reentry_in_write"
    const val KEY_ENABLE_EMERGENCY_IN_WRITE = "enable_emergency_in_write"

    // NFC paired UID feature (gate UI + enforcement)
    const val KEY_ENABLE_PAIRED_UIDS = "enable_paired_uids"

    // NFC anti-overuse
    const val KEY_LIMIT_TEMP_DISABLE_TAGS = "limit_temp_disable_tags"

    // lock Settings/control screens while protection is active
    const val KEY_LOCK_SWITCHLY_APP_ACCESS = "lock_switchly_app_access"

    private val IN_APP_SURFACE_KEYS = setOf(
        KEY_BLOCK_YT_SHORTS,
        KEY_BLOCK_YT_SEARCH,
        KEY_BLOCK_YT_COMMENTS,
        KEY_BLOCK_YT_PIP,

        KEY_BLOCK_IG_REELS,
        KEY_BLOCK_IG_EXPLORE,
        KEY_BLOCK_IG_STORIES,
        KEY_BLOCK_IG_COMMENTS
    )

    fun isInAppSurfaceKey(baseKey: String): Boolean = baseKey in IN_APP_SURFACE_KEYS
}
