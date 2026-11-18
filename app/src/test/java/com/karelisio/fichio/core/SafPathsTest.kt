package com.karelisio.fichio.core

import com.karelisio.fichio.core.fs.SafPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafPathsTest {
    private val root = "/storage/1234-ABCD"
    private val vol = "1234-ABCD"
    private val rootTree = "1234-ABCD:"

    @Test fun pathToDocumentIdOnTheVolumeRootTree() {
        assertEquals("1234-ABCD:", SafPaths.documentIdFor(root, vol, rootTree, root))
        assertEquals("1234-ABCD:", SafPaths.documentIdFor(root, vol, rootTree, "$root/"))
        assertEquals("1234-ABCD:DCIM", SafPaths.documentIdFor(root, vol, rootTree, "$root/DCIM"))
        assertEquals("1234-ABCD:DCIM/Camera/IMG 1.jpg", SafPaths.documentIdFor(root, vol, rootTree, "$root/DCIM/Camera/IMG 1.jpg"))
        assertEquals("1234-ABCD:Été/été.txt", SafPaths.documentIdFor(root, vol, rootTree, "$root/Été/été.txt"))
    }

    @Test fun pathsOutsideTheVolumeOrTreeAreRejected() {
        assertNull(SafPaths.documentIdFor(root, vol, rootTree, "/storage/emulated/0/DCIM"))
        // Same prefix, other volume.
        assertNull(SafPaths.documentIdFor(root, vol, rootTree, "/storage/1234-ABCDE/x"))
        // Tree of another volume.
        assertNull(SafPaths.documentIdFor(root, vol, "9999-0000:", "$root/x"))
        assertNull(SafPaths.documentIdFor(root, vol, "not-a-tree", "$root/x"))
        val music = "1234-ABCD:Music"
        assertEquals("1234-ABCD:Music", SafPaths.documentIdFor(root, vol, music, "$root/Music"))
        assertEquals("1234-ABCD:Music/a.mp3", SafPaths.documentIdFor(root, vol, music, "$root/Music/a.mp3"))
        assertNull(SafPaths.documentIdFor(root, vol, music, "$root/Musicals/a.mp3"))
        assertNull(SafPaths.documentIdFor(root, vol, music, root))
        assertFalse(SafPaths.covers(root, vol, music, "$root/DCIM"))
        assertTrue(SafPaths.covers(root, vol, music, "$root/Music/sub"))
    }

    @Test fun volumeIdsCompareCaseInsensitively() {
        assertTrue(SafPaths.isTreeOf("1234-abcd", "1234-ABCD:"))
        // The tree's own spelling is kept in the produced ids.
        assertEquals("1234-ABCD:a", SafPaths.documentIdFor(root, "1234-abcd", rootTree, "$root/a"))
        assertTrue(SafPaths.isVolumeRoot("1234-ABCD:"))
        assertTrue(SafPaths.isVolumeRoot("1234-ABCD:/"))
        assertFalse(SafPaths.isVolumeRoot("1234-ABCD:Music"))
        assertEquals("1234-ABCD", SafPaths.fallbackVolumeId("/storage/1234-ABCD/"))
    }

    @Test fun documentIdToPathRoundTrips() {
        listOf(root, "$root/DCIM", "$root/DCIM/Camera/IMG 1.jpg", "$root/a:b/c").forEach { p ->
            val id = SafPaths.documentIdFor(root, vol, rootTree, p)!!
            assertEquals(p, SafPaths.pathFor(root, vol, rootTree, id))
        }
        assertEquals("$root/x", SafPaths.pathFor(root, vol, rootTree, "1234-abcd:x"))
        assertNull(SafPaths.pathFor(root, vol, rootTree, "primary:DCIM"))
        assertNull(SafPaths.pathFor(root, vol, rootTree, "garbage"))
        assertNull(SafPaths.pathFor(root, vol, "1234-ABCD:Music", "1234-ABCD:DCIM/a"))
        assertEquals("$root/Music/a", SafPaths.pathFor(root, vol, "1234-ABCD:Music", "1234-ABCD:Music/a"))
    }

    @Test fun missingDirsTopDown() {
        val existing = setOf(root, "$root/A")
        assertEquals(
            listOf("$root/A/B", "$root/A/B/C"),
            SafPaths.missingDirs(root, vol, rootTree, "$root/A/B/C") { it in existing },
        )
        assertEquals(emptyList<String>(), SafPaths.missingDirs(root, vol, rootTree, "$root/A") { it in existing })
        assertNull(SafPaths.missingDirs(root, vol, rootTree, "/storage/emulated/0/A") { true })
        // Sub-folder tree: creation stops at the tree's top folder.
        assertEquals(
            listOf("$root/Music/New"),
            SafPaths.missingDirs(root, vol, "1234-ABCD:Music", "$root/Music/New") { it == "$root/Music" },
        )
        assertNull(SafPaths.missingDirs(root, vol, "1234-ABCD:Music", "$root/Music/New") { false })
    }
}
