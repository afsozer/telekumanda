package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeSwitchResetTest {
    private val dir = DirEntry(name = "C:", path = "C:\\")

    private fun dirty() = RemoteUiState().let { s ->
        s.copy(
            activeBridgeProfileId = "mac",
            input = "yarım mesaj",
            files = s.files.copy(
                browserEntries = listOf(dir), browserBase = "C:\\Users", driveRoots = listOf(dir),
                phoneEntries = listOf(dir),
            ),
            claude = s.claude.copy(
                sessionId = "canli",
                diskSessions = listOf(ClaudeDiskSession(id = "d1", cwd = "C:\\", title = "t", lastText = "", turns = 1, mtime = 0L)),
            ),
            workerDirs = WorkerDirs(ok = true, base = "C:\\", parent = "", dirs = listOf(dir)),
        )
    }

    @Test
    fun clearsDataFetchedFromOldBridge() {
        val s = dirty().clearedForBridgeSwitch()
        assertTrue(s.files.browserEntries.isEmpty())
        assertEquals("", s.files.browserBase)
        // Boş olmalı: loadDriveRoots "doluysa çekme" diyor, dolu kalırsa yeni
        // köprünün sürücüleri hiç gelmez.
        assertTrue(s.files.driveRoots.isEmpty())
        assertTrue(s.claude.diskSessions.isEmpty())
        assertNull(s.workerDirs)
        assertNull(s.backendCatalog)
    }

    @Test
    fun keepsPhoneSideAndSessionIdentity() {
        val s = dirty().clearedForBridgeSwitch()
        assertEquals("mac", s.activeBridgeProfileId)
        assertEquals("yarım mesaj", s.input)
        assertEquals(listOf(dir), s.files.phoneEntries)
        // goToLanding bunu okuyup modu kapatıyor; burada silinirse çıkış yarım kalır.
        assertEquals("canli", s.claude.sessionId)
    }
}
