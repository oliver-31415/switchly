package at.saltyy.switchly.platform.receiver.logic

import at.saltyy.switchly.data.prefs.ScheduleStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothTriggerReceiverLogicTest {

    private fun schedule(
        id: Int,
        enabled: Boolean,
        profile: String,
        btDeviceName: String?
    ): ScheduleStore.Schedule {
        return ScheduleStore.Schedule(
            id = id,
            enabled = enabled,
            profile = profile,
            title = "t",
            note = "",
            type = ScheduleStore.Type.WEEKLY,
            daysMask = ScheduleStore.Days.MON,
            startMinutes = 0,
            endMinutes = 0,
            startDate = 0,
            endDate = 0,
            wifiSsid = null,
            btDeviceName = btDeviceName,
            action = ScheduleStore.Action.ENABLE
        )
    }

    @Test
    fun noDeviceNameReturnsNull() {
        val schedules = listOf(schedule(1, true, "p", "Car"))
        assertNull(
            BluetoothTriggerReceiverLogic.matchProfile(
                BluetoothTriggerReceiverLogic.Event.CONNECTED,
                null,
                schedules
            )
        )
        assertNull(
            BluetoothTriggerReceiverLogic.matchProfile(
                BluetoothTriggerReceiverLogic.Event.CONNECTED,
                "   ",
                schedules
            )
        )
    }

    @Test
    fun disconnectDoesNotDirectlyChangeProfile() {
        val schedules = listOf(schedule(1, true, "p", "Car"))
        assertNull(
            BluetoothTriggerReceiverLogic.matchProfile(
                BluetoothTriggerReceiverLogic.Event.DISCONNECTED,
                "Car",
                schedules
            )
        )
    }

    @Test
    fun matchesEnabledScheduleByBluetoothDeviceNameCaseInsensitive() {
        val schedules = listOf(
            schedule(1, true, "work", "Headset"),
            schedule(2, true, "home", "Car")
        )

        val match = BluetoothTriggerReceiverLogic.matchProfile(
            BluetoothTriggerReceiverLogic.Event.CONNECTED,
            "  car  ",
            schedules
        )
        assertNotNull(match)
        assertEquals(2, match!!.scheduleId)
        assertEquals("home", match.profile)
    }

    @Test
    fun hasActiveBluetoothSchedulesOnlyWhenEnabledAndNameSet() {
        val schedules = listOf(
            schedule(1, true, "p", null),
            schedule(2, false, "p", "Car")
        )
        assertFalse(BluetoothTriggerReceiverLogic.hasActiveBluetoothSchedules(schedules))

        val schedules2 = schedules + schedule(3, true, "p", "Car")
        assertTrue(BluetoothTriggerReceiverLogic.hasActiveBluetoothSchedules(schedules2))
    }
}
