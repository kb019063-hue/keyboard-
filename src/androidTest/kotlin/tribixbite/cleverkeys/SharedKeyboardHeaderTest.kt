package tribixbite.cleverkeys

import android.content.Context
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production header hierarchy and real dispatch, not only the height policy. */
@RunWith(AndroidJUnit4::class)
class SharedKeyboardHeaderTest {
    private lateinit var context: Context
    private lateinit var config: Config
    private lateinit var built: SuggestionBarPane.InitializationResult
    private lateinit var header: SharedKeyboardHeader
    private lateinit var keyboard: View
    private var oldKeys = ""
    private var oldEnabled = false
    private val rowHeight get() = (40 * context.resources.displayMetrics.density).toInt()
    private val width get() = (360 * context.resources.displayMetrics.density).toInt()

    @Before
    fun setup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = ContextThemeWrapper(instrumentation.targetContext, R.style.GboardLight)
        assertTrue(TestConfigHelper.ensureConfigInitialized(context))
        instrumentation.runOnMainSync {
            config = Config.globalConfig()
            oldKeys = config.toolbar_keys
            oldEnabled = config.toolbar_enabled
            config.toolbar_keys = ToolbarConfig.DEFAULT_KEYS_STRING
            config.toolbar_enabled = true
            built = SuggestionBarPane.initialize(context, Theme(context, null), 80, 30)
            header = built.topPane.parent as SharedKeyboardHeader
            built.toolbar.applyFromConfig(config)
            keyboard = View(context)
            built.inputViewContainer.addView(keyboard, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowHeight * 5
            ))
            built.suggestionBar.onOccupancyChanged = { header.setSuggestionState(it, rowHeight) }
            header.setSuggestionState(false, rowHeight)
            layout()
        }
    }

    @After
    fun teardown() {
        if (::config.isInitialized) {
            config.toolbar_keys = oldKeys
            config.toolbar_enabled = oldEnabled
        }
    }

    private fun layout() {
        // A second pass resolves measured end-button widths after changing compact mode.
        repeat(3) {
            built.inputViewContainer.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            built.inputViewContainer.layout(0, 0, width, built.inputViewContainer.measuredHeight)
        }
    }

    private fun tap(view: View) {
        var x = view.width / 2f
        var y = view.height / 2f
        var current = view
        while (current !== built.inputViewContainer) {
            x += current.left - current.scrollX
            y += current.top - current.scrollY
            current = current.parent as View
        }
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(time, time + 10, MotionEvent.ACTION_UP, x, y, 0)
        built.inputViewContainer.dispatchTouchEvent(down)
        built.inputViewContainer.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    @Test
    fun suggestionsShareOneRowAndEndButtonsReceiveTouches() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val top = keyboard.top
            assertEquals(rowHeight, top)
            val commands = mutableListOf<String>()
            var selected: String? = null
            built.toolbar.setCommandDispatcher { commands.add(it.actionValue) }
            built.suggestionBar.setOnSuggestionSelectedListener { selected = it }
            built.suggestionBar.setSuggestions(listOf("Are", "Arena", "Aren't"))
            layout()
            assertEquals(top, keyboard.top)
            assertEquals(rowHeight, header.height)
            val more = built.toolbar.findViewWithTag<View>("more_tools")!!
            val mic = built.toolbar.findViewWithTag<View>("voice_typing")!!
            val undo = built.toolbar.findViewWithTag<View>("undo")!!
            assertEquals(View.VISIBLE, more.visibility)
            assertEquals(View.GONE, undo.visibility)
            assertEquals(View.VISIBLE, mic.visibility)
            val params = built.scrollView.layoutParams as FrameLayout.LayoutParams
            assertEquals(more.width, params.marginStart)
            assertEquals(mic.width, params.marginEnd)
            tap(more)
            tap(mic)
            tap(built.suggestionBar.getChildAt(0))
            assertEquals(listOf("more_tools", "voice_typing"), commands)
            assertEquals("Are", selected)

            built.suggestionBar.setSuggestions(emptyList())
            layout()
            assertEquals(top, keyboard.top)
            assertEquals(View.VISIBLE, undo.visibility)
            assertEquals(0, built.topPane.height)
            built.suggestionBar.setSuggestions(listOf("me", "the"))
            layout()
            assertEquals(top, keyboard.top)
        }
    }

    @Test
    fun paneRestoresLatestSuggestionStateAndFullToolbar() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            built.suggestionBar.setSuggestions(listOf("word"))
            layout()
            val height = rowHeight * 3
            SuggestionBarPane.switchToContentPaneMode(built.topPane, built.contentPaneContainer, built.scrollView, height)
            layout()
            assertEquals(rowHeight + height, keyboard.top)
            assertEquals(View.VISIBLE, built.toolbar.findViewWithTag<View>("undo")!!.visibility)
            built.suggestionBar.setSuggestions(emptyList())
            SuggestionBarPane.switchToSuggestionBarMode(built.topPane, built.contentPaneContainer, built.scrollView, rowHeight)
            layout()
            assertEquals(rowHeight, keyboard.top)
            assertEquals(View.VISIBLE, built.toolbar.findViewWithTag<View>("undo")!!.visibility)
        }
    }

    @Test
    fun disabledAndSingleButtonToolbarsDoNotOverlapSuggestions() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            built.suggestionBar.setSuggestions(listOf("word"))
            config.toolbar_keys = "more_tools"
            built.toolbar.applyFromConfig(config)
            layout()
            val only = built.toolbar.findViewWithTag<View>("more_tools")!!
            val params = built.scrollView.layoutParams as FrameLayout.LayoutParams
            assertEquals(only.width, params.marginStart)
            assertEquals(0, params.marginEnd)
            assertTrue(built.scrollView.width > 0)
            config.toolbar_enabled = false
            built.toolbar.applyFromConfig(config)
            layout()
            assertEquals(View.GONE, built.toolbar.visibility)
            assertEquals(0, params.marginStart)
            assertEquals(0, params.marginEnd)
            assertEquals(width, built.scrollView.width)
            assertEquals(rowHeight, keyboard.top)
        }
    }

    @Test
    fun passwordControlsAndInlineAutofillOccupyHeader() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            built.suggestionBar.setPasswordMode(true)
            layout()
            assertTrue(built.suggestionBar.isOccupied())
            assertEquals(rowHeight, built.topPane.height)
            assertEquals(View.GONE, built.toolbar.findViewWithTag<View>("undo")!!.visibility)
            built.suggestionBar.setInlineAutofillView(View(context))
            layout()
            assertTrue(built.suggestionBar.isOccupied())
            assertEquals(rowHeight, keyboard.top)
        }
    }
}
