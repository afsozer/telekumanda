package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class CoworkBrowserScopeTest {

    private val root = "C:\\Users\\kullanici\\CoworkSpaces"
    private val workspace = "$root\\örnek dosya"

    // Asıl hata buydu: kök doluyken başlangıç klasörü yok sayılıyor, çalışma
    // alanının içinden "Dosyalar"a girince genel kök açılıyordu.
    @Test
    fun startsInWorkspaceWhileStayingLockedToRoot() {
        val scope = coworkBrowserScope(root, workspace)
        assertEquals(root, scope.rootLock)
        assertEquals(workspace, scope.startDir)
    }

    @Test
    fun generalEntryOpensRoot() {
        val scope = coworkBrowserScope(root, "")
        assertEquals(root, scope.rootLock)
        assertEquals(root, scope.startDir)
    }

    // Kök henüz köprüden gelmediyse sınır olarak alanın kendisi kullanılır:
    // kilitsiz bırakmaktansa dar kilit.
    @Test
    fun deepEntryBeforeRootArrivesLocksToWorkspace() {
        val scope = coworkBrowserScope("", workspace)
        assertEquals(workspace, scope.rootLock)
        assertEquals(workspace, scope.startDir)
    }

    @Test
    fun emptyInputsStayEmptySoCallerCanLoadWorkspaceList() {
        val scope = coworkBrowserScope("", "")
        assertEquals("", scope.rootLock)
        assertEquals("", scope.startDir)
    }
}
