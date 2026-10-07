package com.agent.bridge.ui3.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * TUR AYARLARI menüsündeki satırların ARKASI gerçekten bağlı mı — kaynak
 * tarayan bekçi test.
 *
 * Bu depoda tekrarlayan bir hata sınıfı var: delege yazılıyor, satır çiziliyor,
 * ama okuma/yönlendirme dallarından biri unutuluyor. Sonuç sessiz: kod
 * derleniyor, test geçiyor, kullanıcı satıra dokunuyor ve HİÇBİR ŞEY olmuyor.
 * [com.agent.bridge.DelegateReachabilityTest] bunun delege ayağını kapatıyor;
 * burada menü ayağı kapanıyor.
 *
 * Üç kural:
 *  1. Menüdeki her `onAc(Ui3Sheet.X)` satırının kökte (`Ui3Root.kt`) bir
 *     `Ui3Sheet.X ->` dalı olmalı — yoksa satır BOŞ bir sheet açar.
 *  2. `BackendActions.kt`'deki her yönlendirme kapısı "opencode2-app" dalını
 *     veriyorsa "cowork" dalını da vermeli. Görünürlük yardımcıları
 *     (`backend*Supported`) cowork+opencode'da true diyor, yani satır orada da
 *     ÇİZİLİYOR; cowork dalı eksik olsaydı dokunmak hiçbir şey yapmazdı.
 *  3. Menü satırının görünürlük koşulu ile köşedeki sheet dalının koşulu AYNI
 *     yardımcıdan gelmeli — ikisi ayrışırsa satır görünürken sheet boş kalır
 *     (ya da tersi).
 */
class Ui3MenuYonlendirmeTest {

    @Test
    fun menudekiHerSatirinKokteBirDaliVar() {
        val menu = oku("android/app/src/main/java/com/agent/bridge/ui3/chat/Ui3TurAyarlari.kt")
        val kok = oku("android/app/src/main/java/com/agent/bridge/ui3/nav/Ui3Root.kt")

        // `onAc(Ui3Sheet.X)` ve `{ onAc(Ui3Sheet.X) }` biçimlerinin ikisi de.
        val kimlikler = Regex("""onAc\(Ui3Sheet\.([A-Z_]+)\)""")
            .findAll(menu).map { it.groupValues[1] }.toSortedSet()

        assertTrue("Menüde hiç sheet açan satır bulunamadı — regex bayatlamış olabilir", kimlikler.isNotEmpty())
        val eksik = kimlikler.filterNot { kok.contains("Ui3Sheet.$it ->") }
        assertEquals("Kökte dalı olmayan sheet kimlikleri: $eksik", emptyList<String>(), eksik)
    }

    @Test
    fun opencodeDaliOlanHerKapiCoworkDaliniDaVerir() {
        val kaynak = oku("android/app/src/main/java/com/agent/bridge/BackendActions.kt")
        // Yorumlar elenmeli: gerekçe metinlerinde "opencode2-app" tırnak içinde geçiyor.
        val kod = kaynak.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

        // Her `fun RemoteViewModel.<ad>` bloğunu bir sonraki tanıma kadar al.
        val kapilar = Regex("""fun RemoteViewModel\.([A-Za-z0-9_]+)""").findAll(kod).toList()
        assertTrue("BackendActions.kt'de yönlendirme kapısı bulunamadı", kapilar.isNotEmpty())

        val eksik = mutableListOf<String>()
        kapilar.forEachIndexed { i, m ->
            val bas = m.range.first
            val son = if (i + 1 < kapilar.size) kapilar[i + 1].range.first else kod.length
            val govde = kod.substring(bas, son)
            // Dal ÇOK KİMLİKLİ olabilir (`"opencode2-app" ->`);
            // düz metin araması böyle bir dalı görmez ve testi sessizce
            // devre dışı bırakırdı (26.09.2026'da tam bu oldu).
            val opencodeDali = Regex(""""opencode2-app"(?:\s*,\s*"[a-z0-9-]+")*\s*->""").containsMatchIn(govde)
            if (opencodeDali && !govde.contains("\"cowork\" ->")) {
                eksik += m.groupValues[1]
            }
        }
        assertEquals("opencode dalı olup cowork dalı olmayan kapılar: $eksik", emptyList<String>(), eksik)
    }

