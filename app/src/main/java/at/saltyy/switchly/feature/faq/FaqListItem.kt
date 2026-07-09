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

package at.saltyy.switchly.feature.faq

sealed class FaqListItem {
    data class Folder(
        val id: String,
        val title: String,
        val subtitle: String,
        val articleCount: Int,
        val iconRes: Int? = null
    ) : FaqListItem()

    data class Header(val title: String) : FaqListItem()

    data class Item(
        val question: String,
        val answer: String,
        val iconRes: Int? = null
    ) : FaqListItem()
}
