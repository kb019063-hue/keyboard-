package tribixbite.cleverkeys

/**
 * What [CleverKeysService.setInputView] must do with the outermost view it hands the platform.
 *
 * `InputMethodService.setInputView` is two lines: empty the input frame, add the view. The
 * service calls it on every `onStartInputView` -- every time a text field takes focus -- with the
 * same view that is already sitting in that frame. So each focus change detached the whole
 * keyboard from its window and attached it again: every view lost its display list, everything
 * was measured, laid out and drawn from nothing, and all of it sat between the user's tap and the
 * keyboard appearing. The first branch below ends that: a view already in the current frame is
 * left exactly where it is.
 *
 * The second branch is a crash, not a cost. A configuration change (rotation, dark mode, font
 * size) makes `InputMethodService` discard its window content and inflate a NEW input frame,
 * while this service's views live on -- still children of the old frame. Upstream handled that by
 * removing the view from its parent before installing it. When the one-handed and floating
 * wrappers were added around the view, that removal kept being applied to the INNER view, and the
 * outermost one -- the one actually passed to the platform -- went into the new frame with its
 * old parent still set. `ViewGroup.addView` refuses a child that has a parent ("The specified
 * child already has a parent"), the exception leaves `onStartInputView`, and the keyboard's
 * process dies. The owner then waits for a cold start.
 *
 * Kept free of Android types so the rule can be tested: the caller passes the outermost view's
 * parent and the platform's current frame, and identity is all that matters.
 */
object InputViewInstall {
    enum class Action {
        /** Already the child of the current frame: touch nothing. */
        KEEP,

        /** No parent: hand it to the platform. */
        INSTALL,

        /** Child of something that is not the current frame: detach it, then hand it over. */
        MOVE
    }

    /**
     * @param parent the outermost view's current parent, or null when it has none
     * @param frame the platform's current input frame, or null when it cannot be found -- in
     *   which case an attached view is moved, which is what the code did before and is always
     *   safe
     */
    fun decide(parent: Any?, frame: Any?): Action = when {
        parent == null -> Action.INSTALL
        frame != null && parent === frame -> Action.KEEP
        else -> Action.MOVE
    }
}
