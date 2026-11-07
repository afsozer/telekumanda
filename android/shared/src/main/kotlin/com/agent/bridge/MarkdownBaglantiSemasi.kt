package com.agent.bridge

/**
 * Markdown'daki dosyaya benzemeyen bir bağlantının dışarıda açılacak hedefi;
 * açılmaması gerekiyorsa null.
 *
 * Model çıktısı ve köprüden gelen belgeler güvenilir içerik değil: `intent:`,
 * `content:`, `file:`, `javascript:` ya da başka bir uygulamanın özel şeması
 * `ACTION_VIEW` ile açılınca telefonda istenmeyen bir uygulamayı ya da derin
 * bağlantıyı tetikleyebilir. Yalnız tarayıcı, e-posta ve telefon şemaları
 * açılır. Şemasız `www.…` tarayıcıya https ile gider (ACTION_VIEW'da şemasız
 * bağlantıya eşleşen uygulama çıkmıyor).
 */
fun markdownBaglantiHedefi(link: String): String? {
    val temiz = link.trim()
    if (temiz.isEmpty()) return null
    if (temiz.startsWith("www.", ignoreCase = true)) return "https://$temiz"
    val sema = temiz.substringBefore(':', missingDelimiterValue = "").lowercase()
    return temiz.takeIf { sema in ACILABILIR_SEMALAR }
}

private val ACILABILIR_SEMALAR = setOf("http", "https", "mailto", "tel")

/** Açılmayan bağlantıda gösterilen kısa uyarı. */
const val ACILMAYAN_BAGLANTI_MESAJI = "Bu bağlantı türü güvenlik nedeniyle açılmıyor"