    @Test
    fun paylasVeAgentsInitSatirlariKokteAyniKosulaBagli() {
        val menu = oku("android/app/src/main/java/com/agent/bridge/ui3/chat/Ui3TurAyarlari.kt")
        val kok = oku("android/app/src/main/java/com/agent/bridge/ui3/nav/Ui3Root.kt")

        // Satır ve sheet dalı AYNI yardımcıyı sınamalı.
        for (yardimci in listOf("backendShareSupported", "backendAgentsInitSupported")) {
            assertTrue("$yardimci menü satırında kullanılmıyor", menu.contains("$yardimci(backendId)"))
            assertTrue("$yardimci kökteki sheet dalında kullanılmıyor", kok.contains("$yardimci(backendId)"))
        }
        // Sheet kimlikleri kökte gerçekten bağlı.
        assertTrue(kok.contains("Ui3Sheet.OTURUM_PAYLAS ->"))
        assertTrue(kok.contains("Ui3Sheet.AGENTS_INIT ->"))
    }

    /**
     * DEĞİŞİKLİKLER üçlüsü: görünürlük — yükleme — okuma AYNI kimlik kümesine
     * bakmalı.
     *
     * [com.agent.bridge.DelegateReachabilityTest] bu boşluğu göremiyor: o yalnız
     * "delege metoduna bir yerden ulaşılıyor mu" diye bakıyor, ViewModel'dan
     * ARAYÜZE giden ayağı değil. Codex'in dosya değişikliği yolu tam da orada
     * yarım kalmıştı — köprü ucu, istemci çağrısı, delege ve ViewModel
     * yönlendirmesi vardı, hiçbir ekran çağırmıyordu ve hiçbir test görmüyordu.
     *
     * Kural: `backendDiffSupported` hangi backend kimliğine true diyorsa,
     * `loadBackendDiff`te o kimliğin bir dalı VE `backendDiff`te o kimliğin bir
     * okuma dalı olmalı.
     */
    @Test
    fun degisikliklerGorunurlukYuklemeVeOkumaAyniKimlikleriKapsar() {
        val secenekler = oku("android/shared/src/main/kotlin/com/agent/bridge/BackendOptions.kt")
        val eylemler = oku("android/app/src/main/java/com/agent/bridge/BackendActions.kt")
        val kok = oku("android/app/src/main/java/com/agent/bridge/ui3/nav/Ui3Root.kt")

        val gorunur = kimlikler(govde(secenekler, "fun RemoteUiState.backendDiffSupported"))
        assertTrue("backendDiffSupported dalları okunamadı — regex bayatlamış olabilir", gorunur.isNotEmpty())

        val yuklenen = kimlikler(govde(eylemler, "fun RemoteViewModel.loadBackendDiff"))
        val okunan = kimlikler(govde(secenekler, "fun RemoteUiState.backendDiff("))
        assertEquals("Menüde görünüp yükleme dalı olmayan kimlikler", emptySet<String>(), gorunur - yuklenen)
        assertEquals("Menüde görünüp okuma dalı olmayan kimlikler", emptySet<String>(), gorunur - okunan)

        // Sheet gövdesi SABİT bir kutu okumamalı: `uiState.opencode.diff` yazmak
        // codex sekmesinde satırı görünür, sheet'i sonsuza dek boş bırakırdı.
        assertTrue("Kök diff kutusunu backendDiff ile seçmiyor", kok.contains("uiState.backendDiff(backendId)"))
        assertTrue("Kök yükleme bayrağını backendDiffLoading ile seçmiyor", kok.contains("uiState.backendDiffLoading(backendId)"))
        assertTrue(
            "Kök hâlâ sabit opencode kutusunu okuyor",
            !kok.contains("diff = uiState.opencode.diff"),
        )
    }

