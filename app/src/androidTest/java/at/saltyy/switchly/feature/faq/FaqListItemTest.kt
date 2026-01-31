package at.saltyy.switchly.feature.faq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FaqListItemTest {

    @Test
    fun header_equalityDependsOnTitle() {
        val h1 = FaqListItem.Header("Title")
        val h2 = FaqListItem.Header("Title")
        val h3 = FaqListItem.Header("Other")

        assertEquals(h1, h2)
        assertEquals(h1.hashCode(), h2.hashCode())
        assertNotEquals(h1, h3)
    }

    @Test
    fun item_equalityDependsOnAllFields() {
        val i1 = FaqListItem.Item(
            question = "Q1",
            answer = "A1",
            iconRes = 1
        )
        val i2 = FaqListItem.Item(
            question = "Q1",
            answer = "A1",
            iconRes = 1
        )
        val i3 = FaqListItem.Item(
            question = "Q2",
            answer = "A1",
            iconRes = 1
        )

        assertEquals(i1, i2)
        assertEquals(i1.hashCode(), i2.hashCode())
        assertNotEquals(i1, i3)
    }
}
