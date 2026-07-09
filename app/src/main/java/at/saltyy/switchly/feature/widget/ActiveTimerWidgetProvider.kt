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

package at.saltyy.switchly.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.ui.MainActivity

class ActiveTimerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> updateWidget(context, manager, id) }
    }

    companion object {
        fun updateAll(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val component = ComponentName(appContext, ActiveTimerWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            ids.forEach { id -> updateWidget(appContext, manager, id) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val enabled = SwitchModeStore.isEnabled(context)
            val durationMs = SwitchModeStore.getActiveDurationMillis(context)
            val views = RemoteViews(context.packageName, R.layout.widget_active_timer)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                1200 + id,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)

            if (enabled) {
                val base = SystemClock.elapsedRealtime() - durationMs.coerceAtLeast(0L)
                views.setTextViewText(R.id.widgetTitle, context.getString(R.string.widget_active_timer_title))
                views.setViewVisibility(R.id.widgetChronometer, View.VISIBLE)
                views.setChronometer(R.id.widgetChronometer, base, null, true)
            } else {
                views.setTextViewText(R.id.widgetTitle, context.getString(R.string.widget_active_timer_inactive))
                views.setChronometer(R.id.widgetChronometer, SystemClock.elapsedRealtime(), null, false)
                views.setTextViewText(R.id.widgetChronometer, "—")
                views.setViewVisibility(R.id.widgetChronometer, View.VISIBLE)
            }

            manager.updateAppWidget(id, views)
        }
    }
}
