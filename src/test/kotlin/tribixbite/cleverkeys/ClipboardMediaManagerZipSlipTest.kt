package tribixbite.cleverkeys

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Path-traversal (zip-slip) hardening for [ClipboardMediaManager], and the destructive half of the
 * same story: [ClipboardMediaManager.deleteMedia].
 *
 * A clipboard/full backup ZIP is untrusted input: `entry.name` becomes `media_path` verbatim, and
 * importers stage members with `File(filesDir, entry.name)`. Before this hardening a crafted name
 * like `clipboard_media/../../databases/clipboard.db` resolved OUTSIDE filesDir and could clobber
 * app-private files. `getMediaFile` now canonicalizes and rejects any target that escapes
 * `clipboard_media/` with a [SecurityException].
 *
 * `deleteMedia` had no such check, and it is the more dangerous reader: it is called on every row
 * removal, on `clearHistory()` and on both storage-limit prunes, with the value the database holds.
 * It now routes through the same containment check and refuses anything that is not a regular file
 * — see the `deleteMedia_*` cases, which also pin that Java's `File.delete()` deletes an EMPTY
 * directory without complaining.
 *
 * Both methods read only `context.filesDir`, so a MockK Context backed by a real temp directory
 * exercises the real canonicalization and the real filesystem — this runs under the on-device
 * `runMockTests` task (no Robolectric needed).
 */
class ClipboardMediaManagerZipSlipTest {

    private lateinit var baseDir: File
    private lateinit var manager: ClipboardMediaManager

    @Before
    fun setup() {
        baseDir = Files.createTempDirectory("ck-zipslip").toFile()
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns baseDir
        manager = ClipboardMediaManager(context)
    }

    @Test
    fun getMediaFile_rejectsParentTraversalIntoDatabases() {
        val evil = "clipboard_media/../../databases/clipboard.db"
        try {
            manager.getMediaFile(evil)
            fail("Expected SecurityException for traversal path: $evil")
        } catch (e: SecurityException) {
            assertTrue(
                "message should name the offending escape",
                e.message?.contains("escapes") == true ||
                    e.message?.contains("clipboard.db") == true
            )
        }
    }

    @Test
    fun getMediaFile_rejectsSingleLevelEscape() {
        val evil = "clipboard_media/../evil.txt"
        try {
            manager.getMediaFile(evil)
            fail("Expected SecurityException for traversal path: $evil")
        } catch (e: SecurityException) {
            assertTrue(
                "message should reference escape",
                e.message?.contains("escapes") == true
            )
        }
    }

    /**
     * The SAME stored string reaches `deleteMedia`: `media_path` is written verbatim from a
     * backup ZIP (`ClipboardDatabase.importHistoryEntries`) and every row deletion,
     * `clearHistory()` and each storage-limit prune passes it back here. `deleteMedia` used to
     * resolve it with a bare `File(filesDir, mediaPath)`, so a crafted backup row could delete
     * anything the app could reach once that entry left the list.
     *
     * Both shapes were modelled against real directories before these cases were written:
     * `<filesDir>/databases/clipboard.db` is a plain filesDir-relative path that needs no `..` at
     * all.
     */
    @Test
    fun deleteMedia_refusesAPlainRelativePathToAnotherSandboxFile() {
        val clipDb = File(baseDir, "databases").apply { mkdirs() }
            .resolve("clipboard.db").apply { writeBytes(byteArrayOf(1)) }

        // No `..` needed: the old `File(filesDir, mediaPath)` resolved this straight onto the
        // clipboard database and deleted it, then pruned the now-empty `databases` directory.
        manager.deleteMedia("databases/clipboard.db")

        assertTrue("a database file must survive: $clipDb", clipDb.exists())
    }

    /**
     * The `..` shape. `clipboard_media/..` is `files`, and one more `..` is the package
     * directory — where `cleverkeys.db` (the prefs database) lives, outside `filesDir` entirely.
     * Modelled before writing: the old body deleted it, the new one refuses.
     *
     * A second manager over a nested layout, because the victim has to be a sibling of
     * `filesDir` for the traversal to reach it.
     */
    @Test
    fun deleteMedia_refusesADotDotEscapeOutOfFilesDir() {
        val pkgDir = Files.createTempDirectory("ck-zipslip-pkg").toFile()
        val filesDir = File(pkgDir, "files").apply { mkdirs() }
        File(filesDir, "clipboard_media").mkdirs()
        val escaped = File(pkgDir, "cleverkeys.db").apply { writeBytes(byteArrayOf(2)) }
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir

        ClipboardMediaManager(context).deleteMedia("clipboard_media/../../cleverkeys.db")

        assertTrue("nothing outside filesDir may be deleted: $escaped", escaped.exists())
        assertTrue("filesDir must survive", filesDir.isDirectory)
    }

