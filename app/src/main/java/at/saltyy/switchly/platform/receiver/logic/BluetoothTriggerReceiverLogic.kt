package at.saltyy.switchly.platform.receiver.logic

import at.saltyy.switchly.data.prefs.ScheduleStore

/**
 * Pure Bluetooth trigger evaluation logic (no Android dependencies).
 *
 * In Switchly, Bluetooth triggers are currently part of schedules (ScheduleStore.Schedule.btDeviceName).
 */
object BluetoothTriggerReceiverLogic {

    enum class Event { CONNECTED, DISCONNECTED }

    data class Match(
        val scheduleId: Int,
        val profile: String
    )

    /**
     * Current behavior:
     * - On CONNECTED: if any enabled schedule requires this Bluetooth device name (case-insensitive),
     *   return a match (the first one in list order).
     * - On DISCONNECTED: no direct profile change (return null). The schedule engine may still
     *   re-evaluate via tick.
     */
    fun matchProfile(
        event: Event,
        deviceName: String?,
        schedules: List<ScheduleStore.Schedule>
    ): Match? {
        if (event != Event.CONNECTED) return null

        val name = deviceName?.trim().orEmpty()
        if (name.isEmpty()) return null

        val s = schedules
            .asSequence()
            .filter { it.enabled }
            .firstOrNull { it.btDeviceName?.trim()?.equals(name, ignoreCase = true) == true }
            ?: return null

        return Match(scheduleId = s.id, profile = s.profile)
    }

    fun hasActiveBluetoothSchedules(schedules: List<ScheduleStore.Schedule>): Boolean =
        schedules.any { it.enabled && !it.btDeviceName.isNullOrBlank() }
}
