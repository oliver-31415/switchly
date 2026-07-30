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

import android.app.Activity
import android.content.Intent
import android.os.Build

object ActivityTransitionCompat {

    private const val OVERRIDE_TRANSITION_OPEN = 0
    private const val OVERRIDE_TRANSITION_CLOSE = 1

    fun startWithoutAnimation(activity: Activity) {
        suppressTransition(activity, OVERRIDE_TRANSITION_OPEN)
    }

    fun finishWithoutAnimation(activity: Activity) {
        suppressTransition(activity, OVERRIDE_TRANSITION_CLOSE)
    }

    fun switchWithoutAnimation(
        activity: Activity,
        intent: Intent,
        finishCurrent: Boolean = false,
    ) {
        activity.startActivity(intent)
        startWithoutAnimation(activity)
        if (finishCurrent) {
            activity.finish()
            finishWithoutAnimation(activity)
        }
    }

    private fun suppressTransition(activity: Activity, transitionType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                Activity::class.java
                    .getMethod(
                        "overrideActivityTransition",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                    .invoke(activity, transitionType, 0, 0)
            }
        } else {
            runCatching {
                Activity::class.java
                    .getMethod(
                        "overridePendingTransition",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                    .invoke(activity, 0, 0)
            }
        }
    }
}
