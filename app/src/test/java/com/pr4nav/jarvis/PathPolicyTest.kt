package com.pr4nav.jarvis

import com.pr4nav.jarvis.tools.PathPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathPolicyTest {

    @Test
    fun `resolves home alias`() {
        assertEquals("/data/data/com.termux/files/home", PathPolicy.resolve("/", "~"))
        assertEquals(
            "/data/data/com.termux/files/home/notes.txt",
            PathPolicy.resolve("/x", "~/notes.txt")
        )
    }

    @Test
    fun `resolves relative paths against cwd`() {
        val r = PathPolicy.resolve("/storage/emulated/0/Documents", "project/a.kt")
        assertEquals("/storage/emulated/0/Documents/project/a.kt", r)
    }

    @Test
    fun `normalizes dotdot and dots`() {
        assertEquals("/a/c", PathPolicy.normalize("/a/b/../c"))
        assertEquals("/a", PathPolicy.normalize("/a/./b/..//"))
        assertEquals("/", PathPolicy.normalize("/.."))
    }

    @Test
    fun `shared storage is readable and writable`() {
        assertTrue(PathPolicy.readable("/storage/emulated/0/Documents/x.txt"))
        assertTrue(PathPolicy.writableWithoutRoot("/sdcard/Download/x.bin"))
    }

    @Test
    fun `termux home is readable`() {
        assertTrue(PathPolicy.readable("/data/data/com.termux/files/home/jarvis/bin/bootstrap.sh"))
    }

    @Test
    fun `foreign app data is denied without root`() {
        assertFalse(PathPolicy.readable("/data/data/com.whatsapp/databases/msgstore.db"))
        assertFalse(PathPolicy.readable("/system/build.prop"))
    }

    @Test
    fun `saf paths are always allowed`() {
        assertTrue(PathPolicy.readable("saf://tree/primary/Notes"))
    }
}
