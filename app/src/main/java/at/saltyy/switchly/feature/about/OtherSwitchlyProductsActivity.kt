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
import android.content.res.Configuration
import android.graphics.Color
import android.view.Menu
import android.view.MenuItem
import androidx.core.net.toUri
import at.saltyy.switchly.R
import at.saltyy.switchly.ui.dialog.showInfoDialog

class OtherSwitchlyProductsActivity : TilesInfoActivity() {

    private companion object {
        private const val MENU_INFO = 1001
    }

    override fun screenTitle(): String = getString(R.string.about_other_switchly_products_title)

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val toolbarIconColor = toolbarForegroundColor()
        menu.add(Menu.NONE, MENU_INFO, Menu.NONE, R.string.about_other_apps_info_title).apply {
            setIcon(R.drawable.info_24)
            icon?.mutate()?.setTint(toolbarIconColor)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_INFO) {
            showInfoDialog(R.string.about_other_apps_info_title, R.string.about_other_apps_info_body)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun toolbarForegroundColor(): Int {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (night) {
            Color.WHITE
        } else {
            Color.BLACK
        }
    }

    override fun tiles(): List<Tile> {
        val liteLink = getString(R.string.about_switchly_lite_playstore_url)
        val launcherLink = getString(R.string.about_switchly_launcher_playstore_url)

        return listOf(
            Tile(
                getString(R.string.about_switchly_lite),
                getString(R.string.about_switchly_lite_bonus),
                sectionTitle = getString(R.string.about_switchly_lite),
                iconRes = R.drawable.nfc_24
            ),
            Tile(
                getString(R.string.about_switchly_lite_play_store_label),
                displayPlayAppId(liteLink),
                sectionTitle = getString(R.string.about_switchly_lite),
                onClick = { openUrl(liteLink) },
                copyValue = liteLink,
                showCopyButton = true,
                iconRes = R.drawable.cloud_download_24,
                copiedToast = getString(R.string.about_play_store_link_copied)
            ),
            Tile(
                getString(R.string.about_switchly_launcher),
                getString(R.string.about_switchly_launcher_bonus),
                sectionTitle = getString(R.string.about_switchly_launcher),
                iconRes = R.drawable.dashboard_24
            ),
            Tile(
                getString(R.string.about_switchly_launcher_play_store_label),
                displayPlayAppId(launcherLink),
                sectionTitle = getString(R.string.about_switchly_launcher),
                onClick = { openUrl(launcherLink) },
                copyValue = launcherLink,
                showCopyButton = true,
                iconRes = R.drawable.cloud_download_24,
                copiedToast = getString(R.string.about_play_store_link_copied)
            )
        )
    }

    private fun openUrl(url: String) {
        val i = Intent(Intent.ACTION_VIEW, url.toUri())
        runCatching { startActivity(i) }
    }

    private fun displayUrl(url: String): String =
        url.removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/")

    private fun displayPlayAppId(url: String): String =
        runCatching { url.toUri().getQueryParameter("id") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: displayUrl(url)
}
