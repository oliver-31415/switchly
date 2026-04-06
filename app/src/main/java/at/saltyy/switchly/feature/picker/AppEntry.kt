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

package at.saltyy.switchly.feature.picker

import at.saltyy.switchly.util.AppBlockSafety
import java.util.Locale

data class AppEntry(
    val packageName: String,
    val label: String,
    val isAvailable: Boolean = true,
    val blockSafety: AppBlockSafety.Info = AppBlockSafety.Info()
) {
    val pkgLower: String = packageName.lowercase(Locale.getDefault())
    val labelLower: String = label.lowercase(Locale.getDefault())
}