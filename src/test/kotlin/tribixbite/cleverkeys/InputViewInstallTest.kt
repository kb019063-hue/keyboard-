package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rule for handing the keyboard's outermost view to the platform ([InputViewInstall]), and
 * the service really using it.
 *
 * Two defects sat in the three lines this replaces. On every focused text field the whole
 * keyboard was detached from its window and attached again, between the user's tap and the
 * keyboard appearing. And after a configuration change (rotation, dark mode, font size) the
 * outermost view was added to the platform's NEW input frame while still a child of the old one,
 * which `ViewGroup.addView` answers with an exception -- the keyboard's process died. The
 * emulator smoke test's section 10 is the on-device witness for the second; this file is the
 * witness that the decision itself cannot drift.
 */
class InputViewInstallTest {

    private val frame = Any()
    private val staleFrame = Any()

    @Test
    fun aViewWithNoParent_isInstalled() {
        assertEquals(InputViewInstall.Action.INSTALL, InputViewInstall.decide(parent = null, frame = frame))
        assertEquals(InputViewInstall.Action.INSTALL, InputViewInstall.decide(parent = null, frame = null))
    }

    @Test
    fun aViewAlreadyInTheCurrentFrame_isLeftAlone() {
        // The case every focused text field hits: nothing may be detached.
        assertEquals(InputViewInstall.Action.KEEP, InputViewInstall.decide(parent = frame, frame = frame))
    }

    @Test
    fun aViewStillInAFrameThePlatformReplaced_isMovedNotAdded() {
        // After a configuration change the platform has a new frame; the view's parent is the
        // old one. Adding without detaching is the crash.
        assertEquals(InputViewInstall.Action.MOVE, InputViewInstall.decide(parent = staleFrame, frame = frame))
    }

    @Test
    fun whenTheCurrentFrameIsUnknown_anAttachedViewIsMoved() {
        // Never KEEP on a guess: without the frame there is no proof the parent is current.
        assertEquals(InputViewInstall.Action.MOVE, InputViewInstall.decide(parent = staleFrame, frame = null))
    }

    @Test
    fun identityDecides_notEquality() {
        // Two distinct frames that happen to be equal are still two frames.
        val a = listOf(1)
        val b = listOf(1)
        assertEquals(a, b)
        assertEquals(InputViewInstall.Action.MOVE, InputViewInstall.decide(parent = a, frame = b))
    }

    private fun setInputViewBody(): String {
        val f = File("src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt")
        assertTrue("run from the project root: ${f.absolutePath}", f.isFile)
        val src = f.readText()
        val start = src.indexOf("override fun setInputView(v: View)")
        assertTrue("CleverKeysService.setInputView not found", start >= 0)
        val end = src.indexOf("\n    }\n", start)
        assertTrue(end > start)
        return src.substring(start, end)
    }

    @Test
    fun theService_asksTheRuleBeforeTouchingThePlatform() {
        val body = setInputViewBody()
        val decide = body.indexOf("InputViewInstall.decide(floated.parent")
        assertTrue("setInputView must decide on the OUTERMOST view's parent", decide >= 0)
        val firstSuper = body.indexOf("super.setInputView(")
        assertTrue("setInputView must still be able to install", firstSuper >= 0)
        assertTrue("the decision must come before any install", decide < firstSuper)
    }

    @Test
    fun theService_installsOnlyInsideTheTwoBranchesThatNeedIt() {
        val body = setInputViewBody()
        val installs = Regex("""super\.setInputView\(""").findAll(body).count()
        assertEquals("one install for INSTALL, one for MOVE, none unconditional", 2, installs)
        val keep = body.indexOf("InputViewInstall.Action.KEEP -> Unit")
        assertTrue("KEEP must do nothing at all", keep >= 0)
    }

    @Test
    fun theService_detachesTheOutermostViewBeforeMovingIt() {
        val body = setInputViewBody()
        val move = body.indexOf("InputViewInstall.Action.MOVE ->")
        assertTrue(move >= 0)
        val detach = body.indexOf("(floated.parent as? ViewGroup)?.removeView(floated)", move)
        val install = body.indexOf("super.setInputView(floated)", move)
        assertTrue("MOVE must detach the outermost view", detach >= 0)
        assertTrue("...and only then install it", install > detach)
    }

    @Test
    fun theService_noLongerPullsTheHostedViewOutOfItsWrapperOnEveryCall() {
        // This was the line that defeated OneHandedRoot.host()'s idempotence.
        assertFalse(setInputViewBody().contains("parent.removeView(shown)"))
    }
}
