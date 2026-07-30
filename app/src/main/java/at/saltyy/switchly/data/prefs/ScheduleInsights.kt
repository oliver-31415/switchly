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

package at.saltyy.switchly.data.prefs

import android.content.Context
import at.saltyy.switchly.R
import at.saltyy.switchly.util.TimeFormatPrefs
import java.util.Calendar

object ScheduleInsights {

    data class Overlap(
        val first: ScheduleStore.Schedule,
        val second: ScheduleStore.Schedule
    )

    data class TimelineEvent(
        val timeMs: Long,
        val schedule: ScheduleStore.Schedule,
        val label: String
    )

    fun activeRangeSummary(context: Context, schedules: List<ScheduleStore.Schedule>): String? {
        val activeId = ScheduleRuntimeStore.getActiveRangeScheduleId(context)
        val active = schedules.firstOrNull { it.id == activeId && it.enabled } ?: return null
        val since = TimeFormatPrefs.formatMinutesOfDay(context, active.startMinutes)
        val next = TimeFormatPrefs.formatMinutesOfDay(context, active.endMinutes)
        val name = scheduleDisplayName(active)
        return context.getString(
            R.string.schedules_insight_active_tag_fmt,
            name,
            active.profile.ifBlank { "-" },
            since,
            next
        )
    }

    fun detectOverlaps(schedules: List<ScheduleStore.Schedule>): List<Overlap> {
        val enabled = schedules.filter {
            it.enabled &&
                !it.isLocationSchedule() &&
                it.profile.isNotBlank()
        }
        val out = mutableListOf<Overlap>()
        for (i in enabled.indices) {
            for (j in i + 1 until enabled.size) {
                val a = enabled[i]
                val b = enabled[j]
                if (a.id == b.id) continue
                if (a.profile == b.profile) continue
                if (!dateOrDaysIntersect(a, b)) continue
                if (!timeSchedulesConflict(a, b)) continue
                out += Overlap(a, b)
            }
        }
        return out
    }

