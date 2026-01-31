package at.saltyy.switchly.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NfcSchemaUriTest {

    @Test
    fun parseTempMinutes_parsesValidValues() {
        assertEquals(10, NfcSchema.parseTempMinutes("temp10"))
        assertEquals(120, NfcSchema.parseTempMinutes("temp120"))
    }

    @Test
    fun parseTempMinutes_returnsNullForInvalidOrNonTemp() {
        assertNull(NfcSchema.parseTempMinutes("enable"))
        assertNull(NfcSchema.parseTempMinutes("temp_enable"))
        assertNull(NfcSchema.parseTempMinutes("temp_enable10"))
        assertNull(NfcSchema.parseTempMinutes("temp_disable"))
        assertNull(NfcSchema.parseTempMinutes("temp_disable10"))
        assertNull(NfcSchema.parseTempMinutes("tempXYZ"))
        assertNull(NfcSchema.parseTempMinutes("temp-10"))
    }

    @Test
    fun tempMinutesOrNull_onGlobalCommand_usesParseTempMinutes() {
        val tempCommand = NfcSchema.GlobalCommand("temp45")
        val enableCommand = NfcSchema.GlobalCommand(NfcSchema.ACTION_ENABLE)

        assertEquals(45, tempCommand.tempMinutesOrNull())
        assertNull(enableCommand.tempMinutesOrNull())
    }

    @Test
    fun tempMinutesOrNull_onProfileCommand_usesParseTempMinutes() {
        val tempCommand = NfcSchema.ProfileCommand("Work", "temp90")
        val nonTemp = NfcSchema.ProfileCommand("Work", NfcSchema.ACTION_ENABLE)

        assertEquals(90, tempCommand.tempMinutesOrNull())
        assertNull(nonTemp.tempMinutesOrNull())
    }
}
