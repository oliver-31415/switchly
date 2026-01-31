package at.saltyy.switchly.feature.picker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppEntryTest {

    @Test
    fun equalityAndHashCode_basedOnLabelAndPackage() {
        val a = AppEntry(label = "App", packageName = "com.example.app")
        val b = AppEntry(label = "App", packageName = "com.example.app")
        val c = AppEntry(label = "Other", packageName = "com.example.app")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }
}
