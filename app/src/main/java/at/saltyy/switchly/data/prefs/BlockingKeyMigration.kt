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
        BlockingToggleKeys.KEY_BLOCK_IG_STORIES to listOf(
            "pref_block_ig_stories",
            "block_ig_stories",
            "inapp_block_instagram_stories"
        ),
        BlockingToggleKeys.KEY_BLOCK_IG_COMMENTS to listOf("block_ig_comments"),

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
