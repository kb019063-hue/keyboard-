package tribixbite.cleverkeys

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two contracts [IncognitoPolicy] exists for: incognito suspends whatever the user had
 * on, and can never enable what the user had off. The whole truth table is four rows, so assert
 * all four rather than a couple of representative ones -- the interesting row is the last, where
 * a wrong implementation (say `userPreference != incognito`) would still pass the first three.
 */
class IncognitoPolicyTest {
    @Test
    fun `feature on and incognito off acts`() {
        assertTrue(IncognitoPolicy.allows(userPreference = true, incognito = false))
    }

    @Test
    fun `feature on and incognito on is suspended`() {
        assertFalse(IncognitoPolicy.allows(userPreference = true, incognito = true))
    }

    @Test
    fun `feature off and incognito off stays off`() {
        assertFalse(IncognitoPolicy.allows(userPreference = false, incognito = false))
    }

    @Test
    fun `incognito never enables a feature the user had off`() {
        assertFalse(IncognitoPolicy.allows(userPreference = false, incognito = true))
    }
}
