package com.agent.bridge.ui3.chat

import com.agent.bridge.ChatDisplayRow
import com.agent.bridge.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// ui3 listesi DÜZ akıyor (ui2 reverseLayout): en eski yukarıda, başta yalnız
// "daha eskiyi göster" tuşu olabiliyor. Bu testlerin varlık sebebi o fark —
// ui2'nin çapa matematiği buraya olduğu gibi taşınamıyordu.
class Ui3KaydirmaCapasiTest {

    private fun msg(id: String) = ChatMessage(role = "assistant", text = "x", rowId = id)

    private val mesajlar = listOf(msg("a"), msg("b"), msg("c"), msg("d"))
    private val satirlar = listOf(
        ChatDisplayRow.Single(0),
        ChatDisplayRow.Single(1),
        ChatDisplayRow.Single(2),
        ChatDisplayRow.Single(3),
    )

    @Test
    fun gidisDonus() {
        assertEquals("c", ui3CapaSatirId(mesajlar, satirlar, lazyIndeks = 2, basOfset = 0))
        assertEquals(2, ui3LazyIndeks(mesajlar, satirlar, "c", basOfset = 0))
    }

    @Test
    fun eskiTusuIndeksiKaydirir() {
        // "daha eskiyi göster" listenin BAŞINDA: her mesaj satırı bir aşağı iner.
        assertEquals("c", ui3CapaSatirId(mesajlar, satirlar, lazyIndeks = 3, basOfset = 1))
        assertEquals(3, ui3LazyIndeks(mesajlar, satirlar, "c", basOfset = 1))
    }

    @Test
    fun eskiTusununKendisiCapaOlmaz() {
        assertNull(ui3CapaSatirId(mesajlar, satirlar, lazyIndeks = 0, basOfset = 1))
    }

    @Test
    fun oneEskiMesajEklenincaCapaKAYMAZ() {
        // Düz listede yeni mesaj SONA ekleniyor, çapanın index'i zaten sabit
        // kalır. Asıl kayma "daha eskiyi göster" ile ÖNE eklenince olur: ham
        // index yanlış satırı gösterirdi, kimlik doğru satırı bulur.
        val eskiEklendi = listOf(msg("x"), msg("y")) + mesajlar
        val eskiSatirlar = (0 until 6).map { ChatDisplayRow.Single(it) }
        assertEquals(4, ui3LazyIndeks(eskiEklendi, eskiSatirlar, "c", basOfset = 0))
    }

    @Test
    fun silinmisSatirNullDoner() {
        assertNull(ui3LazyIndeks(mesajlar, satirlar, "yok", basOfset = 0))
    }

    @Test
    fun grupSatirindaIlkUyeCapaOlur() {
        val grupSatirlari = listOf(
            ChatDisplayRow.Single(0),
            ChatDisplayRow.ToolGroup(listOf(1, 2)),
            ChatDisplayRow.Single(3),
        )
        assertEquals("b", ui3CapaSatirId(mesajlar, grupSatirlari, lazyIndeks = 1, basOfset = 0))
        // Grubun HERHANGİ bir üyesi aynı satıra çözülür.
        assertEquals(1, ui3LazyIndeks(mesajlar, grupSatirlari, "c", basOfset = 0))
    }

    // ── LAZY ANAHTARI ────────────────────────────────────────────────────
    // 19.08.2026: liste anahtarsızdı, öne mesaj eklenince görünen yer birkaç
    // satır zıplıyordu.

    @Test
    fun anahtarOneEklenincaDegismez() {
        val once = ui3SatirAnahtari(mesajlar, satirlar, 2)
        val yeniMesajlar = listOf(msg("z")) + mesajlar
        val yeniSatirlar = (0..4).map { ChatDisplayRow.Single(it) }
        // "c" artik 3. satirda; anahtari ayni kalmali ki liste yerini korusun.
        assertEquals(once, ui3SatirAnahtari(yeniMesajlar, yeniSatirlar, 3))
    }

    @Test
    fun anahtarlarTekildir() {
        val karisik = listOf(
            ChatDisplayRow.Single(0),
            ChatDisplayRow.ToolGroup(listOf(1, 2)),
            ChatDisplayRow.Single(3),
        )
        val anahtarlar = karisik.indices.map { ui3SatirAnahtari(mesajlar, karisik, it) }
        assertEquals(anahtarlar.size, anahtarlar.toSet().size)
    }

    @Test
    fun kimliksizMesajKronolojikIndekstenAnahtarAlir() {
        val kimliksiz = listOf(ChatMessage(role = "assistant", text = "x"))
        val satir = listOf(ChatDisplayRow.Single(0))
        assertEquals("idx_0", ui3SatirAnahtari(kimliksiz, satir, 0))
    }

    @Test
    fun grupAnahtariIlkUyeyeBaglidir() {
        val grup = listOf(ChatDisplayRow.ToolGroup(listOf(1, 2)))
        assertEquals("grp_b", ui3SatirAnahtari(mesajlar, grup, 0))
        // Gruba yeni adim eklenince anahtar SABIT kalir.
        assertEquals(
            "grp_b",
            ui3SatirAnahtari(mesajlar, listOf(ChatDisplayRow.ToolGroup(listOf(1, 2, 3))), 0),
        )
    }
}
