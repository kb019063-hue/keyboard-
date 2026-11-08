package tribixbite.cleverkeys

import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardToolbarBackgroundTest {
    @Test
    fun toolbarUsesTheSameFieldAsTheGboardStrip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (style in listOf(R.style.GboardLight, R.style.GboardDark)) {
                val context = ContextThemeWrapper(instrumentation.targetContext, style)
                val theme = Theme(context, null)
                val toolbar = KeyboardToolbar(context, theme)
                toolbar.setOpacity(80)
                assertEquals(theme.colorKeyboardBackground, (toolbar.background as ColorDrawable).color)
            }
        }
    }

    @Test
    fun ordinaryThemePreservesRgbAndMultipliesAlpha() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Dark)
            val theme = Theme(context, null)
            val toolbar = KeyboardToolbar(context, theme)
            toolbar.setOpacity(80)
            val bg = theme.suggestionBackgroundColor
            val expected = (bg and 0x00FFFFFF) or (KeyFillAlpha.of(bg, 80 * 255 / 100) shl 24)
            assertEquals(expected, (toolbar.background as ColorDrawable).color)
        }
    }
}