    /**
     * The paths that resolve INSIDE the media directory but are not media files — and these are
     * worse than the escape above, because Java's `File.delete()` does not throw on a directory:
     * it returns false for a non-empty one and SUCCEEDS for an empty one. So the old body's
     * cleanup branch ("if the parent is now empty, remove it") ran on whatever the path's parent
     * happened to be, directories included.
     *
     * Measured against real directories, not assumed: a row whose path is just
     * `clipboard_media`, on a device where that directory is empty, had the old body delete the
     * media directory and then prune its parent — `filesDir` — taking the databases with it.
     */
    @Test
    fun deleteMedia_neverDeletesTheMediaDirectory() {
        val neighbour = File(baseDir, "databases").apply { mkdirs() }
            .resolve("clipboard.db").apply { writeBytes(byteArrayOf(3)) }
        File(baseDir, "clipboard_media").mkdirs()

        manager.deleteMedia("clipboard_media")

        assertTrue(
            "an empty media directory must not be deleted",
            File(baseDir, "clipboard_media").isDirectory,
        )
        assertTrue("nor anything beside it: $neighbour", neighbour.exists())
    }

    /**
     * The collapse itself, in the shape where it does the most damage: `filesDir` holding
     * nothing but the (empty) media directory. The old body deleted `clipboard_media`, found
     * `filesDir` empty, and pruned that too — so the app lost its files directory. This is the
     * first-run state, not a contrived one.
     */
    @Test
    fun deleteMedia_neverCollapsesFilesDir() {
        File(baseDir, "clipboard_media").mkdirs()

        manager.deleteMedia("clipboard_media")

        assertTrue("filesDir must still exist", baseDir.isDirectory)
    }

    /** The partition-level half of the same rule: a partition is not a media file either. */
    @Test
    fun deleteMedia_neverDeletesAnEmptyPartitionDirectory() {
        val partition = File(baseDir, "clipboard_media/042").apply { mkdirs() }

        manager.deleteMedia("clipboard_media/042")

        assertTrue("a partition must not be deleted by a path naming it", partition.isDirectory)
        assertTrue(
            "and the media directory above it must survive",
            File(baseDir, "clipboard_media").isDirectory,
        )
    }

    /**
     * The control: a real media path must still be deleted, and its partition pruned once the
     * last file is gone. Without this the three refusals above would pass on a `deleteMedia` that
     * had simply been made a no-op.
     */
    @Test
    fun deleteMedia_removesARealMediaFileAndItsEmptiedPartition() {
        val partition = File(baseDir, "clipboard_media/042").apply { mkdirs() }
        val media = partition.resolve("abc.png").apply { writeBytes(byteArrayOf(1)) }

        manager.deleteMedia("clipboard_media/042/abc.png")

        assertTrue("the media file should be gone", !media.exists())
        assertTrue("the now-empty partition should be pruned", !partition.exists())
        assertTrue("the media directory itself stays", File(baseDir, "clipboard_media").isDirectory)
    }

    /**
     * A partition that still holds another entry must NOT be pruned — the prune is conditional on
     * emptiness, and an unconditional `parentDir.delete()` would fail here anyway but silently
     * mask the bug if it ever stopped being empty.
     */
    @Test
    fun deleteMedia_keepsAPartitionThatStillHoldsMedia() {
        val partition = File(baseDir, "clipboard_media/042").apply { mkdirs() }
        partition.resolve("abc.png").writeBytes(byteArrayOf(1))
        val sibling = partition.resolve("def.png").apply { writeBytes(byteArrayOf(2)) }

        manager.deleteMedia("clipboard_media/042/abc.png")

        assertTrue("the deleted entry is gone", !partition.resolve("abc.png").exists())
        assertTrue("the partition keeps its remaining entry", sibling.exists())
        assertTrue("the partition survives", partition.isDirectory)
    }

    @Test
    fun getMediaFile_allowsLegitimateMediaPath() {
        // A normal partitioned media path stays inside filesDir and must resolve.
        val legit = "clipboard_media/042/abc.png"
        val file = manager.getMediaFile(legit)

        val canonicalBase = baseDir.canonicalPath
        assertTrue(
            "resolved file must live under filesDir: ${file.canonicalPath}",
            file.canonicalPath == canonicalBase ||
                file.canonicalPath.startsWith(canonicalBase + File.separator)
        )
        // And it must be the expected concrete location (no mangling of the name).
        assertEquals(
            File(baseDir, legit).canonicalPath,
            file.canonicalPath
        )
    }
}
