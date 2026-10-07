package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoworkUiLogicTest {
    @Test
    fun providerSegmentShowsAllCoworkBackends() {
        assertEquals(
            listOf(
                CoworkProviderOption("claude-app", "Claude"),
                CoworkProviderOption("codex-app", "Codex"),
                CoworkProviderOption("opencode2-app", "OpenCode"),
                CoworkProviderOption("opencode2-app", "OpenCode"),
                CoworkProviderOption("omp", "Oh My Pi"),
            ),
            COWORK_PROVIDER_OPTIONS,
        )
    }

    @Test
    fun providerNormalizationFallsBackToClaude() {
        assertEquals("claude-app", normalizeCoworkProvider(""))
        assertEquals("claude-app", normalizeCoworkProvider("unknown"))
        assertEquals("claude-app", normalizeCoworkProvider("claude-app"))
        assertEquals("codex-app", normalizeCoworkProvider("codex-app"))
        assertEquals("opencode2-app", normalizeCoworkProvider("opencode2-app"))
        assertEquals("omp", normalizeCoworkProvider("omp"))
        // Kaldırılmış bir backend eski kayıtlarda sağlayıcı olarak kalmış
        // olabilir; bilinmeyen değer Claude'a düşmeli, oturum açılamaz olmamalı.
        assertEquals("claude-app", normalizeCoworkProvider("kaldirilmis-app"))
    }

    @Test
    fun providerSwitchStateUsesTheSelectedBackendSessionSlot() {
        assertFalse(isCoworkCodexProvider("claude-app"))
        assertTrue(isCoworkCodexProvider("codex-app"))
        assertFalse(isCoworkCodexProvider("anything-else"))
    }

    @Test
    fun approvalRoutingFollowsSelectedCoworkProvider() {
        assertEquals("claude-app", coworkApprovalBackend("cowork", "claude-app"))
        assertEquals("codex-app", coworkApprovalBackend("cowork", "codex-app"))
        assertEquals("codex-app", coworkApprovalBackend("codex-app", "claude-app"))
        assertEquals("opencode2-app", coworkApprovalBackend("cowork", "opencode2-app"))
        assertEquals("opencode2-app", coworkApprovalBackend("cowork", "opencode2-app"))
        assertEquals("claude-app", coworkApprovalBackend("claude-app", "codex-app"))
    }

    @Test
    fun modelPickerUsesActiveCoworkProvider() {
        assertEquals("claude-app", coworkModelProvider("claude-app"))
        assertEquals("codex-app", coworkModelProvider("codex-app"))
        assertEquals("opencode2-app", coworkModelProvider("opencode2-app"))
        assertEquals("opencode2-app", coworkModelProvider("opencode2-app"))
        assertEquals("claude-app", coworkModelProvider("bad-provider"))
    }
}

/**
 * Cowork oturum listesi HANGİ yola bakar?
 *
 * Bu testin sebebi canlı bir hata: liste açılışında köprüye Cowork root'unun
 * dışındaki bir repo yolu gidiyordu ve her seferinde "Cowork oturumları
 * yüklenemedi: projectPath Cowork root disinda" çıkıyordu. Sebep, hedefin
 * `backendSession("cowork").cwd` üzerinden okunmasıydı: o türetilmiş alan
 * aktif cowork yolu boşken ALTTAKİ sağlayıcının cwd'sine düşüyor.
 */
class CoworkSessionListTargetTest {
    // Aktif cowork oturumu yokken hedef BOŞ olmalı — boş "hepsini listele"
    // demektir. Alttaki claude/omp cwd'si buraya SIZMAMALI.
    @Test
    fun blankWhenNoActiveCoworkSessionEvenIfProviderHasCwd() {
        val state = RemoteUiState(
            claude = ClaudeUiState(cwd = "C:/Users/x/agtest"),
            omp = OpencodeUiState(cwd = "C:/Users/x/agtest"),
            cowork = CoworkUiState(activeProjectPath = "", selectedWorkspace = ""),
        )

        assertEquals("", coworkOturumListesiHedefi(state))
        // Türetilmiş alan hâlâ sağlayıcı cwd'sini gösteriyor: hedefin oradan
        // okunmadığını bu satır bekçiliyor.
        assertEquals("C:/Users/x/agtest", state.backendSession("cowork").cwd)
    }

    @Test
    fun activeCoworkPathWins() {
        val state = RemoteUiState(
            claude = ClaudeUiState(cwd = "C:/Users/x/agtest"),
            cowork = CoworkUiState(
                activeProjectPath = "C:/Users/x/CoworkSpaces/dava",
                selectedWorkspace = "C:/Users/x/CoworkSpaces/baska",
            ),
        )

        assertEquals("C:/Users/x/CoworkSpaces/dava", coworkOturumListesiHedefi(state))
    }

    // Aktif oturum yoksa ekranda seçili workspace kullanılır (çekmecenin
    // "seçim listelerken sabit kalır" gereksinimi).
    @Test
    fun fallsBackToSelectedWorkspace() {
        val state = RemoteUiState(
            claude = ClaudeUiState(cwd = "C:/Users/x/agtest"),
            cowork = CoworkUiState(
                activeProjectPath = "",
                selectedWorkspace = "C:/Users/x/CoworkSpaces/baska",
            ),
        )

        assertEquals("C:/Users/x/CoworkSpaces/baska", coworkOturumListesiHedefi(state))
    }
}
