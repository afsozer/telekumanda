package com.agent.bridge

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HatirlaticiTest {
    private val simdi = 1_000_000_000L
    private val dk = 60_000L

    private fun k(id: String, at: Long) = HatirlaticiKaydi(id, "Başlık $id", "özet", at)

    @Test
    fun `gelecektekiler kurulur, gosterilmisler atlanir`() {
        val a = k("a", simdi + 10 * dk)
        val b = k("b", simdi + 20 * dk)
        val plan = hatirlaticiPlani(listOf(a, b), kurulu = emptySet(), gosterilmis = setOf(b.anahtar), simdi = simdi, baslangic = 0)
        assertEquals(listOf(a), plan.kurulacak)
        assertTrue(plan.gosterilecek.isEmpty())
    }

    @Test
    fun `kacmis olan baslangictan sonraysa ve pencere icindeyse gosterilir`() {
        val kacmis = k("k", simdi - 30 * dk)
        val baslangictanOnce = k("o", simdi - 120 * dk)
        val cokEski = k("e", simdi - KACMIS_HATIRLATICI_PENCERESI_MS - dk)
        val plan = hatirlaticiPlani(
            listOf(kacmis, baslangictanOnce, cokEski),
            kurulu = emptySet(), gosterilmis = emptySet(), simdi = simdi, baslangic = simdi - 60 * dk,
        )
        assertEquals(listOf(kacmis), plan.gosterilecek)
        assertTrue(plan.kurulacak.isEmpty())
    }

    @Test
    fun `listeden cikan ya da zamani degisen kurulu alarm iptal edilir`() {
        val eski = k("a", simdi + 10 * dk)
        val ertelenmis = k("a", simdi + 60 * dk)
        val kalan = k("b", simdi + 5 * dk)
        val plan = hatirlaticiPlani(
            listOf(ertelenmis, kalan),
            kurulu = setOf(eski.anahtar, kalan.anahtar, "silinmis@1"),
            gosterilmis = emptySet(), simdi = simdi, baslangic = 0,
        )
        assertEquals(setOf(eski.anahtar, "silinmis@1"), plan.iptal)
        assertEquals(listOf(ertelenmis, kalan), plan.kurulacak)
    }

    @Test
    fun `json ayristirma bozuk kayitlari atar ve geri yazilabilir`() {
        val arr = JSONArray("""[{"noteId":"_genel-notlar/x","title":"Duruşma","summary":"3. kat","at":1700000000000},{"noteId":"","at":5},{"noteId":"y"},null]""")
        val liste = parseHatirlaticilar(arr)
        assertEquals(listOf(HatirlaticiKaydi("_genel-notlar/x", "Duruşma", "3. kat", 1_700_000_000_000L)), liste)
        assertEquals(liste, parseHatirlaticilar(liste.toJsonArray()))
        assertEquals(emptyList<HatirlaticiKaydi>(), parseHatirlaticilar(null))
    }
}
