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
