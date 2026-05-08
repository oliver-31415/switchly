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

package at.saltyy.switchly.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.StyleSpan
import android.widget.RemoteViews
import androidx.core.net.toUri
import at.saltyy.switchly.R
import at.saltyy.switchly.feature.entry.QuickActionIconFactory
import at.saltyy.switchly.feature.entry.ScanLauncherActivity

abstract class BaseLaunchWidgetProvider : AppWidgetProvider() {

    protected abstract val labelRes: Int
    protected abstract val iconRes: Int
    protected abstract val launchAction: String
    protected abstract val requestCode: Int

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        appWidgetIds.forEach { appWidgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_action_compact).apply {
                setContentDescription(R.id.widgetActionRoot, context.getString(labelRes))
                setImageViewBitmap(R.id.widgetActionIcon, QuickActionIconFactory.createWidgetBitmap(context, iconRes))
                setOnClickPendingIntent(
                    R.id.widgetActionRoot,
                    PendingIntent.getActivity(
                        context,
                        requestCode + appWidgetId,
                        Intent(context, ScanLauncherActivity::class.java)
                            .setAction(launchAction)
                            .setData("switchly://widget/${javaClass.simpleName}/$appWidgetId".toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

class QrScanWidgetProvider : BaseLaunchWidgetProvider() {
    override val labelRes: Int = R.string.shortcut_qr_short
    override val iconRes: Int = R.drawable.qr_code_24
    override val launchAction: String = ScanLauncherActivity.ACTION_OPEN_QR_SCAN
    override val requestCode: Int = 2101
}

class BarcodeScanWidgetProvider : BaseLaunchWidgetProvider() {
    override val labelRes: Int = R.string.shortcut_barcode_short
    override val iconRes: Int = R.drawable.barcode_24
    override val launchAction: String = ScanLauncherActivity.ACTION_OPEN_BARCODE_SCAN
    override val requestCode: Int = 2102
}

class NfcWriteWidgetProvider : BaseLaunchWidgetProvider() {
    override val labelRes: Int = R.string.shortcut_nfc_short
    override val iconRes: Int = R.drawable.nfc_24
    override val launchAction: String = ScanLauncherActivity.ACTION_OPEN_NFC_WRITE
    override val requestCode: Int = 2103
}

class FocusNowWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        appWidgetIds.forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
        }
    }

    companion object {
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, FocusNowWidgetProvider::class.java))
            ids.forEach { appWidgetId ->
                manager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
            }
        }

