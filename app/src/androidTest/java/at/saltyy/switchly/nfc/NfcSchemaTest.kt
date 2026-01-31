package at.saltyy.switchly.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NfcSchemaTest {

    @Test
    fun parseTempMinutes_parsesSimpleTempValue() {
        val result = NfcSchema.parseTempMinutes("temp10")
        assertEquals(10, result)
    }

    @Test
    fun parseTempMinutes_parsesMaxRangeTempValue() {
        val result = NfcSchema.parseTempMinutes("temp120")
        assertEquals(120, result)
    }

    @Test
    fun parseTempMinutes_returnsNullForNonTempAction() {
        val result = NfcSchema.parseTempMinutes("enable")
        assertNull(result)
    }

    @Test
    fun parseTempMinutes_returnsNullForMalformedTemp() {
        assertNull(NfcSchema.parseTempMinutes("temp_enable"))
        assertNull(NfcSchema.parseTempMinutes("temp_enable10"))
        assertNull(NfcSchema.parseTempMinutes("temp_disable"))
        assertNull(NfcSchema.parseTempMinutes("temp_disable10"))
        assertNull(NfcSchema.parseTempMinutes("tempXYZ"))
        assertNull(NfcSchema.parseTempMinutes("temp-10"))
    }
}
