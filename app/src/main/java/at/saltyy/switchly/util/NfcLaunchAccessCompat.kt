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
import android.nfc.NfcAdapter
import android.os.Build

object NfcLaunchAccessCompat {
    enum class State {
        ALLOWED,
        NOT_ALLOWED,
        UNKNOWN
    }

    fun state(context: Context): State {
        val adapter = runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull() ?: return State.UNKNOWN
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            return State.NOT_ALLOWED
        }
        if (Build.VERSION.SDK_INT >= 36) {
            return runCatching {
                if (adapter.isTagIntentAllowed) {
                    State.ALLOWED
                } else {
                    State.NOT_ALLOWED
                }
            }.getOrDefault(State.UNKNOWN)
        }
        return readTagIntentPreference(context, adapter) ?: State.ALLOWED
    }

    fun isLikelyAllowed(context: Context): Boolean = state(context) == State.ALLOWED

    private fun readTagIntentPreference(context: Context, adapter: NfcAdapter): State? {
        val packageName = context.packageName
        runCatching {
            val supported = adapter.javaClass.methods
                .firstOrNull { method -> method.name == "isTagIntentAppPreferenceSupported" && method.parameterTypes.isEmpty() }
                ?.invoke(adapter) as? Boolean
            if (supported == false) {
                return null
            }
        }

        return runCatching {
            val userId = userId()
            val method = adapter.javaClass.methods.firstOrNull { candidate ->
                candidate.name == "getTagIntentAppPreferenceForUser" &&
                    candidate.parameterTypes.size == 1 &&
                    candidate.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: return@runCatching null
            val map = method.invoke(adapter, userId) as? Map<*, *> ?: return@runCatching null
            val allowed = map[packageName] as? Boolean ?: return@runCatching null
            if (allowed) State.ALLOWED else State.NOT_ALLOWED
        }.getOrNull()
    }

    private fun userId(): Int {
        return runCatching {
            val userHandle = Class.forName("android.os.UserHandle")
            val method = userHandle.getDeclaredMethod("myUserId")
            method.isAccessible = true
            method.invoke(null) as? Int
        }.getOrNull() ?: 0
    }
}
