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

class DeveloperInfoActivity : TilesInfoActivity() {

    override fun screenTitle(): String = getString(R.string.about_developer_info_title)

    override fun tiles(): List<Tile> {
        val name = getString(R.string.dev_name_line).removePrefix("Developer: ").trim().ifBlank { getString(R.string.about_developer_name) }
        val website = getString(R.string.about_developer_website_url)
        val email = getString(R.string.dev_contact_email)

        return listOf(
            Tile(
                getString(R.string.about_dev_name_label),
                name,
                sectionTitle = getString(R.string.about_section_contact),
                iconRes = R.drawable.account_box_24
            ),
            Tile(
                getString(R.string.about_email_label),
                email,
                sectionTitle = getString(R.string.about_section_contact),
                onClick = { openMail(email) },
                copyValue = email,
                showCopyButton = true,
                iconRes = R.drawable.mail_24,
                copiedToast = getString(R.string.about_mail_copied),
                subtitleColorRes = R.color.contact_text,
                subtitleAlpha = 1f
            ),
            Tile(
                getString(R.string.about_website_label),
                displayUrl(website),
                sectionTitle = getString(R.string.about_section_contact),
                onClick = { openUrl(website) },
                copyValue = website,
                showOpenButton = true,
                iconRes = R.drawable.language_24
            ),
        )
    }

    private fun openUrl(url: String) {
        val i = Intent(Intent.ACTION_VIEW, url.toUri())
        runCatching { startActivity(i) }
    }

    private fun openMail(email: String) {
        val i = Intent(Intent.ACTION_SENDTO).apply { data = "mailto:$email".toUri() }
        runCatching { startActivity(Intent.createChooser(i, getString(R.string.about_mail))) }
    }

    private fun displayUrl(url: String): String =
        url.removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/")
}
