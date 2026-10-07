package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class FolderPickerModelsTest {

    // ── Breadcrumb ──────────────────────────────────────────────────────────

    @Test
    fun breadcrumbWindowsSimplePath() {
        val crumbs = folderBreadcrumb("C:\\Users\\Dev\\agtest")
        assertEquals(4, crumbs.size)
        assertEquals("C:" to "C:", crumbs[0])
        assertEquals("Users" to "C:\\Users", crumbs[1])
        assertEquals("Dev" to "C:\\Users\\Dev", crumbs[2])
        assertEquals("agtest" to "C:\\Users\\Dev\\agtest", crumbs[3])
    }

    @Test
    fun breadcrumbWindowsDriveOnly() {
        val crumbs = folderBreadcrumb("D:")
        assertEquals(1, crumbs.size)
        assertEquals("D:" to "D:", crumbs[0])
    }

    @Test
    fun breadcrumbWindowsRootDrives() {
        val crumbs = folderBreadcrumb("C:\\")
        assertEquals(1, crumbs.size)
        assertEquals("C:" to "C:", crumbs[0])
    }

    @Test
    fun breadcrumbUnixSimplePath() {
        val crumbs = folderBreadcrumb("/home/user/dir")
        assertEquals(4, crumbs.size)
        assertEquals("/" to "/", crumbs[0])
        assertEquals("home" to "/home", crumbs[1])
        assertEquals("user" to "/home/user", crumbs[2])
        assertEquals("dir" to "/home/user/dir", crumbs[3])
    }

    @Test
    fun breadcrumbUnixRootOnly() {
        val crumbs = folderBreadcrumb("/")
        assertEquals(1, crumbs.size)
        assertEquals("/" to "/", crumbs[0])
    }

    @Test
    fun breadcrumbForwardSlashWindows() {
        // Windows paths written with forward slashes should still work
        val crumbs = folderBreadcrumb("C:/Users/Dev")
        assertEquals(3, crumbs.size)
        assertEquals("C:" to "C:", crumbs[0])
        assertEquals("Users" to "C:\\Users", crumbs[1])
        assertEquals("Dev" to "C:\\Users\\Dev", crumbs[2])
    }

    @Test
    fun breadcrumbTrimmedWhitespace() {
        val crumbs = folderBreadcrumb("  C:\\Users  ")
        assertEquals(2, crumbs.size)
        assertEquals("C:" to "C:", crumbs[0])
        assertEquals("Users" to "C:\\Users", crumbs[1])
    }

    @Test
    fun breadcrumbEmptyOrBlankPath() {
        assertEquals(0, folderBreadcrumb("").size)
        assertEquals(0, folderBreadcrumb("  ").size)
    }

    @Test
    fun breadcrumbUncPath() {
        val crumbs = folderBreadcrumb("\\\\server\\share\\sub\\deep")
        assertTrue(crumbs.size >= 3)
        // The root should be \\server\share
        assertEquals("\\\\server\\share", crumbs[0].second)
        assertEquals("sub", crumbs[1].first)
        assertEquals("\\\\server\\share\\sub", crumbs[1].second)
    }

    // ── Path key normalization ──────────────────────────────────────────────

    @Test
    fun pathKeyNormalizesBackslashToForwardSlash() {
        assertEquals("c:/users/dev", folderPathKey("C:\\Users\\Dev"))
    }

    @Test
    fun pathKeyTrimsTrailingSlash() {
        assertEquals("c:/users", folderPathKey("C:\\Users\\"))
    }

    @Test
    fun pathKeyIsCaseInsensitive() {
        assertEquals(folderPathKey("C:\\Users\\DEV"), folderPathKey("c:\\users\\dev"))
    }

    @Test
    fun pathKeyMatchesMixedSeparators() {
        val a = folderPathKey("C:\\Users/Dev\\app")
        val b = folderPathKey("c:/Users\\dev/App")
        assertEquals(a, b)
    }
}
