package dev.localai.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for the guest path jail. The jail is the security boundary of the
 * read_file/write_file/list_dir tools, so symlink and `..` escapes are covered
 * explicitly, not just the happy path.
 */
class GuestPathsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var alpineRootfs: File
    private lateinit var sharedDir: File

    private fun setUpRoots() {
        alpineRootfs = tmp.newFolder("alpine")
        File(alpineRootfs, "workspace").mkdirs()
        sharedDir = tmp.newFolder("shared")
    }

    @Test
    fun mapsWorkspaceFileToHostPath() {
        setUpRoots()
        val target = GuestPaths.resolve(alpineRootfs, sharedDir, "/workspace/src/Main.kt")
        assertEquals(
            File(alpineRootfs, "workspace/src/Main.kt").canonicalPath,
            target.canonicalPath,
        )
    }

    @Test
    fun mapsWorkspaceRootItself() {
        setUpRoots()
        val target = GuestPaths.resolve(alpineRootfs, sharedDir, "/workspace")
        assertEquals(
            File(alpineRootfs, "workspace").canonicalPath,
            target.canonicalPath,
        )
    }

    @Test
    fun mapsSharedToSeparateHostDir() {
        setUpRoots()
        val target = GuestPaths.resolve(alpineRootfs, sharedDir, "/shared/out.txt")
        assertEquals(
            File(sharedDir, "out.txt").canonicalPath,
            target.canonicalPath,
        )
    }

    @Test
    fun rejectsRelativePath() {
        setUpRoots()
        assertThrows(ToolException::class.java) {
            GuestPaths.resolve(alpineRootfs, sharedDir, "workspace/file.txt")
        }
    }

    @Test
    fun rejectsGuestPathsOutsideJail() {
        setUpRoots()
        for (path in listOf("/etc/passwd", "/system/build.prop", "/workspaceish/file")) {
            assertThrows(path) {
                GuestPaths.resolve(alpineRootfs, sharedDir, path)
            }
        }
    }

    @Test
    fun rejectsDotDotEscape() {
        setUpRoots()
        assertThrows(ToolException::class.java) {
            GuestPaths.resolve(alpineRootfs, sharedDir, "/workspace/../../etc/passwd")
        }
    }

    @Test
    fun rejectsSymlinkEscapeInsideWorkspace() {
        setUpRoots()
        val secret = tmp.newFile("secret.txt")
        val link = File(File(alpineRootfs, "workspace"), "evil")
        java.nio.file.Files.createSymbolicLink(
            link.toPath(),
            secret.toPath(),
        )
        assertThrows(ToolException::class.java) {
            GuestPaths.resolve(alpineRootfs, sharedDir, "/workspace/evil")
        }
        assertThrows(ToolException::class.java) {
            GuestPaths.resolve(alpineRootfs, sharedDir, "/workspace/evil/inner.txt")
        }
    }

    @Test
    fun allowsDeepPathUnderShared() {
        setUpRoots()
        val target = GuestPaths.resolve(alpineRootfs, sharedDir, "/shared/a/b/c.txt")
        assertTrue(target.path.startsWith(sharedDir.canonicalPath))
    }

    private fun assertThrows(path: String, block: () -> Unit) {
        try {
            block()
        } catch (expected: ToolException) {
            return
        }
        throw AssertionError("expected ToolException for $path")
    }
}
