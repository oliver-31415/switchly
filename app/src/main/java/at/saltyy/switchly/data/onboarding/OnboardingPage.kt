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

package at.saltyy.switchly.data.onboarding

import android.app.Activity
import android.content.Context

data class OnboardingPage(
    val type: Type = Type.STANDARD,

    val title: String,
    val desc: String,
    val iconRes: Int? = null,
    val level: Level = Level.INFO,
    val actionLabel: String? = null,
    val action: ((Activity) -> Unit)? = null,

    /** Optional rows rendered as small cards below the description. */
    val detailRows: List<String> = emptyList(),

    /** Optional completion check (e.g. a permission) used to show a "Granted" state and gate required steps. */
    val completionCheck: ((Context) -> Boolean)? = null,

    /** Optional label used when [completionCheck] returns true. Defaults to "Granted". */
    val completedLabel: String? = null,

    /** Keep the action button enabled even after [completionCheck] is true. Useful for setup steps users may want to adjust again. */
    val keepActionEnabledWhenCompleted: Boolean = false,

    /** Optional message shown when the user tries to continue but the required step is incomplete. */
    val requiredMessage: String? = null
) {
    enum class Type {
        STANDARD, PERMISSION_OVERVIEW, REVIEW
    }
    enum class Level {
        REQUIRED, RECOMMENDED, OPTIONAL, INFO
    }
}
