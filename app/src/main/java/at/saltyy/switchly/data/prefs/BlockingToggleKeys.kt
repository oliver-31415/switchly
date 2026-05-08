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
    const val KEY_BLOCK_IG_SEARCH = "block_ig_search"
    const val KEY_BLOCK_IG_STORIES = "block_ig_stories"
    const val KEY_BLOCK_IG_COMMENTS = "block_ig_comments"

    // X / Twitter
    const val KEY_BLOCK_X_HOME = "block_x_home"
    const val KEY_BLOCK_X_SEARCH = "block_x_search"
    const val KEY_BLOCK_X_GROK = "block_x_grok"
    const val KEY_BLOCK_X_NOTIFICATIONS = "block_x_notifications"

    // Snapchat
    const val KEY_BLOCK_SNAP_MAP = "block_snap_map"
    const val KEY_BLOCK_SNAP_STORIES = "block_snap_stories"
    const val KEY_BLOCK_SNAP_SPOTLIGHT = "block_snap_spotlight"
    const val KEY_BLOCK_SNAP_FOLLOWING = "block_snap_following"

    // NFC writer
    const val KEY_ENABLE_REENTRY_IN_WRITE = "enable_reentry_in_write"
    const val KEY_ENABLE_EMERGENCY_IN_WRITE = "enable_emergency_in_write"

    // NFC paired UID feature (gate UI + enforcement)
    const val KEY_ENABLE_PAIRED_UIDS = "enable_paired_uids"
    const val KEY_AUTO_PAIR_ON_WRITE = "auto_pair_on_write"

    // Temporary action anti-overuse
    const val KEY_LIMIT_TEMP_DISABLE_TAGS = "limit_temp_disable_tags"
    const val KEY_LIMIT_TEMP_QR_CODES = "limit_temp_qr_codes"

    // lock Settings/control screens while protection is active
    const val KEY_LOCK_SWITCHLY_APP_ACCESS = "lock_switchly_app_access"

    private val IN_APP_SURFACE_KEYS = setOf(
        KEY_BLOCK_YT_SHORTS,
        KEY_BLOCK_YT_SEARCH,
        KEY_BLOCK_YT_COMMENTS,
        KEY_BLOCK_YT_PIP,

        KEY_BLOCK_IG_REELS,
        KEY_BLOCK_IG_EXPLORE,
        KEY_BLOCK_IG_SEARCH,
        KEY_BLOCK_IG_STORIES,
        KEY_BLOCK_IG_COMMENTS,

        KEY_BLOCK_X_HOME,
        KEY_BLOCK_X_SEARCH,
        KEY_BLOCK_X_GROK,
        KEY_BLOCK_X_NOTIFICATIONS,

        KEY_BLOCK_SNAP_MAP,
        KEY_BLOCK_SNAP_STORIES,
        KEY_BLOCK_SNAP_SPOTLIGHT,
        KEY_BLOCK_SNAP_FOLLOWING
    )

    fun isInAppSurfaceKey(baseKey: String): Boolean = baseKey in IN_APP_SURFACE_KEYS
}
