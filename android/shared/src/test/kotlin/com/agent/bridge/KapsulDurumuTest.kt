package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Kapsül durumunun saf türetmesi (plan: docs/kapsul-live-updates-plani.md
// §1.2, §3.1, §4). Cihaz gerekmez; kapsülün "onay önceliği" ve sayaç kuralları
// Faz 8'de MediaSession yolunda ölçülmüştü, burada kilitleniyor.
class KapsulDurumuTest {

    private fun harita(vararg olaylar: Triple<String, String, Long>): Map<String, KapsulOturumu> {
        var m = emptyMap<String, KapsulOturumu>()
        for ((sessionId, kind, ms) in olaylar) {
            m = kapsulOlayiUygula(
                m, backend = "claude-app", sessionId = sessionId, kind = kind, zamanMs = ms,
                baslik = "Başlık $sessionId", ozet = "Özet $sessionId", backendLabel = "Claude",
            )
        }
        return m
    }

    @Test
    fun onayBekleyenTurdanOnceliklidir() {
        // s1 daha SONRA başladı ama s2 onay bekliyor: kapsül onayı göstermeli
        // ve kart s2'ye bağlanmalı.
        val kapsul = kapsulDurumu(harita(
            Triple("s2", "attention", 1_000L),
            Triple("s1", "started", 9_000L),
        ))!!
        assertTrue(kapsul.onay)
        assertEquals("onay", kapsul.kisaMetin)
        assertEquals("s2", kapsul.sessionId)
        assertEquals("Başlık s2", kapsul.baslik)
        assertEquals("Özet s2", kapsul.ozet)
        assertEquals("Claude", kapsul.backendLabel)
    }

    @Test
    fun turdaKisaMetinYokOnaydaVar() {
        // SONUC2 §2: kapsülün sağ slotu tek. Metin yazılırsa kronometre ezilir.
        // Turda akan sayaç isteniyor → kisaMetin NULL (çağıran setter'ı hiç
        // çağırmaz). Onayda rozet isteniyor → "onay".
        val tur = kapsulDurumu(harita(Triple("s1", "started", 1_000L)))!!
        assertNull(tur.kisaMetin)
        assertEquals(1, tur.sayi)
        assertEquals(1_000L, tur.baslangicMs)
        val onay = kapsulDurumu(harita(Triple("s1", "attention", 1_000L)))!!
        assertEquals("onay", onay.kisaMetin)
    }

    @Test
    fun ayniDurumdakiOturumlarSayilir() {
        val turlar = kapsulDurumu(harita(
            Triple("s1", "started", 1_000L),
            Triple("s2", "started", 2_000L),
            Triple("s3", "started", 3_000L),
        ))!!
        // Çoklu turda da kısa metin yok: sayı gölgedeki başlığa yazılır.
        assertNull(turlar.kisaMetin)
        assertEquals(3, turlar.sayi)
        assertEquals("Ajan turu · 3 oturum", kapsulBasligi(turlar.onay, turlar.sayi))
        // En son başlayan tur öncelikli.
        assertEquals("s3", turlar.sessionId)

        // Sayaç, kapsülün GÖSTERDİĞİ durumdaki oturumları sayar: iki onay + bir
        // tur varken "onay 2" (toplam 3 değil).
        val karisik = kapsulDurumu(harita(
            Triple("s1", "started", 1_000L),
            Triple("s2", "attention", 2_000L),
            Triple("s3", "attention", 5_000L),
        ))!!
        assertEquals("onay 2", karisik.kisaMetin)
        assertEquals(2, karisik.sayi)
        // Birden çok onay varsa EN SON onay isteyen kartı sahiplenir.
        assertEquals("s3", karisik.sessionId)
    }

    @Test
    fun turBitinceKapsulKapanir() {
        val bitti = kapsulOlayiUygula(
            harita(Triple("s1", "started", 1_000L)),
            backend = "claude-app", sessionId = "s1", kind = "completed", zamanMs = 2_000L,
        )
        assertTrue(bitti.isEmpty())
        assertNull(kapsulDurumu(bitti))
        assertNull(kapsulDurumu(emptyMap()))
    }

    @Test
    fun oncelikliOturumBitinceKapsulKalanaDuser() {
        val kalan = kapsulOlayiUygula(
            harita(
                Triple("s1", "started", 1_000L),
                Triple("s2", "attention", 2_000L),
            ),
            backend = "claude-app", sessionId = "s2", kind = "failed", zamanMs = 3_000L,
        )
        val kapsul = kapsulDurumu(kalan)!!
        assertNull(kapsul.kisaMetin)
        assertEquals(false, kapsul.onay)
        assertEquals("s1", kapsul.sessionId)
    }

    @Test
    fun bilinmeyenKindHaritayiDegistirmez() {
        // Eski APK / yeni köprü (ve tersi) uyumsuzluğunda sessizce düşmeli.
        val once = harita(Triple("s1", "started", 1_000L))
        val sonra = kapsulOlayiUygula(
            once, backend = "claude-app", sessionId = "s1", kind = "reminder", zamanMs = 9_000L,
        )
        assertSame(once, sonra)
        assertEquals(1_000L, sonra.getValue("claude-app:s1").baslangicMs)
        // Oturumsuz olay da kapsülü bozmamalı (not/sistem bildirimleri).
        assertSame(once, kapsulOlayiUygula(
            once, backend = "claude-app", sessionId = "", kind = "started", zamanMs = 9_000L,
        ))
    }

    @Test
    fun onaycozulunceAyniOturumTuraDoner() {
        // Köprüde "onay çözüldü" diye ayrı olay yok: `waiting → running` geçişi
        // `started` üretiyor (operations.mjs:149). Aynı oturumda attention'dan
        // sonra gelen started ONAY'ı TUR'a çevirmeli, ikinci bir kart açmamalı.
        val onay = harita(Triple("s1", "attention", 1_000L))
        assertTrue(kapsulDurumu(onay)!!.onay)
        val tur = kapsulOlayiUygula(
            onay, backend = "claude-app", sessionId = "s1", kind = "started", zamanMs = 4_000L,
        )
        assertEquals(1, tur.size)
        val kapsul = kapsulDurumu(tur)!!
        assertEquals(false, kapsul.onay)
        assertNull(kapsul.kisaMetin)
        // Chronometer yeni turun başından saymalı.
        assertEquals(4_000L, kapsul.baslangicMs)
        // Başlık boş `started` olayıyla silinmemeli.
        assertEquals("Başlık s1", kapsul.baslik)
    }

    @Test
    fun farkliBackendlerAyniOturumKimligindeCakismaz() {
        val m = kapsulOlayiUygula(
            harita(Triple("s1", "started", 1_000L)),
            backend = "codex-app", sessionId = "s1", kind = "attention", zamanMs = 2_000L,
            backendLabel = "Codex",
        )
        assertEquals(2, m.size)
        val kapsul = kapsulDurumu(m)!!
        assertEquals("onay", kapsul.kisaMetin)
        assertEquals("codex-app", kapsul.backend)
    }

    @Test
    fun kapsulBasligiOturumSayisiniTasir() {
        // Kapsülde yalnız ikon + sağ slot görünüyor (SONUC.md:88-91); oturum
        // sayısı gölgedeki karta düşüyor.
        assertEquals("Ajan turu", kapsulBasligi(onay = false, sayi = 1))
        assertEquals("Ajan turu · 2 oturum", kapsulBasligi(onay = false, sayi = 2))
        assertEquals("Onay bekliyor", kapsulBasligi(onay = true, sayi = 1))
        assertEquals("Onay bekliyor · 3 oturum", kapsulBasligi(onay = true, sayi = 3))
    }

    @Test
    fun kapsulZamaniGuvenilmeyenDamgayiReddeder() {
        val simdi = 1_800_000_000_000L
        val iyi = simdi - 90_000L
        val iyiIso = java.time.Instant.ofEpochMilli(iyi).toString()
        assertEquals(iyi, kapsulZamani(iyiIso, simdi))
        // Boş / bozuk / gelecekten / 1 günden eski → "şimdi" (Faz 8 saat kayması).
        assertEquals(simdi, kapsulZamani("", simdi))
        assertEquals(simdi, kapsulZamani("   ", simdi))
        assertEquals(simdi, kapsulZamani("dun aksam", simdi))
        assertEquals(simdi, kapsulZamani("1789581654361", simdi))
        assertEquals(simdi, kapsulZamani(
            java.time.Instant.ofEpochMilli(simdi + 10L * 60 * 1000).toString(), simdi,
        ))
        assertEquals(simdi, kapsulZamani(
            java.time.Instant.ofEpochMilli(simdi - 48L * 60 * 60 * 1000).toString(), simdi,
        ))
    }

    @Test
    fun kapsulZamaniTekMilisaniyeIleriyiBileReddeder() {
        // `startedAt` turun BAŞLADIĞI andır; geleceğe düşmesi tanım gereği
        // yanlış. Pratikte de kural: köprüyü koşturan bilgisayarın saati
        // telefonunkinden ~1,8 sn ileride (16.09.2026 ölçümü), yani damga
        // telefonda neredeyse her zaman gelecekten görünüyor — tolerans SIFIR.
        val simdi = 1_800_000_000_000L
        assertEquals(simdi, kapsulZamani(java.time.Instant.ofEpochMilli(simdi + 1).toString(), simdi))
        assertEquals(simdi, kapsulZamani(java.time.Instant.ofEpochMilli(simdi + 1_500).toString(), simdi))
        // Tam "şimdi" ve geçmiş kabul edilir.
        assertEquals(simdi, kapsulZamani(java.time.Instant.ofEpochMilli(simdi).toString(), simdi))
        val geride = simdi - 1
        assertEquals(geride, kapsulZamani(java.time.Instant.ofEpochMilli(geride).toString(), simdi))
    }

    // Kapsüldeki onay tuşu, onayın kimliğini taşır; tur ve yeni onay eski
    // kimliği silmeli — yoksa bayat kapsül yeni isteği onaylardı.
    @Test
    fun onayKimligiYalnizBekleyenOnaydaTasinir() {
        var m = kapsulOlayiUygula(emptyMap(), "omp", "s1", "attention", 1_000L, requestId = "r1")
        assertEquals("r1", kapsulDurumu(m)!!.requestId)

        m = kapsulOlayiUygula(m, "omp", "s1", "started", 2_000L)
        assertEquals("", m.values.single().requestId)
        assertEquals("", kapsulDurumu(m)!!.requestId)

        m = kapsulOlayiUygula(m, "omp", "s1", "attention", 3_000L, requestId = "r2")
        assertEquals("r2", kapsulDurumu(m)!!.requestId)

        // Kimliksiz yeni attention (eski köprü) önceki kimliği MİRAS ALMAZ.
        m = kapsulOlayiUygula(m, "omp", "s1", "attention", 4_000L)
        assertEquals("", kapsulDurumu(m)!!.requestId)
    }
}