    /**
     * DEĞİŞİKLİKLER'de kurulan kuralın GERİ SAR / KOMUTLAR / AGENTS.md init /
     * SKILL satırlarına genellenmiş hâli.
     *
     * 26.09.2026'da opencode2 bu dört kapının HİÇBİRİNDE yoktu: köprüde uçlar
     * hazırdı, capability kataloğu `true` diyordu, telefon yönlendirme dalını
     * yazmamıştı — satırlar çizilmiyor, yazılan delege hiç çağrılmıyordu.
     * Görünürlük bir kimliğe `true` diyorsa yükleme/eylem kapılarında o
     * kimliğin dalı OLMAK ZORUNDA.
     */
    @Test
    fun gorunurlukVerenHerKapininYonlendirmeDaliVar() {
        val secenekler = oku("android/shared/src/main/kotlin/com/agent/bridge/BackendOptions.kt")
        val eylemler = oku("android/app/src/main/java/com/agent/bridge/BackendActions.kt")

        val kontroller = listOf(
            Triple(
                "Geri sar", "fun RemoteUiState.backendRevertSupported",
                listOf(
                    "fun RemoteViewModel.loadBackendCheckpoints",
                    "fun RemoteViewModel.revertBackendToMessage",
                    "fun RemoteViewModel.unrevertBackend",
                ),
            ),
            Triple(
                "Komutlar", "fun RemoteUiState.backendCommandsSupported",
                listOf("fun RemoteViewModel.loadBackendCommands", "fun RemoteViewModel.runBackendCommand"),
            ),
            Triple(
                "AGENTS.md init", "fun RemoteUiState.backendAgentsInitSupported",
                listOf("fun RemoteViewModel.initBackendAgentsFile"),
            ),
            Triple(
                "Skill", "fun RemoteUiState.backendSkillsSupported",
                listOf("fun RemoteViewModel.loadBackendSkills"),
            ),
            Triple(
                "Ajan", "fun RemoteUiState.backendAgentSupported",
                listOf("fun RemoteViewModel.loadBackendAgents", "fun RemoteViewModel.setBackendAgent"),
            ),
        )

        for ((ad, gorunurlukImza, yuklemeImzalari) in kontroller) {
            val gorunur = kimlikler(govde(secenekler, gorunurlukImza))
            assertTrue("$ad: görünürlük dalları okunamadı — regex bayatlamış olabilir", gorunur.isNotEmpty())
            for (imza in yuklemeImzalari) {
                val yuklenen = kimlikler(govde(eylemler, imza))
                assertEquals(
                    "$ad: görünür ama $imza içinde dalı olmayan kimlikler",
                    emptySet<String>(), gorunur - yuklenen,
                )
            }
        }
    }

    /**
     * Skill / komut / geri sarma kutuları SABİT `opencode` ailesinden
     * okunmamalı — v2 sekmesinde satır görünür, gövde boş kalırdı (MODEL SEÇ
     * sheet'inde 26.09.2026'da tam olarak bu yaşandı).
     */
    @Test
    fun opencodeKutulariAileyeGoreOkunuyor() {
        val kok = oku("android/app/src/main/java/com/agent/bridge/ui3/nav/Ui3Root.kt")
        val sohbet = oku("android/app/src/main/java/com/agent/bridge/ui3/chat/Ui3ChatScreen.kt")
        val secenekler = oku("android/shared/src/main/kotlin/com/agent/bridge/BackendOptions.kt")

        assertTrue("Geri sar listesi sabit v1 kutusundan okunuyor", !kok.contains("uiState.opencode.checkpoints"))
        assertTrue("Geri sarma şeridi sabit v1 kutusundan okunuyor", !sohbet.contains("uiState.opencode.reverted"))
        assertTrue("Komut kataloğu sabit v1 kutusundan okunuyor", !secenekler.contains("opencode.commands"))
    }

    /** Bir fonksiyonun gövdesi: imzadan bir sonraki üst düzey `fun`a kadar. */
    private fun govde(kaynak: String, imza: String): String {
        val kod = kaynak.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        val bas = kod.indexOf(imza)
        assertTrue("İmza bulunamadı: $imza", bas >= 0)
        val son = Regex("""\nfun """).find(kod, bas + imza.length)?.range?.first ?: kod.length
        return kod.substring(bas, son)
    }

    /**
     * `"x", "y" ->` biçimindeki `when` dallarındaki backend kimlikleri.
     *
     * COWORK DALINDAN ÖNCESİ okunuyor, bilerek: cowork'ün İÇİNDE de aynı
     * kimlikler geçiyor (`"cowork" -> when(sağlayıcı) { "codex-app" -> ... }`)
     * ve bütün gövde taransaydı üst düzey dalın silinmesi bu testten kaçardı —
     * yani testin yakalamak için var olduğu hata sınıfı görünmez olurdu. Cowork
     * ayağını [opencodeDaliOlanHerKapiCoworkDaliniDaVerir] ve
     * BackendOptionsTest davranışsal olarak kapatıyor.
     */
    private fun kimlikler(govde: String): Set<String> {
        val ustDuzey = govde.substringBefore("\"cowork\" ->")
        return Regex(""""([a-z0-9-]+)"\s*(?:,\s*"[a-z0-9-]+"\s*)*->""").findAll(ustDuzey)
            .flatMap { m -> Regex(""""([a-z0-9-]+)"""").findAll(m.value).map { it.groupValues[1] } }
            .toSet()
    }

    private fun oku(yol: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val aday = File(dir, yol)
            if (aday.isFile) return aday.readText(Charsets.UTF_8).removePrefix("﻿")
            dir = dir.parentFile
        }
        fail("Kaynak bulunamadı: $yol (çalışma dizini ${File("").absolutePath})")
        throw IllegalStateException("ulaşılmaz")
    }
}
