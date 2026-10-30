package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `Pointers.Modifiers` value semantics (bug hunt 2026-10-30).
 *
 * The backing array is normally LONGER than `size()`: `Pointers.getModifiers()` allocates one
 * slot per active pointer and `Modifiers.ofArray` compacts the non-null modifiers into a prefix
 * without shrinking the array. `equals`/`hashCode` used to cover the whole array, dead tail
 * included, so two Modifiers holding the same modifiers disagreed whenever they came from
 * different pointer counts. `KeyModifier` memoizes on this type and its KDoc promises content
 * equality, so the tail both broke memo hits and split one logical modifier set across several
 * map entries.
 *
 * `ctrl` is used rather than `shift` because `getKeyByName` for a modifier is safe in plain JVM
 * tests (same rationale as `KeyModifierMemoTest`).
 */
class ModifiersValueSemanticsTest {

    private val ctrl = KeyValue.getKeyByName("ctrl")

    @Test
    fun `dead array tail does not affect equality`() {
        // Same single modifier, backed by a one-slot array and by a three-slot array whose tail
        // is null (what getModifiers() produces with three pointers down and one modifier).
        val tight = Pointers.Modifiers.ofArray(arrayOf<KeyValue?>(ctrl), 1)
        val loose = Pointers.Modifiers.ofArray(arrayOf<KeyValue?>(ctrl, null, null), 1)

        assertThat(loose).isEqualTo(tight)
        assertThat(loose.hashCode()).isEqualTo(tight.hashCode())
    }

    @Test
    fun `an empty modifier set from a live pointer equals EMPTY`() {
        // One finger down, no modifier latched: getModifiers() allocates one null slot.
        val fromPointer = Pointers.Modifiers.ofArray(arrayOf<KeyValue?>(null), 0)

        assertThat(fromPointer).isEqualTo(Pointers.Modifiers.EMPTY)
        assertThat(fromPointer.hashCode()).isEqualTo(Pointers.Modifiers.EMPTY.hashCode())
    }

    @Test
    fun `different modifiers stay unequal`() {
        val onlyCtrl = Pointers.Modifiers.ofArray(arrayOf<KeyValue?>(ctrl), 1)

        assertThat(onlyCtrl).isNotEqualTo(Pointers.Modifiers.EMPTY)
        assertThat(Pointers.Modifiers.EMPTY).isNotEqualTo(onlyCtrl)
    }
}
