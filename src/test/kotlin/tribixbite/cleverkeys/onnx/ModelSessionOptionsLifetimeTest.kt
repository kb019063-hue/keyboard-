package tribixbite.cleverkeys.onnx

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Pins the **lifetime** of `OrtSession.SessionOptions` relative to the sessions built from it.
 *
 * ORT's own contract (`ai.onnxruntime.OrtSession.SessionOptions`, both the JavaDoc and the
 * `OrtSession.java` source) is one-directional:
 *
 * > The SessionOptions object must not be closed until all sessions which use it are closed,
 * > as otherwise it could release resources that are in use.
 *
 * and the handle is real native memory: `SessionOptions()` calls the native `createOptions`,
 * and only `close()` (→ `closeOptions`) reclaims it. `createSession` copies the options into
 * the session, so nothing else will ever free that handle.
 *
 * Two defects this class exists to keep fixed:
 *
 *  1. `loadModel` created options per load and closed them nowhere — a native handle leaked per
 *     model load, and the adapter (and therefore the load path) is rebuilt on every IME
 *     teardown rather than only at process death.
 *  2. The obvious one-line fix (close the options immediately after `createSession`) VIOLATES
 *     the contract above. The options therefore have to travel to whoever owns the session, and
 *     the close order has to be session-then-options.
 *
 * This is a source-scan test for the same reason as [ModelLoaderOrtCacheTest]: the real
 * behaviour needs `onnxruntime-android`, whose native library JVM unit tests cannot load, and
 * the properties that matter are *ordering* facts about the teardown path rather than values.
 */
class ModelSessionOptionsLifetimeTest {

    private val loaderSource = File("src/main/kotlin/tribixbite/cleverkeys/onnx/ModelLoader.kt")
    private val emissionSource =
        File("src/main/kotlin/tribixbite/cleverkeys/swipe/OnnxCtcEmissionModel.kt")

    private fun lines(file: File): List<String> {
        assertWithMessage(
            "${file.name} not found at ${file.absolutePath} — run from the project root " +
                "(source-scan convention of DeadPlumbingDriftTest)."
        ).that(file.isFile).isTrue()
        return file.readLines()
    }

    private fun isCommentOnly(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }

    /** `line` with any trailing `//` comment removed (these functions have no `//` in strings). */
    private fun code(line: String): String = line.substringBefore("//")

    /**
     * The lines of the function/KClass body starting at [startIndex], found by brace depth.
     *
     * `takeWhile { it.trim() != "}" }` is wrong here: both functions under test contain nested
     * `try`/`catch` blocks, so the first line that is just `}` closes an inner block, not the
     * declaration — and the assertions would then silently look at a truncated body.
     */
    private fun bodyOf(source: List<String>, startIndex: Int): List<String> {
        val body = mutableListOf<String>()
        var depth = 0
        var opened = false
        for (i in startIndex until source.size) {
            val text = source[i]
            body.add(text)
            for (ch in code(text)) {
                when {
                    ch == '{' -> { depth++; opened = true }
                    ch == '}' -> depth--
                }
            }
            if (opened && depth <= 0) break
        }
        assertWithMessage(
            "Could not find the body of the declaration at line ${startIndex + 1}; the source " +
                "scan is stale — update this test rather than deleting it."
        ).that(opened).isTrue()
        return body
    }

    /** Index of the body whose parameter list contains [paramNeedle], found from its `fun` line. */
    private fun functionStart(source: List<String>, paramNeedle: String): Int {
        val paramAt = source.indexOfFirst { !isCommentOnly(it) && it.contains(paramNeedle) }
        assertWithMessage("no declaration taking `$paramNeedle` was found.").that(paramAt).isAtLeast(0)
        for (i in paramAt downTo 0) {
            if (source[i].contains("fun ")) return i
        }
        return -1
    }

    private fun indexOfCode(body: List<String>, needle: String): Int =
        body.indexOfFirst { !isCommentOnly(it) && it.contains(needle) }

    // ------------------------------------------------------- the handle is carried, not dropped

    @Test
    fun `LoadResult carries the session options out of loadModel`() {
        val source = lines(loaderSource)
        val bytesLoadAt = functionStart(source, "modelData: ByteArray")
        assertWithMessage("the bytes-based loadModel overload was not found.").that(bytesLoadAt).isAtLeast(0)
        val body = bodyOf(source, bytesLoadAt)

        assertWithMessage(
            "loadModel must hand its OrtSession.SessionOptions to the caller via " +
                "LoadResult.sessionOptions. Dropping it (the pre-2026-11-15 behaviour) leaks " +
                "the native createOptions handle on every model load, and closing it inline " +
                "before createSession returns is forbidden by ORT. Ownership must move to " +
                "whoever owns the session."
        ).that(indexOfCode(body, "sessionOptions = sessionOptions")).isAtLeast(0)
    }

