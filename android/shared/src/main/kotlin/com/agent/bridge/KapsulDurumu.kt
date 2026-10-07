package com.agent.bridge

// Kapsül (Android 16 Live Updates) durumunun SAF türetmesi.
// Android bağımlılığı yok; bildirim kurulumu `KapsulDenetleyici` işidir, burası
// yalnız "hangi oturumlar hangi durumda → kapsül ne göstermeli" sorusunu
// cevaplar. Ayrılmasının nedeni Faz 8 dersi (`docs/ui3-liquid-glass-plani.md:
// 1192-1196`): durum BİRİKTİRİLMEZ, oturum haritasından TÜRETİLİR — biriken
// sayaç bir push kaçınca sonsuza kadar yanlış kalıyordu.

enum class OturumDurumu { TUR, ONAY }

// Tek bir oturumun kapsüle yansıyan hâli. `baslangicMs` durumun BAŞLADIĞI an
// (tur için turun başı, onay için onayın istendiği an) — kapsülün chronometer'ı
// ve "en son olan" seçimi buna bakar.
data class KapsulOturumu(
    val backend: String,
    val sessionId: String,
    val durum: OturumDurumu,
    val baslangicMs: Long,
    val baslik: String = "",
    val ozet: String = "",
    val backendLabel: String = "",
    // ONAY'da bekleyen onayın kimliği (attention push'u); TUR'da boş. Kapsüldeki
    // "İzin ver"/"Reddet" bunu taşır ki bayat kapsül yeni isteği onaylamasın.
    val requestId: String = "",
)

// Kapsülün tek karesi. `null` dönmesi "bildirimi iptal et" demektir.
//
// `kisaMetin` YALNIZ onay durumunda doludur; TUR'da **null**'dır ve çağıran
// `setShortCriticalText`'i HİÇ ÇAĞIRMAMALIDIR (null geçmek yetmez). Gerekçe
// ölçüm: kapsülün sağ alanı tek slot — `shortCriticalText` doluysa o yazılır,
// boşsa `usesChronometer` + `when` devreye girip akan sayaç çizilir; ikisi
// aynı anda olmuyor (`capsule-test/live/SONUC2.md` §2). Turda akan sayaç
// "tur" yazısından çok daha fazlasını söylüyor, onay bekleyen turda ise
// "onay" rozeti sayacı bilinçli olarak eziyor: onay öncelikli.
data class Kapsul(
    val onay: Boolean,
    val kisaMetin: String?,
    val sayi: Int,
    val backend: String,
    val backendLabel: String,
    val sessionId: String,
    val baslik: String,
    val ozet: String,
    val baslangicMs: Long,
    val requestId: String = "",
)

// Kapsül durumu tüm oturumların birleşimidir:
//  - en az bir ONAY varsa kapsül "onay" (Faz 8 kararı: onay bekleyen her zaman
//    öncelikli, `ui3-liquid-glass-plani.md:1130-1131`),
//  - ONAY yoksa en az bir TUR varsa "tur",
//  - hiçbiri yoksa null → bildirim kapatılır.
// Gölgedeki kartın başlığı/özeti ve eylemleri ÖNCELİKLİ OTURUMUNDUR: onay
// bekleyenler arasında en son onay isteyen, yoksa en son başlayan tur.
fun kapsulDurumu(oturumlar: Map<String, KapsulOturumu>): Kapsul? {
    if (oturumlar.isEmpty()) return null
    val onaylar = oturumlar.entries.filter { it.value.durum == OturumDurumu.ONAY }
    val secilenler = if (onaylar.isNotEmpty()) {
        onaylar
    } else {
        oturumlar.entries.filter { it.value.durum == OturumDurumu.TUR }
    }
    if (secilenler.isEmpty()) return null
    // Aynı milisaniyede iki olay gelirse sıralama harita gezinme sırasına
    // kalmasın diye anahtarla da kırılıyor — kapsül metni deterministik olmalı.
    val oncelikli = secilenler.maxWithOrNull(
        compareBy<Map.Entry<String, KapsulOturumu>>({ it.value.baslangicMs }, { it.key }),
    )?.value ?: return null
    val onay = oncelikli.durum == OturumDurumu.ONAY
    val sayi = secilenler.size
    return Kapsul(
        onay = onay,
        kisaMetin = kapsulKisaMetin(onay, sayi),
        sayi = sayi,
        backend = oncelikli.backend,
        backendLabel = oncelikli.backendLabel,
        sessionId = oncelikli.sessionId,
        baslik = oncelikli.baslik,
        ozet = oncelikli.ozet,
        baslangicMs = oncelikli.baslangicMs,
        requestId = if (onay) oncelikli.requestId else "",
    )
}

// `shortCriticalText` 96dp / ~7 karakter sınırına tabi (developer.android.com
// live-update). Tek oturumda sayı YOK: "onay"; birden çok onay bekleyen varsa
// "onay 2".
//
// TUR'da **null**: metin yazılırsa kronometre ezilir (SONUC2 §2), turda ise
// akan sayaç isteniyor. Çağıran null dönünce `setShortCriticalText`'i hiç
// çağırmaz.
fun kapsulKisaMetin(onay: Boolean, sayi: Int): String? {
    if (!onay) return null
    return if (sayi > 1) "onay $sayi" else "onay"
}

