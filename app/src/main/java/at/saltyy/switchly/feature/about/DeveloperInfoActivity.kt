package at.saltyy.switchly.feature.about

import android.content.Intent
import androidx.core.net.toUri
import at.saltyy.switchly.R

class DeveloperInfoActivity : TilesInfoActivity() {

    override fun screenTitle(): String = getString(R.string.about_developer_info_title)

    override fun tiles(): List<Tile> {
        val name = getString(R.string.dev_name_line).removePrefix("Developer: ").trim().ifBlank { getString(R.string.about_developer_name) }
        val website = getString(R.string.about_website_url)
        val email = getString(R.string.dev_contact_email)

        return listOf(
            Tile(getString(R.string.about_dev_name_label), name),
            Tile(getString(R.string.about_website_label), website, onClick = { openUrl(website) }),
            Tile(getString(R.string.about_email_label), email, onClick = { openMail(email) }, showCopyButton = true, copiedToast = getString(R.string.about_mail_copied)),
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
}