    fun buildTimeline(context: Context, schedules: List<ScheduleStore.Schedule>, maxItems: Int = 6): List<TimelineEvent> {
        val now = System.currentTimeMillis()
        val end = now + 24L * 60L * 60L * 1000L
        val nowCal = Calendar.getInstance()
        val events = mutableListOf<TimelineEvent>()

        for (schedule in schedules.filter { it.enabled && !it.isLocationSchedule() }) {
            for (offsetDays in 0..1) {
                val day = Calendar.getInstance().apply {
                    timeInMillis = nowCal.timeInMillis
                    add(Calendar.DAY_OF_YEAR, offsetDays)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (!appliesOnDay(schedule, day)) continue

                val startMs = day.timeInMillis + schedule.startMinutes.coerceIn(0, 1439) * 60_000L
                addTimelineEventIfInWindow(context, events, startMs, now, end, schedule, schedule.actionLabel(context))

                if (isRangeLike(schedule)) {
                    var endMs = day.timeInMillis + schedule.endMinutes.coerceIn(0, 1439) * 60_000L
                    if (schedule.endMinutes <= schedule.startMinutes) endMs += 24L * 60L * 60L * 1000L
                    val endLabel = when (schedule.action) {
                        ScheduleStore.Action.ENABLE_AND_DISABLE -> context.getString(R.string.schedules_action_disable)
                        ScheduleStore.Action.DISABLE_AND_ENABLE -> context.getString(R.string.schedules_action_enable)
                        else -> schedule.actionLabel(context)
                    }
                    addTimelineEventIfInWindow(context, events, endMs, now, end, schedule, endLabel)
                }
            }
        }

        return events.sortedBy { it.timeMs }.take(maxItems.coerceAtLeast(1))
    }

    fun isActiveNow(schedule: ScheduleStore.Schedule, now: Calendar = Calendar.getInstance()): Boolean {
        if (!schedule.enabled || schedule.isLocationSchedule()) {
            return false
        }
        if (!appliesOnDay(schedule, now)) {
            return false
        }
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (isRangeLike(schedule)) {
            inTimeRange(minutes, schedule.startMinutes, schedule.endMinutes)
        } else {
            minutes == schedule.startMinutes
        }
    }

    fun scheduleDisplayName(schedule: ScheduleStore.Schedule): String {
        return schedule.title.ifBlank {
            schedule.profile.ifBlank { "#${schedule.id}" }
        }
    }

    private fun addTimelineEventIfInWindow(
        context: Context,
        events: MutableList<TimelineEvent>,
        timeMs: Long,
        startMs: Long,
        endMs: Long,
        schedule: ScheduleStore.Schedule,
        actionLabel: String
    ) {
        if (timeMs < startMs || timeMs > endMs) {
            return
        }
        val time = TimeFormatPrefs.formatMinutesOfDay(
            context,
            Calendar.getInstance().apply { timeInMillis = timeMs }.let {
                it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
            }
        )
        val title = scheduleDisplayName(schedule)
        val label = context.getString(
            R.string.schedules_insight_timeline_item_fmt,
            time,
            actionLabel,
            title,
            schedule.profile.ifBlank { "-" }
        )
        events += TimelineEvent(timeMs, schedule, label)
    }

    private fun ScheduleStore.Schedule.actionLabel(context: Context): String = when (action) {
        ScheduleStore.Action.ENABLE -> context.getString(R.string.schedules_action_enable)
        ScheduleStore.Action.DISABLE -> context.getString(R.string.schedules_action_disable)
        ScheduleStore.Action.TOGGLE -> context.getString(R.string.schedules_action_toggle)
        ScheduleStore.Action.ENABLE_AND_DISABLE -> context.getString(R.string.schedules_action_enable_disable)
        ScheduleStore.Action.DISABLE_AND_ENABLE -> context.getString(R.string.schedules_action_disable_enable)
    }

    private fun isRangeLike(schedule: ScheduleStore.Schedule): Boolean {
        return schedule.action == ScheduleStore.Action.ENABLE_AND_DISABLE ||
            schedule.action == ScheduleStore.Action.DISABLE_AND_ENABLE ||
            schedule.startMinutes != schedule.endMinutes
    }

    private fun appliesOnDay(schedule: ScheduleStore.Schedule, day: Calendar): Boolean {
        return when (schedule.type) {
            ScheduleStore.Type.WEEKLY -> {
                val bit = ScheduleStore.Days.fromCalendarDay(day.get(Calendar.DAY_OF_WEEK))
                schedule.daysMask and bit != 0
            }
            ScheduleStore.Type.ONE_TIME -> {
                val ymd = day.get(Calendar.YEAR) * 10000 +
                    (day.get(Calendar.MONTH) + 1) * 100 +
                    day.get(Calendar.DAY_OF_MONTH)
                schedule.startDate > 0 && schedule.endDate > 0 && ymd in schedule.startDate..schedule.endDate
            }
        }
    }

    private fun dateOrDaysIntersect(a: ScheduleStore.Schedule, b: ScheduleStore.Schedule): Boolean {
        return when {
            a.type == ScheduleStore.Type.WEEKLY && b.type == ScheduleStore.Type.WEEKLY ->
                a.daysMask and b.daysMask != 0
            a.type == ScheduleStore.Type.ONE_TIME && b.type == ScheduleStore.Type.ONE_TIME ->
                a.startDate <= b.endDate && b.startDate <= a.endDate
            else -> true
        }
    }

    private fun timeWindowsOverlap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean {
        val a = splitWindow(aStart, aEnd)
        val b = splitWindow(bStart, bEnd)
        return a.any { aw -> b.any { bw -> aw.first < bw.second && bw.first < aw.second } }
    }

    private fun timeSchedulesConflict(
        a: ScheduleStore.Schedule,
        b: ScheduleStore.Schedule
    ): Boolean {
        val aRange = isRangeLike(a)
        val bRange = isRangeLike(b)
        return when {
            aRange && bRange -> timeWindowsOverlap(a.startMinutes, a.endMinutes, b.startMinutes, b.endMinutes)
            !aRange && !bRange -> normalizedMinute(a.startMinutes) == normalizedMinute(b.startMinutes)
            aRange -> timeWindowContainsPoint(a.startMinutes, a.endMinutes, b.startMinutes)
            else -> timeWindowContainsPoint(b.startMinutes, b.endMinutes, a.startMinutes)
        }
    }

    private fun timeWindowContainsPoint(start: Int, end: Int, point: Int): Boolean {
        val p = normalizedMinute(point)
        return splitWindow(start, end).any { window ->
            p >= window.first && p < window.second
        }
    }

    private fun normalizedMinute(value: Int): Int {
        return value.coerceIn(0, 1439)
    }

    private fun splitWindow(start: Int, end: Int): List<Pair<Int, Int>> {
        val s = normalizedMinute(start)
        val e = normalizedMinute(end)
        if (s == e) {
            return listOf(0 to 1440)
        }
        return if (e > s) {
            listOf(s to e)
        } else {
            listOf(s to 1440, 0 to e)
        }
    }

    private fun inTimeRange(nowMin: Int, startMin: Int, endMin: Int): Boolean {
        if (startMin == endMin) {
            return true
        }
        return if (endMin > startMin) {
            nowMin in startMin until endMin
        } else {
            nowMin >= startMin || nowMin < endMin
        }
    }
}
