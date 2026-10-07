package com.agent.bridge.ui3.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Yama satırlarının renklendirme kararı.
 *
 * Sınıflandırma ayrı bir saf fonksiyon çünkü tam burada sessiz bir tuzak var:
 * birleşik diff'in dosya başlıkları `---` ve `+++` ile başlıyor, yani ham
 * "artıyla başlıyorsa ekleme" kuralı başlık satırlarını yeşile/kırmızıya boyar
 * ve yama "iki satır silinip iki satır eklenmiş" gibi görünür. Test o sırayı
 * kilitliyor.
 *
 * İki yama biçimi de sınanıyor (ikisi de canlı ölçüldü, opencode serve
 * 1.18.21): oturum turlarının diff'i jsdiff biçiminde geliyor (`Index: dosya`
 * + `===` çizgisi), git tarafı `diff --git` biçiminde.
 */
class Ui3DegisikliklerTest {

    @Test fun eklemeVeSilmeSatirlariAyrilir() {
        assertEquals(Ui3PatchSatiri.Ekleme, ui3PatchSatiriTipi("+yeni satır"))
        assertEquals(Ui3PatchSatiri.Silme, ui3PatchSatiriTipi("-eski satır"))
        assertEquals(Ui3PatchSatiri.Baglam, ui3PatchSatiriTipi(" değişmeyen"))
        assertEquals(Ui3PatchSatiri.Baglam, ui3PatchSatiriTipi(""))
    }

    @Test fun hunkBasligiAyriRenkAlir() {
        assertEquals(Ui3PatchSatiri.Hunk, ui3PatchSatiriTipi("@@ -1,3 +1,4 @@"))
        assertEquals(Ui3PatchSatiri.Hunk, ui3PatchSatiriTipi("@@ -1 +1 @@ fun x()"))
    }

    // ASIL TUZAK: başlıklar +/− ile başlıyor ama gövde DEĞİL.
    @Test fun dosyaBasliklariEklemeSilmeSayilmaz() {
        assertEquals(Ui3PatchSatiri.Bilgi, ui3PatchSatiriTipi("--- a/src/a.ts"))
        assertEquals(Ui3PatchSatiri.Bilgi, ui3PatchSatiriTipi("+++ b/src/a.ts"))
        // jsdiff biçimi: yolun ardından sekme geliyor, `a/` öneki yok.
        assertEquals(Ui3PatchSatiri.Bilgi, ui3PatchSatiriTipi("--- c.txt\t"))
        assertEquals(Ui3PatchSatiri.Bilgi, ui3PatchSatiriTipi("+++ c.txt\t"))
    }

    @Test fun herIkiYamaBicimininCercevesiSoluk() {
        // jsdiff (oturum turu diff'i)
        assertEquals(Ui3PatchSatiri.Bilgi, ui3PatchSatiriTipi("Index: c.txt"))
        assertEquals(Ui3PatchSatiri.Bilgi, ui3PatchSatiriTipi("========================="))
        // git
        assertEquals(Ui3PatchSatiri.Bilgi, ui3PatchSatiriTipi("diff --git a/x b/x"))
        assertEquals(Ui3PatchSatiri.Bilgi, ui3PatchSatiriTipi("index 0000000..0637a08"))
        assertEquals(Ui3PatchSatiri.Bilgi, ui3PatchSatiriTipi("new file mode 100644"))
        assertEquals(Ui3PatchSatiri.Bilgi, ui3PatchSatiriTipi("deleted file mode 100644"))
        assertEquals(Ui3PatchSatiri.Bilgi, ui3PatchSatiriTipi("rename from a.ts"))
        // git'in "son satırda yeni satır yok" notu da gövde değil.
        assertEquals(Ui3PatchSatiri.Bilgi, ui3PatchSatiriTipi("\\ No newline at end of file"))
    }

    // BOŞ LİSTE İKİ FARKLI ŞEY OLABİLİR. "Bu oturum dosya değiştirmedi" bir
    // iddia; köprü listenin başının eksik olduğunu söylüyorsa (codex'te geçmişi
    // rollout'tan kurulan oturum) o iddia YANLIŞ ve kullanıcı incelemesini
    // yanlış temele oturtur.
    @Test fun bosMetniGecmisBoslugunuGizlemez() {
        assertEquals("Bu oturum dosya değiştirmedi.", ui3DegisiklikBosMetni(false))
        val bosluklu = ui3DegisiklikBosMetni(true)
        assertNotEquals("Bu oturum dosya değiştirmedi.", bosluklu)
        assertTrue(bosluklu.contains("okunamıyor"))
    }

    // Harf + renk BİRLİKTE anlamı taşır: renk tek başına bırakılırsa renk körü
    // kullanıcı eklenen ile silinen dosyayı ayırt edemez.
    @Test fun durumHarfiKopruDegerleriniKarsilar() {
        assertEquals("E", ui3DiffDurumHarfi("added"))
        assertEquals("S", ui3DiffDurumHarfi("deleted"))
        assertEquals("D", ui3DiffDurumHarfi("modified"))
        // Köprü tanımadığı durumu 'modified'a düşürüyor; arayüz yine de
        // bilinmeyende çökmemeli, "değişti" demeli.
        assertEquals("D", ui3DiffDurumHarfi("renamed"))
        assertEquals("D", ui3DiffDurumHarfi(""))
    }
}
