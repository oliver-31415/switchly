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

package at.saltyy.switchly.platform.receiver.logic

import at.saltyy.switchly.data.prefs.ScheduleStore

/**
 * Pure Bluetooth trigger evaluation logic (no Android dependencies).
 * In Switchly, Bluetooth triggers are part of schedules. 
 * Device address is preferred when available; name remains the fallback.
 */
object BluetoothTriggerReceiverLogic {

    enum class Event { CONNECTED, DISCONNECTED }

    data class Match(
        val scheduleId: Int,
        val profile: String
    )

    /**
     * Current behavior:
     * - On CONNECTED: match the Bluetooth address first when a schedule stores one.
     * - Fall back to the device name for older schedules and devices where Android does not expose the address/name consistently.
     * - On DISCONNECTED: no direct profile change (return null). The schedule engine may still re-evaluate via tick.
     */
    fun matchProfile(
        event: Event,
        deviceName: String?,
        deviceAddress: String?,
        schedules: List<ScheduleStore.Schedule>
    ): Match? {
        if (event != Event.CONNECTED) return null

        val name = deviceName?.trim().orEmpty()
        val address = normalizeAddress(deviceAddress)
        if (name.isEmpty() && address.isEmpty()) return null

        val s = schedules
            .asSequence()
            .filter { it.enabled }
            .firstOrNull { schedule ->
                val configuredAddress = normalizeAddress(schedule.btDeviceAddress)
                val configuredName = schedule.btDeviceName?.trim().orEmpty()
                val configuredNameAsAddress = normalizeAddress(configuredName)

                when {
                    configuredAddress.isNotEmpty() && address.isNotEmpty() -> configuredAddress == address
                    configuredNameAsAddress.isNotEmpty() && address.isNotEmpty() -> configuredNameAsAddress == address
                    configuredName.isNotEmpty() && name.isNotEmpty() -> configuredName.equals(name, ignoreCase = true)
                    else -> false
                }
            }
            ?: return null

        return Match(scheduleId = s.id, profile = s.profile)
    }

    fun hasActiveBluetoothSchedules(schedules: List<ScheduleStore.Schedule>): Boolean =
        schedules.any { it.enabled && (!it.btDeviceName.isNullOrBlank() || !it.btDeviceAddress.isNullOrBlank()) }

    private fun normalizeAddress(value: String?): String =
        value
            ?.trim()
            ?.takeIf { it.matches(Regex("""(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}""")) }
            ?.uppercase()
            .orEmpty()
}
