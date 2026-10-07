package com.agent.bridge.ui3.chat

import com.agent.bridge.ChatDisplayRow
import com.agent.bridge.ChatMessage
import com.agent.bridge.ui2.chat.chatRowKey

/**
 * Sohbette okunan yerin çapası — ui3 yerleşimi için.
 *
 * Fikir ui2'den (kullanıcı isteği 05.08.2026: "yan sessiondan veya merkezden
 * geri geldiğimde kaydırdığım yeri hatırlasın"): ham lazy index saklanmaz,
 * çünkü sen başka yere bakarken ajan üç mesaj daha yazarsa bütün index'ler
 * kayar ve eski index bambaşka bir satırı gösterir. Saklanan şey satırın
 * KİMLİĞİ; dönüşte kimlik yeniden index'e çevrilir. Bellek ([ChatScrollMemory])
 * ve kimlik kuralı ([chatRowKey]) ui2 ile ORTAK — kopyalanmadı.
 *
 * KOPYALANAMAYAN kısım index matematiğiydi ve bu dosya onun için var. ui2'nin
 * listesi `reverseLayout`: index 0 = EN YENİ mesaj, mesaj satırlarından önce
 * dip kartları (onay/soru/plan/bekleme) emit ediliyor ve `tailExtra` ile geri
 * sayılıyor. ui3'ün listesi düz akıyor: en eski yukarıda, başta yalnız
 * "daha eskiyi göster" tuşu olabiliyor ([basOfset] 0 ya da 1). Aynı fonksiyonu
 * iki yerleşime birden uydurmak ikisini de okunmaz yapardı.
 */
fun ui3CapaSatirId(
    mesajlar: List<ChatMessage>,
    satirlar: List<ChatDisplayRow>,
    lazyIndeks: Int,
    basOfset: Int,
): String? {
    val yer = lazyIndeks - basOfset
    if (yer < 0 || yer >= satirlar.size) return null
    val mesajIndeksi = when (val satir = satirlar[yer]) {
        is ChatDisplayRow.Single -> satir.index
        // Araç grubunda ilk üye çapa olur: grup açılıp kapansa da o kimlik
        // listede kalıyor.
        is ChatDisplayRow.ToolGroup -> satir.indices.first()
    }
    return chatRowKey(mesajlar, mesajIndeksi).takeIf { it.isNotBlank() }
}

/** Çapa kimliğinden lazy index; satır artık listede yoksa null. */
fun ui3LazyIndeks(
    mesajlar: List<ChatMessage>,
    satirlar: List<ChatDisplayRow>,
    satirId: String,
    basOfset: Int,
): Int? {
    if (satirId.isBlank()) return null
    val mesajIndeksi = mesajlar.indices.firstOrNull { chatRowKey(mesajlar, it) == satirId }
        ?: return null
    val yer = satirlar.indexOfFirst { satir ->
        when (satir) {
            is ChatDisplayRow.Single -> satir.index == mesajIndeksi
            is ChatDisplayRow.ToolGroup -> mesajIndeksi in satir.indices
        }
    }
    if (yer < 0) return null
    return yer + basOfset
}

/**
 * Lazy öğe anahtarı — ui2'nin `ChatRootScreen` içindeki kuralının aynısı.
 *
 * `chatRowKey`den AYRI durmasının sebebi var: o fonksiyon ÇAPA kimliği
 * üretiyor ve grup satırı için ilk üyenin ham kimliğini veriyor. Lazy anahtarı
 * ise gruba "grp_" öneki koymak zorunda — aksi halde tek bir mesaj hem kendi
 * satırı hem grubun ilk üyesi olarak aynı anahtarı üretebilir ve LazyColumn
 * yinelenen anahtarla patlar.
 */
internal fun ui3SatirAnahtari(
    mesajlar: List<ChatMessage>,
    satirlar: List<ChatDisplayRow>,
    yer: Int,
): Any = when (val satir = satirlar[yer]) {
    is ChatDisplayRow.Single -> {
        val kimlik = mesajlar.getOrNull(satir.index)?.rowId.orEmpty()
        if (kimlik.isNotEmpty()) kimlik else "idx_${satir.index}"
    }
    is ChatDisplayRow.ToolGroup -> {
        val ilk = satir.indices.first()
        "grp_" + mesajlar.getOrNull(ilk)?.rowId.orEmpty().ifEmpty { "idx_$ilk" }
    }
}