    @Test
    fun `loadModel closes the options when loading fails`() {
        val source = lines(loaderSource)
        val body = bodyOf(source, functionStart(source, "modelData: ByteArray"))

        val catchAt = indexOfCode(body, "} catch (e: Exception)")
        assertWithMessage("loadModel's catch block not found — has the file been restructured?")
            .that(catchAt).isAtLeast(0)

        val rethrowAt = body.drop(catchAt).indexOfFirst { it.trim().startsWith("throw RuntimeException") }
        assertWithMessage("loadModel's catch no longer rethrows; update this test.")
            .that(rethrowAt).isAtLeast(0)

        val closesOnFailure = body.subList(catchAt, catchAt + rethrowAt)
            .any { !isCommentOnly(it) && it.contains("sessionOptions.close()") }
        assertWithMessage(
            "Every failure between createOptimizedSessionOptions() and a successful " +
                "createSession (execution-provider attachment above all) abandons the native " +
                "options handle, because on that path no caller ever receives a LoadResult. " +
                "The catch must close it before rethrowing."
        ).that(closesOnFailure).isTrue()
    }

    // --------------------------------------------------------------- the close ORDER is right

    @Test
    fun `closeSession closes the session before the options`() {
        val source = lines(loaderSource)
        val start = functionStart(source, "result: LoadResult")
        assertWithMessage(
            "ModelLoader.closeSession(result) is missing — see its KDoc for why the options " +
                "cannot simply be closed inline in loadModel."
        ).that(source[start].contains("closeSession")).isTrue()

        val body = bodyOf(source, start)
        val sessionClose = indexOfCode(body, "result.session.close()")
        val optionsClose = indexOfCode(body, "result.sessionOptions?.close()")
        assertWithMessage("closeSession must close the session; found no `result.session.close()`.")
            .that(sessionClose).isAtLeast(0)
        assertWithMessage("closeSession must close the options; found no `result.sessionOptions?.close()`.")
            .that(optionsClose).isAtLeast(0)
        assertWithMessage(
            "ORT: SessionOptions \"must not be closed until all sessions which use it are " +
                "closed, as otherwise it could release resources that are in use\". Closing the " +
                "options before the session is a native use-after-free, not a style question."
        ).that(optionsClose).isGreaterThan(sessionClose)
    }

    @Test
    fun `emission model releases the session before the options`() {
        val source = lines(emissionSource)
        val start = source.indexOfFirst { !isCommentOnly(it) && it.trim() == "fun close() {" }
        assertWithMessage("OnnxCtcEmissionModel.close() is missing.").that(start).isAtLeast(0)

        val body = bodyOf(source, start)
        val sessionClose = indexOfCode(body, "session.close()")
        val optionsClose = indexOfCode(body, "sessionOptions?.close()")
        assertWithMessage("the emission model must close its session.").that(sessionClose).isAtLeast(0)
        assertWithMessage(
            "The emission model owns the session, so it must also own the options it was built " +
                "from and release them second; found no `sessionOptions?.close()`."
        ).that(optionsClose).isAtLeast(0)
        assertWithMessage(
            "Releasing the options before the session breaks ORT's stated contract (see class doc)."
        ).that(optionsClose).isGreaterThan(sessionClose)
    }

    @Test
    fun `emission model accepts the options it is expected to release`() {
        val declared = lines(emissionSource).any {
            !isCommentOnly(it) && it.contains("sessionOptions: OrtSession.SessionOptions?")
        }
        assertWithMessage(
            "OnnxCtcEmissionModel must take the SessionOptions it is bound to; without the " +
                "parameter there is no owner to close the handle and loadModel's leak returns."
        ).that(declared).isTrue()
    }

    // ------------------------------------------------- no inline close of a live handle

    @Test
    fun `loadModel never closes options before the session is built`() {
        val source = lines(loaderSource)
        val body = bodyOf(source, functionStart(source, "modelData: ByteArray"))

        val createAt = indexOfCode(body, "createSession(")
        assertWithMessage("loadModel no longer calls createSession; update this test.")
            .that(createAt).isAtLeast(0)

        val inlineClose = body.subList(0, createAt)
            .any { !isCommentOnly(it) && it.contains("sessionOptions.close()") }
        assertWithMessage(
            "loadModel must not close the options before ORT has built the session: createSession " +
                "copies them, but the handle must stay alive across the call."
        ).that(inlineClose).isFalse()
    }
}
