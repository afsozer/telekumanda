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
 * İki kural:
 *  1. Menüdeki her `onAc(Ui3Sheet.X)` satırının kökte (`Ui3Root.kt`) bir
 *     `Ui3Sheet.X ->` dalı olmalı — yoksa satır BOŞ bir sheet açar.
 *  2. Menü satırının görünürlük koşulu ile köşedeki sheet dalının koşulu AYNI
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

        // Sheet gövdesi SABİT bir kutu okumamalı: `uiState.omp.diff` yazmak
        // codex sekmesinde satırı görünür, sheet'i sonsuza dek boş bırakırdı.
        assertTrue("Kök diff kutusunu backendDiff ile seçmiyor", kok.contains("uiState.backendDiff(backendId)"))
        assertTrue("Kök yükleme bayrağını backendDiffLoading ile seçmiyor", kok.contains("uiState.backendDiffLoading(backendId)"))
        assertTrue(
            "Kök hâlâ sabit bir kutu okuyor",
            !kok.contains("diff = uiState.omp.diff"),
        )
    }

    /**
     * DEĞİŞİKLİKLER'de kurulan kuralın GERİ SAR / KOMUTLAR / AGENTS.md init /
     * SKILL satırlarına genellenmiş hâli.
     *
     * 26.09.2026'da sökülen backend bu dört kapının HİÇBİRİNDE yoktu: köprüde uçlar
     * hazırdı, capability kataloğu `true` diyordu, telefon yönlendirme dalını
     * yazmamıştı — satırlar çizilmiyor, yazılan delege hiç çağrılmıyordu.
     * Görünürlük bir kimliğe `true` diyorsa yükleme/eylem kapılarında o
     * kimliğin dalı OLMAK ZORUNDA.
     */
    @Test
    fun gorunurlukVerenHerKapininYonlendirmeDaliVar() {
        val secenekler = oku("android/shared/src/main/kotlin/com/agent/bridge/BackendOptions.kt")
        val eylemler = oku("android/app/src/main/java/com/agent/bridge/BackendActions.kt")

        // Geri sar / Komutlar / AGENTS.md init / Ajan kapıları (24.10.2026)
        // söküldü; kural kalan kapılarda geçerli.
        val kontroller = listOf(
            Triple(
                "Skill", "fun RemoteUiState.backendSkillsSupported",
                listOf("fun RemoteViewModel.loadBackendSkills"),
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
     * ayağını BackendOptionsTest davranışsal olarak kapatıyor.
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
