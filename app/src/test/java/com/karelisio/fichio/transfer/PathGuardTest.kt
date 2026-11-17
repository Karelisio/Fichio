package com.karelisio.fichio.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PathGuardTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var outside: File
    private lateinit var guard: PathGuard

    @Before fun setUp() {
        root = tmp.newFolder("storage")
        outside = tmp.newFolder("outside").also { File(it, "secret.txt").writeText("x") }
        File(root, "DCIM/Camera").mkdirs()
        File(root, "Android/data/com.app").mkdirs()
        File(root, "Android/obb").mkdirs()
        File(root, "Android/media").mkdirs()
        File(root, ".Fichio/trash").mkdirs()
        guard = PathGuard(root.path, listOf("${root.path}/Android/data", "${root.path}/Android/obb", "${root.path}/.Fichio"))
    }

    @Test fun resolvesPathsInsideRoot() {
        assertEquals(root.canonicalFile, guard.resolve("/"))
        assertEquals(root.canonicalFile, guard.resolve(""))
        assertEquals(root.canonicalFile, guard.resolve(null))
        assertEquals(File(root, "DCIM/Camera").canonicalFile, guard.resolve("/DCIM/Camera"))
        assertEquals(File(root, "DCIM/Camera").canonicalFile, guard.resolve("DCIM//./Camera/"))
        assertNotNull(guard.resolve("/Android/media"))
        assertEquals("/DCIM/Camera", guard.relative(guard.resolve("/DCIM/Camera")!!))
        assertEquals("/", guard.relative(guard.resolve("/")!!))
    }

    @Test fun refusesTraversal() {
        assertNull(guard.resolve("/.."))
        assertNull(guard.resolve("../outside/secret.txt"))
        assertNull(guard.resolve("/DCIM/../../outside"))
        assertNull(guard.resolve("/DCIM/../DCIM")) // any ".." segment is refused
        assertNull(guard.resolve("\\..\\outside"))
        assertNull(guard.resolve("/DCIM\u0000/x"))
    }

    @Test fun refusesRestrictedFolders() {
        assertNull(guard.resolve("/Android/data"))
        assertNull(guard.resolve("/Android/data/com.app"))
        assertNull(guard.resolve("/Android/obb"))
        assertNull(guard.resolve("/.Fichio/trash"))
        // Shared storage is case-insensitive on Android.
        assertNull(guard.resolve("/android/DATA/com.app"))
        assertNull(guard.resolve("/.fichio"))
    }

    @Test fun refusesSymlinksEscapingTheRoot() {
        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "sneaky").toPath(), File(root, "Android/data").toPath())
        Files.createSymbolicLink(File(root, "inside").toPath(), File(root, "DCIM").toPath())
        assertNull(guard.resolve("/escape"))
        assertNull(guard.resolve("/escape/secret.txt"))
        assertNull(guard.resolve("/sneaky/com.app"))
        assertEquals(File(root, "DCIM").canonicalFile, guard.resolve("/inside"))
    }

    @Test fun refusesPathsOutsideRootEvenIfAbsolute() {
        // An absolute client path is interpreted relative to the root, never as a real absolute path.
        val r = guard.resolve(outside.path)
        assertEquals(true, r == null || r.path.startsWith(root.canonicalPath + "/"))
        assertEquals(false, guard.isAllowed(File(outside, "secret.txt")))
        assertEquals(false, guard.isAllowed(File(root.parentFile, "storage-evil")))
    }

    @Test fun sanitisesNames() {
        assertEquals("photo.jpg", PathGuard.safeName("photo.jpg"))
        assertEquals("passwd", PathGuard.safeName("../../etc/passwd"))
        assertEquals("evil.txt", PathGuard.safeName("C:\\Users\\x\\evil.txt"))
        assertEquals("a b", PathGuard.safeName(" a\u0001 b. "))
        assertNull(PathGuard.safeName(".."))
        assertNull(PathGuard.safeName(""))
        assertNull(PathGuard.safeName(null))
        assertNull(PathGuard.safeName("x".repeat(300)))
    }

    @Test fun uniqueNamesOnConflict() {
        val existing = setOf("photo.jpg", "photo (1).jpg", "notes")
        assertEquals("photo (2).jpg", PathGuard.uniqueName("photo.jpg") { it in existing })
        assertEquals("notes (1)", PathGuard.uniqueName("notes") { it in existing })
        assertEquals("new.txt", PathGuard.uniqueName("new.txt") { it in existing })
        assertEquals(".bashrc (1)", PathGuard.uniqueName(".bashrc") { it == ".bashrc" })
    }

    @Test fun contentDispositionIsSafe() {
        val d = TransferServer.disposition("été \"x\".txt", inline = false)
        assertEquals("attachment; filename=\"_t_ _x_.txt\"; filename*=UTF-8''%C3%A9t%C3%A9%20%22x%22.txt", d)
    }
}