// Gölgedeki kartın başlığı (kapsülde GÖRÜNMEZ — kapsül yalnız ikon + sağ
// slotu çiziyor, `SONUC.md:88-91`). Oturum sayısı kapsülden düştüğü için
// buraya yazılıyor: kullanıcı kartı açtığında kaç turun döndüğünü görsün.
fun kapsulBasligi(onay: Boolean, sayi: Int): String {
    val taban = if (onay) "Onay bekliyor" else "Ajan turu"
    return if (sayi > 1) "$taban · $sayi oturum" else taban
}

// `startedAt` ISO 8601 metnini kronometre için ms'ye çevirir (köprü
// `now().toISOString()` gönderiyor, `operations.mjs:98`).
//
// **GELECEKTEKİ damga bir milisaniye bile kabul edilmez.** `startedAt` "turun
// BAŞLADIĞI an"dır; geleceğe düşmesi tanım gereği yanlış ve pratikte KURAL:
// köprüyü koşturan bilgisayarın saati telefonunkinden ~1,8 sn ileride
// (16.09.2026'da `date +%s%3N` ile ölçüldü), yani damga telefona neredeyse her
// zaman gelecekten geliyor. Toleranslı bırakılırsa sayaç negatif/ileri bir
// noktadan başlar. Faz 8'in saat kayması dersinin somut hâli.
//
// (Not: kapsülün bir kez "Zaman aşımı" yazısına düşmesi BU yüzden DEĞİLDİ —
// o ayrı ve açık bir Honor sorusu, `docs/kapsul-live-updates-plani.md` §5.1(b).)
//
// Ayrıca 1 günden eski damga da reddedilir (kuyrukta bekleyip geç teslim edilen
// push kapsülde saatlerce süren sahte bir tur göstermesin) ve boş/bozuk metin
// de. Reddedilen her durumda "şimdi" kullanılır: sayaç sıfırdan başlar,
// yanlış başlamaz.
fun kapsulZamani(startedAt: String, simdiMs: Long): Long {
    if (startedAt.isBlank()) return simdiMs
    val ms = runCatching { java.time.Instant.parse(startedAt.trim()).toEpochMilli() }
        .getOrNull() ?: return simdiMs
    if (ms > simdiMs) return simdiMs
    if (simdiMs - ms > 24L * 60 * 60 * 1000) return simdiMs
    return ms
}

// Push olayını oturum haritasına uygulayan saf geçiş. Yeni harita döner;
// değişiklik yoksa AYNI harita örneği döner (çağıran gereksiz bildirim
// güncellemesi atmasın diye kimlik karşılaştırabilir).
//
//   started   → TUR    (köprüde `waiting → running` de `started` üretir, yani
//                       "onay çözüldü → tura döndü" aynı olaydan gelir)
//   attention → ONAY
//   completed → sil
//   failed    → sil
//   bilinmeyen → değişiklik yok (eski APK/yeni köprü uyumsuzluğunda sessizce düş)
fun kapsulOlayiUygula(
    oturumlar: Map<String, KapsulOturumu>,
    backend: String,
    sessionId: String,
    kind: String,
    zamanMs: Long,
    baslik: String = "",
    ozet: String = "",
    backendLabel: String = "",
    requestId: String = "",
): Map<String, KapsulOturumu> {
    if (sessionId.isBlank()) return oturumlar
    val anahtar = notificationKey(backend, sessionId)
    val durum = when (kind) {
        "started" -> OturumDurumu.TUR
        "attention" -> OturumDurumu.ONAY
        "completed", "failed" -> {
            if (!oturumlar.containsKey(anahtar)) return oturumlar
            return oturumlar - anahtar
        }
        else -> return oturumlar
    }
    // Başlık/özet/etiket boş gelirse önceki değer korunur: `started` olayının
    // özeti bazen boş (`operations.mjs:97`) ve kapsülün gölge kartı o anda
    // elindeki tek metni kaybetmemeli.
    val mevcut = oturumlar[anahtar]
    return oturumlar + (anahtar to KapsulOturumu(
        backend = backend,
        sessionId = sessionId,
        durum = durum,
        baslangicMs = zamanMs,
        baslik = baslik.ifBlank { mevcut?.baslik ?: "" },
        ozet = ozet.ifBlank { mevcut?.ozet ?: "" },
        backendLabel = backendLabel.ifBlank { mevcut?.backendLabel ?: "" },
        // Kimlik ÖNCEKİNDEN KORUNMAZ: yeni attention yeni onaydır, tur ise
        // onayın bittiği demektir. Eski kimliği taşımak tam önlenmek istenen
        // "bayat tuş yeni isteği onaylar" durumunu doğururdu.
        requestId = if (durum == OturumDurumu.ONAY) requestId else "",
    ))
}
