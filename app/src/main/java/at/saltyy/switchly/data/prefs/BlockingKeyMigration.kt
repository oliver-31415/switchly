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

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * One-time migration of legacy blocking toggle keys into [BlockingToggleKeys].
 *
 * Keeps runtime code clean (no permanent legacy key checks).
 */
object BlockingKeyMigration {
    private const val KEY_DONE = "migration_blocking_keys_v1_done"

    private val mappings: Map<String, List<String>> = mapOf(
        BlockingToggleKeys.KEY_BLOCK_WEBSITES to listOf(
            "pref_block_websites_enabled",
            "pref_block_websites"
        ),
        BlockingToggleKeys.KEY_BLOCK_YT_SHORTS to listOf(
            "pref_block_yt_shorts",
            "block_yt_shorts",
            "inapp_block_youtube_shorts"
        ),
        BlockingToggleKeys.KEY_BLOCK_YT_SEARCH to listOf(
            "pref_block_yt_search",
            "block_yt_search",
            "inapp_block_youtube_search"
        ),
        BlockingToggleKeys.KEY_BLOCK_YT_COMMENTS to listOf(
            "pref_block_yt_comments",
            "block_yt_comments",
            "inapp_block_youtube_comments"
        ),
        BlockingToggleKeys.KEY_BLOCK_YT_PIP to listOf(
            "pref_block_yt_pip",
            "block_yt_pip",
            "inapp_block_youtube_pip"
        ),
        BlockingToggleKeys.KEY_BLOCK_IG_REELS to listOf(
            "pref_block_ig_reels",
            "block_ig_reels",
            "inapp_block_instagram_reels"
        ),
        BlockingToggleKeys.KEY_BLOCK_IG_EXPLORE to listOf(
            "pref_block_ig_explore",
            "block_ig_explore",
            "inapp_block_instagram_explore"
        ),
        BlockingToggleKeys.KEY_BLOCK_IG_SEARCH to listOf(
            "pref_block_ig_search",
            "block_ig_search",
            "inapp_block_instagram_search"
        ),
        BlockingToggleKeys.KEY_BLOCK_IG_STORIES to listOf(
            "pref_block_ig_stories",
            "block_ig_stories",
            "inapp_block_instagram_stories"
        ),
        BlockingToggleKeys.KEY_BLOCK_IG_COMMENTS to listOf(
            "block_ig_comments"
        ),
        BlockingToggleKeys.KEY_BLOCK_X_FOR_YOU to listOf(
            "pref_block_x_for_you",
            "block_x_for_you",
            "inapp_block_twitter_for_you"
        ),
        BlockingToggleKeys.KEY_BLOCK_X_SEARCH to listOf(
            "pref_block_x_search",
            "block_x_search",
            "inapp_block_twitter_search"
        ),
        BlockingToggleKeys.KEY_BLOCK_X_GROK to listOf(
            "pref_block_x_grok",
            "block_x_grok",
            "inapp_block_twitter_grok"
        ),
        BlockingToggleKeys.KEY_BLOCK_X_NOTIFICATIONS to listOf(
            "pref_block_x_notifications",
            "block_x_notifications",
            "inapp_block_twitter_notifications"
        ),
        BlockingToggleKeys.KEY_BLOCK_SNAP_MAP to listOf(
            "pref_block_snap_map",
            "block_snap_map",
            "inapp_block_snapchat_map"
        ),
        BlockingToggleKeys.KEY_BLOCK_SNAP_STORIES to listOf(
            "pref_block_snap_stories",
            "block_snap_stories",
            "inapp_block_snapchat_stories"
        ),
        BlockingToggleKeys.KEY_BLOCK_SNAP_SPOTLIGHT to listOf(
            "pref_block_snap_spotlight",
            "block_snap_spotlight",
            "inapp_block_snapchat_spotlight"
        ),
        BlockingToggleKeys.KEY_BLOCK_SNAP_FOLLOWING to listOf(
            "pref_block_snap_following",
            "block_snap_following",
            "inapp_block_snapchat_following"
        ),
    )

    fun migrateOnce(ctx: Context) {
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        if (sp.getBoolean(KEY_DONE, false)) return

        sp.edit {
            mappings.forEach { (newKey, oldKeys) ->
                if (!sp.contains(newKey)) {
                    val migrated = oldKeys
                        .firstOrNull { sp.contains(it) }
                        ?.let { sp.getBoolean(it, false) }
                    if (migrated != null) putBoolean(newKey, migrated)
                }
            }
            putBoolean(KEY_DONE, true)
        }

        // Keep website subsystem flag in sync with unified toggle.
        if (sp.contains(BlockingToggleKeys.KEY_BLOCK_WEBSITES)) {
            DomainBlockStore.setEnabled(ctx, sp.getBoolean(BlockingToggleKeys.KEY_BLOCK_WEBSITES, true))
        }
    }
}
