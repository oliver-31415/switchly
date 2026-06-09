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
import android.text.format.DateUtils
import android.widget.RemoteViews
import androidx.core.net.toUri
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.SchedulePlanner
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.feature.entry.QuickActionIconFactory
import at.saltyy.switchly.feature.schedule.SchedulesActivity
import at.saltyy.switchly.util.TimeFormatPrefs
import java.util.Calendar

class NextScheduleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        appWidgetIds.forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            SchedulePlanner.ACTION_NEXT_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED -> refreshAll(context)
        }
    }

    companion object {
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, NextScheduleWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            ids.forEach { appWidgetId ->
                manager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
            }
        }

        private fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
            val content = buildContent(context)
            return RemoteViews(context.packageName, R.layout.widget_next_schedule).apply {
                setImageViewBitmap(R.id.widgetNextScheduleIcon, QuickActionIconFactory.createWidgetBitmap(context, R.drawable.schedule_24))
                setTextViewText(R.id.widgetNextScheduleName, content.name)
                setTextViewText(R.id.widgetNextScheduleTime, content.time)
                setTextViewText(R.id.widgetNextScheduleStatus, content.status)
                setOnClickPendingIntent(
                    R.id.widgetNextScheduleRoot,
                    PendingIntent.getActivity(
                        context,
                        2201 + appWidgetId,
                        Intent(context, SchedulesActivity::class.java)
                            .setData("switchly://widget/schedule/$appWidgetId".toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
        }

        private fun buildContent(context: Context): DisplayContent {
            if (!AutomationModeStore.isScheduleAllowed(context)) {
                return DisplayContent(
                    name = context.getString(R.string.widget_next_schedule_name),
                    time = context.getString(R.string.schedules_next_inactive_control_mode),
                    status = context.getString(R.string.widget_next_schedule_open)
                )
            }

            val next = findNextBoundary(context)
            if (next == null) {
                return DisplayContent(
                    name = context.getString(R.string.widget_next_schedule_name),
                    time = context.getString(R.string.schedules_next_none),
                    status = context.getString(R.string.widget_next_schedule_open)
                )
            }

            return DisplayContent(
                name = next.label,
                time = formatTime(context, next.timeMillis),
                status = DateUtils.getRelativeTimeSpanString(
                    next.timeMillis,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                ).toString()
            )
        }

        private fun formatTime(context: Context, timeMillis: Long): String {
            val cal = Calendar.getInstance().apply { timeInMillis = timeMillis }
            val minutesOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            return TimeFormatPrefs.formatMinutesOfDay(context, minutesOfDay)
        }

        private fun findNextBoundary(context: Context): BoundaryInfo? {
            if (SchedulePlanner.getNextBoundaryMillis(context) <= 0L) return null
            val schedules = ScheduleStore.getAll(context)
                .filter { it.enabled }
                .filterNot { schedule ->
                    val isConnectionOnly = !schedule.wifiSsid.isNullOrBlank() || (!schedule.btDeviceName.isNullOrBlank() || !schedule.btDeviceAddress.isNullOrBlank())
                    isConnectionOnly && schedule.startMinutes == 0 && schedule.endMinutes >= 1439
                }
            if (schedules.isEmpty()) return null

            val now = Calendar.getInstance()
            val nowMs = now.timeInMillis
            var best: BoundaryInfo? = null

            fun consider(candidate: BoundaryInfo) {
                if (candidate.timeMillis <= nowMs) return
                if (best == null || candidate.timeMillis < best!!.timeMillis) {
                    best = candidate
                }
            }

            schedules.forEach { schedule ->
                when (schedule.type) {
                    ScheduleStore.Type.WEEKLY -> {
                        repeat(14) { offset ->
                            val day = Calendar.getInstance().apply {
                                timeInMillis = nowMs
                                add(Calendar.DAY_OF_YEAR, offset)
                            }
                            val bit = ScheduleStore.Days.fromCalendarDay(day.get(Calendar.DAY_OF_WEEK))
                            if (schedule.daysMask and bit == 0) return@repeat
                            considerBoundary(context, schedule, day)?.let(::consider)
                            considerBoundary(context, schedule, day, endBoundary = true)?.let(::consider)
                        }
                    }
                    ScheduleStore.Type.ONE_TIME -> {
                        if (schedule.startDate <= 0 || schedule.endDate <= 0) return@forEach
                        repeat(30) { offset ->
                            val day = Calendar.getInstance().apply {
                                timeInMillis = nowMs
                                add(Calendar.DAY_OF_YEAR, offset)
                            }
                            val ymd = day.get(Calendar.YEAR) * 10000 + (day.get(Calendar.MONTH) + 1) * 100 + day.get(Calendar.DAY_OF_MONTH)
                            if (ymd !in schedule.startDate..schedule.endDate) return@repeat
                            considerBoundary(context, schedule, day)?.let(::consider)
                            considerBoundary(context, schedule, day, endBoundary = true)?.let(::consider)
                        }
                    }
                }
            }

            return best
        }

        private fun considerBoundary(
            context: Context,
            schedule: ScheduleStore.Schedule,
            day: Calendar,
            endBoundary: Boolean = false,
        ): BoundaryInfo? {
            if (endBoundary && !isRangeAction(schedule.action)) return null
            val minutes = if (endBoundary) schedule.endMinutes else schedule.startMinutes
            val timeMillis = (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, minutes / 60)
                set(Calendar.MINUTE, minutes % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            return BoundaryInfo(
                timeMillis = timeMillis,
                label = schedule.title.ifBlank {
                    schedule.note.ifBlank {
                        schedule.profile.ifBlank { context.getString(R.string.widget_next_schedule_name) }
                    }
                }
            )
        }

        private fun isRangeAction(action: ScheduleStore.Action): Boolean {
            return when (action) {
                ScheduleStore.Action.ENABLE_AND_DISABLE,
                ScheduleStore.Action.DISABLE_AND_ENABLE -> true
                else -> false
            }
        }
    }

    private data class DisplayContent(
        val name: String,
        val time: String,
        val status: String,
    )

    private data class BoundaryInfo(
        val timeMillis: Long,
        val label: String,
    )
}
