package com.karelisio.fichio.ops

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HashingAndUndoTest {
    @Test
    fun md5AndSha256InOnePass() = runTest {
        var last = 0f
        val h = Hashing.compute("abc".byteInputStream(), 3) { last = it }
        assertEquals("900150983cd24fb0d6963f7d28e17f72", h[HashAlgorithm.MD5])
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h[HashAlgorithm.SHA256])
        assertEquals(1f, last)
    }

    @Test
    fun undoRevertsLastActionOnce() = runTest {
        val undo = UndoManager()
        val reverted = mutableListOf<UndoAction>()
        undo.bind { reverted += it; true }
        assertFalse(undo.undo())
        val a = UndoAction.Renamed(listOf("/a" to "/b"), "renommé")
        undo.record(a)
        assertEquals(a, undo.last.value)
        assertTrue(undo.undo())
        assertEquals(listOf<UndoAction>(a), reverted)
        assertNull(undo.last.value)
        assertFalse(undo.undo())
    }

    @Test
    fun failedUndoReturnsFalse() = runTest {
        val undo = UndoManager()
        undo.bind { error("boom") }
        undo.record(UndoAction.Created(listOf("/x"), "créé"))
        assertFalse(undo.undo())
    }
}
