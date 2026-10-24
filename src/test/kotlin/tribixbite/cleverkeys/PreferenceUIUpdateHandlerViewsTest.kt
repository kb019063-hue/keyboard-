package tribixbite.cleverkeys

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * Preference-driven UI updates reach the views ON SCREEN, not the ones that existed when the
 * handler was created. A theme change — now also a switch onto a layout bound to another theme
 * — replaces the keyboard view, the suggestion bar and the toolbar; the handler used to keep the
 * originals, so a later opacity change went to a detached bar (2026-10-17).
 *
 * The toolbar is covered by the same invariant on purpose: it was added later (HeliBoard gap
 * analysis phase 1) and reads its config on every preference change, so a captured reference
 * would silently stop honouring the enable toggle and any reordering after the first theme
 * change.
 *
 * The one-handed root (gap analysis phase 2) is covered too, and for a subtler reason: unlike
 * every other view here it is created ONCE and never rebuilt, so capturing it would appear to
 * work — right up to the point where `setInputView` is called with new content and the handler
 * applies state to a root that is no longer the window's. The provider keeps it honest even
 * though today the answer is always the same instance.
 */
class PreferenceUIUpdateHandlerViewsTest {

    @Test
    fun opacityAndLayoutGoToTheCurrentViewsAfterTheyAreReplaced() {
        val config = ObjenesisStd().newInstance(Config::class.java)
        config.suggestion_bar_opacity = 42
        val layout = ObjenesisStd().newInstance(KeyboardData::class.java)
        val bridge = mockk<LayoutBridge> { every { getCurrentLayout() } returns layout }
        val firstBar = mockk<SuggestionBar>(relaxed = true)
        val firstView = mockk<Keyboard2View>(relaxed = true)
        val firstToolbar = mockk<KeyboardToolbar>(relaxed = true)
        val firstOneHanded = mockk<OneHandedRoot>(relaxed = true)
        var bar = firstBar
        var view = firstView
        var toolbar = firstToolbar
        var oneHanded = firstOneHanded
        val handler = PreferenceUIUpdateHandler.create(
            config, bridge, { view }, { bar }, { toolbar }, { oneHanded }
        )

        // The theme changes: all four views are rebuilt.
        bar = mockk(relaxed = true)
        view = mockk(relaxed = true)
        toolbar = mockk(relaxed = true)
        oneHanded = mockk(relaxed = true)
        handler.handlePreferenceChange("suggestion_bar_opacity")

        verify { bar.setOpacity(42) }
        verify { view.setKeyboard(layout) }
        verify { toolbar.applyFromConfig(config) }
        verify { oneHanded.applyFromConfig(config) }
        verify(exactly = 0) { firstBar.setOpacity(any()) }
        verify(exactly = 0) { firstView.setKeyboard(any()) }
        verify(exactly = 0) { firstToolbar.applyFromConfig(any()) }
        verify(exactly = 0) { firstOneHanded.applyFromConfig(any()) }
    }
}
