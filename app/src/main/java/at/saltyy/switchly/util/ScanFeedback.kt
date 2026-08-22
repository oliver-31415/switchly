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

package at.saltyy.switchly.util

import android.content.Context
import android.widget.Toast
import at.saltyy.switchly.data.prefs.AppLogStore

object ScanFeedback {
    fun error(
        context: Context,
        source: String,
        reason: String,
        message: CharSequence,
        long: Boolean = false,
    ) {
        AppLogStore.append(
            context,
            source,
            "scan_result status=error reason=$reason"
        )
        Toast.makeText(
            context.applicationContext,
            message,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
        ).show()
    }

    fun noop(context: Context, source: String, reason: String, message: CharSequence) {
        AppLogStore.append(
            context,
            source,
            "scan_result status=noop reason=$reason"
        )
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
