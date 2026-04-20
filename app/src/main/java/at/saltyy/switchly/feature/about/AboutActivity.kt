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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.PlayStoreUpdatePrompt
import com.google.android.material.appbar.MaterialToolbar

class AboutActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_versions)

        setupToolbar()
        hideUnusedRows()
        bindAboutTiles()
        bindContactTiles()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun setupToolbar() {
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
    }

    private fun hideUnusedRows() {
        runCatching { findViewById<View>(R.id.rowDeveloper).visibility = View.GONE }
        runCatching { findViewById<View>(R.id.rowDevice).visibility = View.GONE }
        runCatching { findViewById<View>(R.id.rowAndroid).visibility = View.GONE }
        runCatching { findViewById<View>(R.id.rowPackage).visibility = View.GONE }
    }

    private fun bindAboutTiles() {
        val appName = getString(R.string.app_name)
        val versionName = resolveVersionName()
        val packageNameLabel = packageName
        val downloadsUrl = getString(R.string.about_downloads_url)
        val androidVersionLabel = getString(
            R.string.about_android_version_fmt,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT
        )
        val deviceModelLabel = buildDeviceLabel()
        val developerName = getString(R.string.about_developer_name)

        val gitlabUrl = getString(R.string.about_gitlab_url)
        bindTile(
            rootId = R.id.rowDiscord,
            titleId = R.id.tvTitleDiscord,
            subtitleId = R.id.tvSubtitleDiscord,
            title = getString(R.string.about_gitlab_title),
            subtitle = gitlabUrl,
            onClick = { openLink(gitlabUrl) },
            copyValue = gitlabUrl
        )

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
        updateVersionSubtitleIfNeeded(versionName)

        bindTile(
            rootId = R.id.rowPackage,
            titleId = R.id.tvTitlePackage,
            subtitleId = R.id.tvSubtitlePackage,
            title = getString(R.string.about_package),
            subtitle = packageNameLabel,
            onClick = {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = "package:$packageNameLabel".toUri()
                        }
                    )
                }
            },
            copyValue = packageNameLabel
        )

        bindTile(
            rootId = R.id.rowAndroid,
            titleId = R.id.tvTitleAndroid,
            subtitleId = R.id.tvSubtitleAndroid,
            title = getString(R.string.about_android),
            subtitle = androidVersionLabel,
            onClick = null,
            copyValue = androidVersionLabel
        )

        bindTile(
            rootId = R.id.rowDevice,
            titleId = R.id.tvTitleDevice,
            subtitleId = R.id.tvSubtitleDevice,
            title = getString(R.string.about_device),
            subtitle = deviceModelLabel,
            onClick = null,
            copyValue = deviceModelLabel
        )

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
            subtitle = downloadsUrl,
            onClick = { openUrl(downloadsUrl) },
            copyValue = downloadsUrl
        )
    }

    private fun bindContactTiles() {
        val discordUrl = getString(R.string.about_discord_url)
        val emailAddress = getString(R.string.about_mail_address)

        bindTileWithCopyButton(
            rootId = R.id.rowDiscord,
            titleId = R.id.tvTitleDiscord,
            subtitleId = R.id.tvSubtitleDiscord,
            copyBtnId = R.id.btnCopyDiscord,
            title = getString(R.string.about_discord),
            subtitle = discordUrl,
            onClick = { openUrl(discordUrl) },
            copyValue = discordUrl,
            copiedToast = getString(R.string.about_discord_copied)
        )

        bindTileWithCopyButton(
            rootId = R.id.rowMail,
            titleId = R.id.tvTitleMail,
            subtitleId = R.id.tvSubtitleMail,
            copyBtnId = R.id.btnCopyMail,
            title = getString(R.string.about_mail),
            subtitle = emailAddress,
            onClick = { openMail(emailAddress) },
            copyValue = emailAddress,
            copiedToast = getString(R.string.about_mail_copied)
        )
    }

    private fun updateVersionSubtitleIfNeeded(versionName: String) {
        PlayStoreUpdatePrompt.checkAvailability(this) { available ->
            if (available) {
                runCatching {
                    val subtitleView = findViewById<TextView>(R.id.tvSubtitleVersion)
                    subtitleView.text = getString(
                        R.string.about_version_with_update,
                        versionName,
                        getString(R.string.update_available_inline)
                    )
                }
            }
        }
    }

    private fun resolveVersionName(): String {
        return runCatching {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "-"
        }.getOrDefault("-")
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
        val titleView = findViewById<TextView>(titleId)
        val subtitleView = findViewById<TextView>(subtitleId)

        titleView.text = title
        subtitleView.text = subtitle

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

        val copyButton = findViewById<ImageButton>(copyBtnId)
        copyButton.setOnClickListener {
            copyToClipboard(copyValue)
            Toast.makeText(this, copiedToast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText("Switchly", text))
    }

    private fun openMail(email: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = "mailto:$email".toUri()
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_mail_subject))
        }

        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.about_mail)))
        }.onFailure {
            Toast.makeText(this, getString(R.string.about_no_mail_app), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openLink(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
    }

    private fun buildDeviceLabel(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val prettyManufacturer = manufacturer.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }

        return when {
            model.isBlank() -> prettyManufacturer.ifBlank { "-" }
            prettyManufacturer.isBlank() -> model
            model.startsWith(prettyManufacturer, ignoreCase = true) -> model
            else -> "$prettyManufacturer $model"
        }
    }
}
