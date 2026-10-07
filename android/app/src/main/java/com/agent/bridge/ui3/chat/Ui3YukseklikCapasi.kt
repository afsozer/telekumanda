package com.agent.bridge.ui3.chat

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity

/**
 * SATIR YÜKSEKLİĞİ ÇAPASI — yukarı kaydırırken içeriğin atlamasını önler.
 *
 * Markdown gövdeleri `AndroidView` (Markwon TextView) ve yükseklikleri İLK
 * YERLEŞİMDEN SONRA değişiyor. Aynı olgu [Ui3ChatScreen]'de dibe inişte zaten
 * belgeli ("tek seferlik `scrollToItem` yetmiyor"), ama telafisi YALNIZ aşağı
 * yön için yazılmıştı (`dibeYerles` kare kare tekrarlıyor). Yukarı kaydırırken
 * üstten giren satır önce küçük ölçülüp yerleştiriliyor, sonra Markwon tabloyu
 * çizince şişiyor ve altındaki her şey kayıyor — kullanıcı bunu "araya atladı,
 * bölümü es geçti" olarak görüyor (bildirildi 24.08.2026; dört tablolu uzun
 * bir cevapta tetiklendi, transkript sağlamdı yani veri değil çizim sorunu).
 *
 * 19.08.2026'daki kararlı anahtar düzeltmesi BUNU KAPSAMAZ: anahtar "başa satır
 * eklenince indeks kayması"nı çözer, "öğe ölçüldükten sonra boy değiştiriyor"u
 * çözmez. İki ayrı mekanizma.
 *
 * Çözüm: satırın son ölçülen yüksekliğini anahtarına göre sakla; satır yeniden
 * bestelendiğinde ilk birkaç kare boyunca `heightIn(min = …)` ver. Liste daha
 * ilk karede doğru boyu görür, büyüme farkı sıfırlanır.
 *
 * ÇAPA BİLEREK KALICI DEĞİL — [CAPA_KARE] kare sonra bırakılır. Kalıcı olsaydı
 * açılıp kapanan kartlar (araç/düşünce grubu) KAPANAMAZDI: min yükseklik
 * küçülmeyi engeller, küçülme olmayınca `onSizeChanged` yeni değeri hiç yazmaz
 * ve satır sonsuza kadar açık boyda kilitlenirdi.
 */
private const val CAPA_KARE = 3

/**
 * Satır yüksekliklerinin LRU önbelleği. Oturum başına bir tane; uzun sohbette
 * sınırsız büyümesin diye en son dokunulandan geriye [azami] kayıt tutulur
 * (erişim sıralı `LinkedHashMap`).
 */
internal class Ui3YukseklikOnbellegi(
    private val azami: Int = 600,
) : LinkedHashMap<Any, Int>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Int>?): Boolean = size > azami

    fun yaz(anahtar: Any, yukseklik: Int) {
        if (yukseklik > 0) put(anahtar, yukseklik)
    }

    fun oku(anahtar: Any): Int = this[anahtar] ?: 0
}

/**
 * @param anahtar satırın LazyColumn anahtarı ([ui3SatirAnahtari] ile aynı değer)
 * @param onbellek oturum ömürlü yükseklik önbelleği
 * @param etkin akan son satırda `false` verilir: metni her karede uzuyor, çapa
 *   onu eski boyunda tutup titretirdi
 */
@Composable
internal fun Modifier.ui3YukseklikCapasi(
    anahtar: Any,
    onbellek: Ui3YukseklikOnbellegi,
    etkin: Boolean = true,
): Modifier {
    val olculen = Modifier.onSizeChanged { onbellek.yaz(anahtar, it.height) }
    if (!etkin) return this.then(olculen)

    val kayitli = onbellek.oku(anahtar)
    var capaAcik by remember(anahtar) { mutableStateOf(kayitli > 0) }
    if (capaAcik) {
        LaunchedEffect(anahtar) {
            repeat(CAPA_KARE) { withFrameNanos { } }
            capaAcik = false
        }
    }
    val yogunluk = LocalDensity.current
    return this
        .then(
            if (capaAcik && kayitli > 0) Modifier.heightIn(min = with(yogunluk) { kayitli.toDp() })
            else Modifier,
        )
        .then(olculen)
}
