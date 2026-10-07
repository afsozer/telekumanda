package com.agent.bridge

import java.nio.file.Path
import java.nio.file.Paths

// Dışarıdan gelen (VIEW/SEND) URI'lerin süzgeci. MainActivity dışa açık; başka
// bir uygulama ona "şu dosyayı aç/paylaş" diyerek BİZİM özel dosyalarımızı
// (settings.xml'deki token, cihaz anahtarı, önbellek) okutup köprüye yükletebilir
// ya da düzenleyicide açtırabilirdi. Karar burada saf fonksiyon olarak duruyor ki
// birim testiyle kilitlensin; Android tarafı yalnız yolları ve sağlayıcıyı verir.

enum class GelenUriKarari {
    KABUL,
    /** `file://`: hedef yolu gönderen seçer, bizim özel dizinimizi gösterebilir. */
    RED_DOSYA_SEMASI,
    /** Kendi FileProvider'ımız: dışarıdan gelmesi, kendi dosyamızı okutma girişimidir. */
    RED_KENDI_SAGLAYICI,
    /** content dışında her şey (şemasız, http, android.resource…). */
    RED_DESTEKLENMEYEN_SEMA,
    /** Çözülen gerçek yol uygulamanın özel dizinlerinden birinin altında. */
    RED_OZEL_DIZIN,
}

/**
 * URI'nin şeması ve sağlayıcısına göre ilk karar. `kendiSaglayici`
 * `${packageName}.fileprovider` — Lite'ta paket adı farklı olduğu için çağıran
 * çalışma anındaki paket adından kurar.
 */
fun gelenUriKarari(sema: String?, saglayici: String?, kendiSaglayici: String): GelenUriKarari = when {
    sema.equals("file", ignoreCase = true) -> GelenUriKarari.RED_DOSYA_SEMASI
    !sema.equals("content", ignoreCase = true) -> GelenUriKarari.RED_DESTEKLENMEYEN_SEMA
    saglayici.equals(kendiSaglayici, ignoreCase = true) -> GelenUriKarari.RED_KENDI_SAGLAYICI
    else -> GelenUriKarari.KABUL
}

/**
 * `yol` (sağlayıcının `_data` sütunundan ya da başka yoldan çözülmüş gerçek
 * dosya yolu) özel köklerden birinin altında mı?
 *
 * Çağıran yolları `File.canonicalPath` ile verir (sembolik bağlar çözülmüş:
 * `/data/data/<paket>` ile `/data/user/0/<paket>` aynı yere iner). Burada ayrıca
 * `..` normalize edilir ve karşılaştırma bileşen bazındadır: `/x/com.agent.bridge`
 * kökü `/x/com.agent.bridge.lite/...` yolunu KAPSAMAZ.
 */
fun yolOzelDizinAltinda(yol: String, ozelKokler: Collection<String>): Boolean {
    val hedef = normalYol(yol) ?: return false
    return ozelKokler.any { kok ->
        val k = normalYol(kok) ?: return@any false
        hedef.startsWith(k)
    }
}

private fun normalYol(yol: String): Path? {
    if (yol.isBlank()) return null
    return runCatching { Paths.get(yol).toAbsolutePath().normalize() }.getOrNull()
}

/** Ret durumunda kullanıcıya gösterilen kısa metin. */
fun gelenUriRetMesaji(karar: GelenUriKarari): String = when (karar) {
    GelenUriKarari.KABUL -> ""
    GelenUriKarari.RED_DOSYA_SEMASI -> "Dosya yolu ile gönderilen dosya açılmıyor; paylaşım menüsünden gönder"
    GelenUriKarari.RED_KENDI_SAGLAYICI,
    GelenUriKarari.RED_OZEL_DIZIN -> "Uygulamanın kendi özel dosyaları dışarıdan açılamaz"
    GelenUriKarari.RED_DESTEKLENMEYEN_SEMA -> "Bu tür bağlantı açılamıyor"
}
