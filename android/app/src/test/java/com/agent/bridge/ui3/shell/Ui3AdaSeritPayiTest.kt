package com.agent.bridge.ui3.shell

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ada ile sekme çubuğunun "＋" tuşunun ÇAKIŞMAMASI.
 *
 * Geniş yerleşimde ikisi aynı dikey bantta: ada ekranın sağ ucunda, "＋" okuma
 * sütununun sağ ucunda. Ada salt gösterge iken üst üste binmeleri yalnız
 * görüntü kusuruydu; ada dokunulabilir olunca "＋"ın dokunuşunu yer. Bu testler
 * o payı ölçüyor — sayılar cihazın gerçek genişliklerinden (Tab S10+) ve
 * yerleşim sabitlerinden geliyor.
 */
class Ui3AdaSeritPayiTest {
    private fun esit(beklenen: Float, olculen: androidx.compose.ui.unit.Dp) =
        assertEquals(beklenen, olculen.value, 0.01f)

    // Dar ekranda ada sistem durum çubuğunun İÇİNDE; sekme çubuğuyla hiç
    // kesişmiyor ve çubuktan yer çalmak sebepsiz kayıp olurdu.
    @Test fun darEkrandaPayYok() {
        esit(0f, ui3AdaSeritPayi(genis = false, ekranGenisligi = 365.7.dp, icerikSolBosluk = 0.dp))
    }

    // EN DAR "geniş" (840dp): rail 116 gidince içeriğe 724 kalıyor, sütun 720 —
    // sağda yalnız 2dp boşluk var, yani "＋" tam adanın altında. Neredeyse
    // payın tamamı gerekiyor.
    @Test fun sutunEkraniDoldurdugundaTamPayVerilir() {
        val pay = ui3AdaSeritPayi(genis = true, ekranGenisligi = 840.dp, icerikSolBosluk = 116.dp)
        esit(130f, pay)
    }

    // Tab S10+ DİKEY (876dp, tek panel): içerik 760, sütun 720 → sağda 20dp.
    // Ada 132dp yer istiyor, 112'si eksik.
    @Test fun tabletDikeydeEksikPayKapatilir() {
        esit(112f, ui3AdaSeritPayi(genis = true, ekranGenisligi = 876.dp, icerikSolBosluk = 116.dp))
    }

    // Tab S10+ YATAY (1400dp, iki panel): içerik 1400-116-332 = 952, sütun 720
    // → sağda 116dp. Ada 132 istiyor, 16'sı eksik: çubuk neredeyse hiç
    // kırpılmıyor. (Bu yerleşim cihazda çalışıyordu; hesap onu bozmamalı.)
    @Test fun tabletYataydaPayNeredeyseSifir() {
        esit(16f, ui3AdaSeritPayi(genis = true, ekranGenisligi = 1400.dp, icerikSolBosluk = 448.dp))
    }

    // Çok geniş ekranda sütun zaten yeterince içeride: pay 0, hiçbir şey
    // değişmiyor. Aksi halde her tablette "＋"ın solunda sebepsiz bir boşluk
    // açılırdı.
    @Test fun cokGenisEkrandaPayHicVerilmez() {
        esit(0f, ui3AdaSeritPayi(genis = true, ekranGenisligi = 1600.dp, icerikSolBosluk = 116.dp))
    }
}
