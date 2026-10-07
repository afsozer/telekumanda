package com.agent.bridge

import java.util.Locale

// Android'in bilmediği uzantılar için MIME karşılıkları.
//
// NOT: Bu açıklama bilinçli olarak // ile yazıldı, KDoc bloğuyla değil —
// içinde joker MIME tipi geçiyor ve o dizi blok yorumunu erken kapatıp
// derlemeyi kırıyordu (canlı yaşandı).
//
// Neden gerekli: MimeTypeMap.getSingleton().getMimeTypeFromExtension("udf")
// null döner ve çağıran taraf joker tipe düşer. Joker tiple açılan seçicide
// hiçbir uygulama çıkmıyor, çünkü hedef uygulamalar SOMUT tip kaydediyor —
// telefonda ölçüldü (31 Tem 2026):
//
//   tr.gov.uyap.editor : application/octet-stream, application/zip, application/x-zip
//   com.udfeditor.pro  : application/udf, application/x-udf, application/zip,
//                        application/octet-stream, application/x-zip-compressed, ...
//
// Seçim application/zip: iki uygulamanın VIEW filtresinde de var (kesişim) ve
// semantik olarak da doğru — UDF zaten bir ZIP kabı. application/udf daha dar
// görünüyor ama UYAP Editör onu yalnız SEND filtresinde tanıyor, VIEW'da değil;
// onu seçseydik UYAP Editör listede çıkmazdı. application/octet-stream de
// ikisini yakalar ama "ne olduğu bilinmeyen ikili" demek olduğundan seçiciyi
// alakasız uygulamalarla dolduruyor.
//
// Buraya yeni bir uzantı eklerken tahmin etme: hedef uygulamanın filtresini
// `adb shell dumpsys package <paket>` ile oku, kesişimi al.
private val EXTRA_MIME_TYPES: Map<String, String> = mapOf(
    "udf" to "application/zip",
    // UYAP'ın imzalı/şifreli varyantları da aynı ZIP kabını kullanıyor.
    "udfx" to "application/zip",
)

// [ext] uzantısı için MIME döndürür.
//
// Sıra bilinçli: ÖNCE yukarıdaki liste, sonra Android'in kendi haritası. Bu
// girdiler cihazda ölçülerek seçildi; Android ileride .udf için başka bir tip
// öğrenirse bizim ölçülmüş değerimizin ezilmesini istemiyoruz — çalıştığını
// bildiğimiz tek şey bu.
//
// Uzantı noktasız ve küçük harf beklenir; büyük harf gelirse burada düşürülür.
// lowercase() Locale.ROOT ile çağrılır: tr-TR'de "TIF" gibi I içeren uzantılar
// varsayılan yerelde "tıf" olup eşleşmeyi sessizce bozardı.
fun mimeTypeForExtension(
    ext: String,
    fallback: String = "*/*",
    systemLookup: (String) -> String? = { null },
): String {
    val key = ext.lowercase(Locale.ROOT).removePrefix(".")
    if (key.isBlank()) return fallback
    return EXTRA_MIME_TYPES[key] ?: systemLookup(key) ?: fallback
}