        private fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
            return RemoteViews(context.packageName, R.layout.widget_action_compact).apply {
                setContentDescription(R.id.widgetActionRoot, context.getString(R.string.shortcut_focus_now_short))
                setImageViewBitmap(R.id.widgetActionIcon, QuickActionIconFactory.createWidgetBitmap(context, R.drawable.play_arrow_24))
                setOnClickPendingIntent(
                    R.id.widgetActionRoot,
                    PendingIntent.getBroadcast(
                        context,
                        2105 + appWidgetId,
                        Intent(context, QuickActionReceiver::class.java)
                            .setAction(QuickActionReceiver.ACTION_FOCUS_NOW)
                            .setData("switchly://widget/focus/$appWidgetId".toUri()),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
        }
    }
}

class BlockedNotificationsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        appWidgetIds.forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
        }
    }

    companion object {
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BlockedNotificationsWidgetProvider::class.java))
            ids.forEach { appWidgetId ->
                manager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
            }
        }

        private fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
            val events = at.saltyy.switchly.data.prefs.BlockedInboxStore.getAll(context).take(5)
            val views = RemoteViews(context.packageName, R.layout.widget_blocked_notifications)
            views.setTextViewCompoundDrawablesRelative(R.id.widgetBlockedHeader, R.drawable.widget_notifications_20, 0, 0, 0)
            views.setOnClickPendingIntent(
                R.id.widgetBlockedRoot,
                PendingIntent.getActivity(
                    context,
                    2106 + appWidgetId,
                    Intent(context, ScanLauncherActivity::class.java)
                        .setAction(ScanLauncherActivity.ACTION_OPEN_BLOCKED_NOTIFICATIONS)
                        .setData("switchly://widget/blocked/$appWidgetId".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            if (events.isEmpty()) {
                views.setTextViewText(R.id.widgetBlockedEmpty, context.getString(R.string.blocked_inbox_empty))
                views.setViewVisibility(R.id.widgetBlockedEmpty, android.view.View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widgetBlockedEmpty, android.view.View.GONE)
            }

            val rowIds = listOf(R.id.widgetBlockedRow1, R.id.widgetBlockedRow2, R.id.widgetBlockedRow3, R.id.widgetBlockedRow4, R.id.widgetBlockedRow5)
            events.forEachIndexed { index, event ->
                val rowId = rowIds[index]
                views.setViewVisibility(rowId, android.view.View.VISIBLE)
                views.setTextViewText(rowId, formatRow(context, event))
            }
            for (i in events.size until rowIds.size) {
                views.setViewVisibility(rowIds[i], android.view.View.GONE)
            }
            return views
        }

        private fun formatRow(context: Context, event: at.saltyy.switchly.data.prefs.BlockedNotificationEvent): CharSequence {
            val appName = runCatching {
                val ai = context.packageManager.getApplicationInfo(event.pkg, 0)
                context.packageManager.getApplicationLabel(ai)?.toString().orEmpty().trim()
            }.getOrNull().takeUnless { it.isNullOrBlank() } ?: event.pkg

            val preview = sequenceOf(event.title, event.text, event.bigText, event.subText, event.summaryText, event.reason)
                .map { it.replace("\n", " ").trim() }
                .firstOrNull { it.isNotBlank() }
                ?: context.getString(R.string.blocked_inbox_content_unknown)

            val relativeTime = DateUtils.getRelativeTimeSpanString(
                event.timeMillis,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            ).toString()

            val header = "$appName • $relativeTime"
            val trimmedPreview = preview.take(72)
            return SpannableStringBuilder().apply {
                append(header)
                setSpan(
                    StyleSpan(Typeface.BOLD),
                    0,
                    appName.length.coerceAtMost(length),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (trimmedPreview.isNotBlank()) {
                    append("\n")
                    append(trimmedPreview)
                }
            }
        }
    }
}

class PauseBlockerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        appWidgetIds.forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
        }
    }

    companion object {
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PauseBlockerWidgetProvider::class.java))
            ids.forEach { appWidgetId ->
                manager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
            }
        }

        private fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
            return RemoteViews(context.packageName, R.layout.widget_pause_blocker).apply {
                setTextViewCompoundDrawablesRelative(R.id.widgetPauseHeader, R.drawable.widget_toggle_off_20, 0, 0, 0)
                bindButton(context, appWidgetId, R.id.widgetPause15, 15, QuickActionReceiver.ACTION_PAUSE_SWITCHLY_15, 2301)
                bindButton(context, appWidgetId, R.id.widgetPause30, 30, QuickActionReceiver.ACTION_PAUSE_SWITCHLY_30, 2302)
                bindButton(context, appWidgetId, R.id.widgetPause60, 60, QuickActionReceiver.ACTION_PAUSE_SWITCHLY_60, 2303)
            }
        }

        private fun RemoteViews.bindButton(
            context: Context,
            appWidgetId: Int,
            viewId: Int,
            minutes: Int,
            action: String,
            requestCodeBase: Int,
        ) {
            setOnClickPendingIntent(
                viewId,
                PendingIntent.getBroadcast(
                    context,
                    requestCodeBase + appWidgetId,
                    Intent(context, QuickActionReceiver::class.java)
                        .setAction(action)
                        .setData("switchly://widget/pause/$minutes/$appWidgetId".toUri()),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
    }
}
