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

package at.saltyy.switchly.blocking

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

internal fun sanitizeWebsiteSignal(raw: String?, maxLen: Int = 120): String {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return "-"
    val noQuery = value.substringBefore('?').substringBefore('#')
    val withoutScheme = noQuery.removePrefix("https://").removePrefix("http://")
    val trimmed = noQuery.trim('/').ifBlank { withoutScheme.take(maxLen) }
    return trimmed.take(maxLen)
}

internal fun eventTypeLabel(event: AccessibilityEvent?): String {
    val type = event?.eventType ?: return "NO_EVENT"

    return when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "VIEW_TEXT_SELECTION_CHANGED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "VIEW_SELECTED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
        else -> type.toString()
    }
}

internal fun firefoxSignalSummary(root: AccessibilityNodeInfo?, event: AccessibilityEvent?): String {
    val eventText = sanitizeWebsiteSignal(event?.text?.joinToString(" | "))
    val eventDesc = sanitizeWebsiteSignal(event?.contentDescription?.toString())
    val sourceId = runCatching { event?.source?.viewIdResourceName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "-"
    val sourceText = sanitizeWebsiteSignal(runCatching { event?.source?.text?.toString() }.getOrNull())
    val sourceDesc = sanitizeWebsiteSignal(runCatching { event?.source?.contentDescription?.toString() }.getOrNull())
    val rootPkg = root?.packageName?.toString() ?: "-"
    return "event=${eventTypeLabel(event)} srcId=$sourceId eventText=$eventText eventDesc=$eventDesc srcText=$sourceText srcDesc=$sourceDesc rootPkg=$rootPkg"
}
