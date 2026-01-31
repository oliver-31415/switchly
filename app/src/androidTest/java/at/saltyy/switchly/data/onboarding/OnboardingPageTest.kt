package at.saltyy.switchly.data.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OnboardingPageTest {

    @Test
    fun equalsAndHashCode_matchForSameValues() {
        val first = OnboardingPage(
            title = "Welcome",
            desc = "Description",
            iconRes = 123,
            actionLabel = "Go"
        )
        val second = OnboardingPage(
            title = "Welcome",
            desc = "Description",
            iconRes = 123,
            actionLabel = "Go"
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun equalsAndHashCode_differForDifferentValues() {
        val first = OnboardingPage(
            title = "Welcome",
            desc = "Description"
        )
        val second = OnboardingPage(
            title = "Other",
            desc = "Description"
        )

        assertNotEquals(first, second)
        assertNotEquals(first.hashCode(), second.hashCode())
    }
}
