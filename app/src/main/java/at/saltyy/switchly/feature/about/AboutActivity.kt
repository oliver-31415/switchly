package at.saltyy.switchly.feature.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.appcompat.app.AppCompatActivity
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.PlayStoreUpdatePrompt
import com.google.android.material.appbar.MaterialToolbar

class AboutActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_versions)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        // Hide sections we don't want to show in About
        runCatching { findViewById<View>(R.id.rowDeveloper).visibility = View.GONE }
        runCatching { findViewById<View>(R.id.rowDevice).visibility = View.GONE }
        runCatching { findViewById<View>(R.id.rowAndroid).visibility = View.GONE }
        runCatching { findViewById<View>(R.id.rowPackage).visibility = View.GONE }

        val gitlab = getString(R.string.about_gitlab_url)
        runCatching {
            bindTile(
                rootId = R.id.rowDiscord,
                titleId = R.id.tvTitleDiscord,
                subtitleId = R.id.tvSubtitleDiscord,
                title = getString(R.string.about_gitlab_title),
                subtitle = gitlab,
                onClick = { openLink(gitlab) },
                copyValue = gitlab
            )
        }

        // values
        val appName = getString(R.string.app_name)
        val versionName = runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            pi.versionName ?: "-"
        }.getOrDefault("-")

        val pkg = packageName
        val website = getString(R.string.about_website_url)
        val downloads = getString(R.string.about_downloads_url)
        val androidVersion = getString(R.string.about_android_version_fmt, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
        val deviceModel = buildDeviceLabel()

        val developerName = getString(R.string.about_developer_name)
        val email = getString(R.string.about_mail_address)
        val discord = getString(R.string.about_discord_url)

        // bind app info tiles
        bindTile(
            rootId = R.id.rowAppName,
            titleId = R.id.tvTitleAppName,
            subtitleId = R.id.tvSubtitleAppName,
            title = getString(R.string.about_app_name),
            subtitle = appName,
            onClick = null,
            copyValue = appName
        )

        bindTile(
            rootId = R.id.rowVersion,
            titleId = R.id.tvTitleVersion,
            subtitleId = R.id.tvSubtitleVersion,
            title = getString(R.string.about_version),
            subtitle = versionName,
            onClick = { PlayStoreUpdatePrompt.promptNow(this) },
            copyValue = versionName
        )

        // If Play Store reports an update available, show a small hint inline.
        PlayStoreUpdatePrompt.checkAvailability(this) { available ->
            if (available) {
                runCatching {
                    val tv = findViewById<TextView>(R.id.tvSubtitleVersion)
                    tv.text = getString(
                        R.string.about_version_with_update,
                        versionName,
                        getString(R.string.update_available_inline)
                    )
                }
            }
        }

        bindTile(
            rootId = R.id.rowPackage,
            titleId = R.id.tvTitlePackage,
            subtitleId = R.id.tvSubtitlePackage,
            title = getString(R.string.about_package),
            subtitle = pkg,
            onClick = {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = "package:$pkg".toUri()
                        }
                    )
                }
            },
            copyValue = pkg
        )

        bindTile(
            rootId = R.id.rowAndroid,
            titleId = R.id.tvTitleAndroid,
            subtitleId = R.id.tvSubtitleAndroid,
            title = getString(R.string.about_android),
            subtitle = androidVersion,
            onClick = null,
            copyValue = androidVersion
        )

        bindTile(
            rootId = R.id.rowDevice,
            titleId = R.id.tvTitleDevice,
            subtitleId = R.id.tvSubtitleDevice,
            title = getString(R.string.about_device),
            subtitle = deviceModel,
            onClick = null,
            copyValue = deviceModel
        )

        // developer info
        bindTile(
            rootId = R.id.rowDeveloper,
            titleId = R.id.tvTitleDeveloper,
            subtitleId = R.id.tvSubtitleDeveloper,
            title = getString(R.string.about_developer),
            subtitle = developerName,
            onClick = null,
            copyValue = developerName
        )

        bindTile(
            rootId = R.id.rowWebsite,
            titleId = R.id.tvTitleWebsite,
            subtitleId = R.id.tvSubtitleWebsite,
            title = getString(R.string.about_older_versions_label),
            subtitle = downloads,
            onClick = { openUrl(downloads) },
            copyValue = downloads
        )

        bindTileWithCopyButton(
            rootId = R.id.rowDiscord,
            titleId = R.id.tvTitleDiscord,
            subtitleId = R.id.tvSubtitleDiscord,
            copyBtnId = R.id.btnCopyDiscord,
            title = getString(R.string.about_discord),
            subtitle = discord,
            onClick = { openUrl(discord) },
            copyValue = discord,
            copiedToast = getString(R.string.about_discord_copied)
        )

        bindTileWithCopyButton(
            rootId = R.id.rowMail,
            titleId = R.id.tvTitleMail,
            subtitleId = R.id.tvSubtitleMail,
            copyBtnId = R.id.btnCopyMail,
            title = getString(R.string.about_mail),
            subtitle = email,
            onClick = { openMail(email) },
            copyValue = email,
            copiedToast = getString(R.string.about_mail_copied)
        )
    }

    private fun bindTile(
        rootId: Int,
        titleId: Int,
        subtitleId: Int,
        title: String,
        subtitle: String,
        onClick: (() -> Unit)?,
        copyValue: String
    ) {
        val root = findViewById<View>(rootId)
        val tvTitle = findViewById<TextView>(titleId)
        val tvSubtitle = findViewById<TextView>(subtitleId)

        tvTitle.text = title
        tvSubtitle.text = subtitle

        root.isClickable = onClick != null
        root.setOnClickListener { onClick?.invoke() }

        root.setOnLongClickListener {
            copyToClipboard(copyValue)
            Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun bindTileWithCopyButton(
        rootId: Int,
        titleId: Int,
        subtitleId: Int,
        copyBtnId: Int,
        title: String,
        subtitle: String,
        onClick: (() -> Unit)?,
        copyValue: String,
        copiedToast: String
    ) {
        bindTile(rootId, titleId, subtitleId, title, subtitle, onClick, copyValue)

        val btn = findViewById<ImageButton>(copyBtnId)
        btn.setOnClickListener {
            copyToClipboard(copyValue)
            Toast.makeText(this, copiedToast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Switchly", text))
    }

    private fun openMail(email: String) {
        val i = Intent(Intent.ACTION_SENDTO).apply {
            data = "mailto:$email".toUri()
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_mail_subject))
        }
        runCatching {
            startActivity(Intent.createChooser(i, getString(R.string.about_mail)))
        }.onFailure {
            Toast.makeText(this, getString(R.string.about_no_mail_app), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(url: String) {
        val i = Intent(Intent.ACTION_VIEW, url.toUri())
        runCatching { startActivity(i) }.onFailure {
            Toast.makeText(this, getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildDeviceLabel(): String {
        val manu = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val prettyManu = manu.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return when {
            model.isBlank() -> prettyManu.ifBlank { "-" }
            prettyManu.isBlank() -> model
            model.startsWith(prettyManu, ignoreCase = true) -> model
            else -> "$prettyManu $model"
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun openLink(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
    }
}
