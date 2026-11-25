package tribixbite.cleverkeys

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import android.widget.GridView

/**
 * A two-column [GridView] that measures all of its rows, so it can live inside the clipboard
 * pane's ScrollView without taking over scrolling itself. Mirrors [NonScrollListView], which
 * the clipboard used before the Gboard-style card grid.
 *
 * The adapter still has one position per clip — GridView lays positions out in row-major
 * order — so edit, swipe, selection and pagination keep their existing indices.
 */
open class NonScrollGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GridView(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMeasureSpecCustom = MeasureSpec.makeMeasureSpec(
            Int.MAX_VALUE shr 2, MeasureSpec.AT_MOST
        )
        super.onMeasure(widthMeasureSpec, heightMeasureSpecCustom)
        layoutParams.height = measuredHeight
    }
}
