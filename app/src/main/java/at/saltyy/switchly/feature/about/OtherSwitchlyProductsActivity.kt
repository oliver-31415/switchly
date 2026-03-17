package at.saltyy.switchly.feature.about

import android.content.Intent
import androidx.core.net.toUri
import at.saltyy.switchly.R

class OtherSwitchlyProductsActivity : TilesInfoActivity() {

    override fun screenTitle(): String = getString(R.string.about_other_switchly_products_title)

    override fun tiles(): List<Tile> {
        val liteLink = getString(R.string.about_switchly_lite_playstore_url)
        val launcherLink = getString(R.string.about_switchly_launcher_playstore_url)

        return listOf(
            Tile(
                getString(R.string.about_switchly_lite),
                getString(R.string.about_switchly_lite_bonus),
            ),
            Tile(
                getString(R.string.about_switchly_lite_play_store_label),
                liteLink,
                onClick = { openUrl(liteLink) },
                showCopyButton = true,
                copiedToast = getString(R.string.about_play_store_link_copied)
            ),
            Tile(
                getString(R.string.about_switchly_launcher),
                getString(R.string.about_switchly_launcher_bonus),
            ),
            Tile(
                getString(R.string.about_switchly_launcher_play_store_label),
                launcherLink,
                onClick = { openUrl(launcherLink) },
                showCopyButton = true,
                copiedToast = getString(R.string.about_play_store_link_copied)
            )
        )
    }

    private fun openUrl(url: String) {
        val i = Intent(Intent.ACTION_VIEW, url.toUri())
        runCatching { startActivity(i) }
    }
}
