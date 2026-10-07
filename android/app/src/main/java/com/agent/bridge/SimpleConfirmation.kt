package com.agent.bridge

/**
 * Eski mesaj kanalı hata ve başarıyı String olarak birlikte taşıyor.
 * Yalnız bilinen başarı biçimlerini Toast'a indir; tanınmayan mesajların
 * ayrıntı/kopyalama erişimi kalsın. Özellikle kısmi başarıyı yutma.
 */
internal fun isSimpleConfirmation(text: String): Boolean =
    text in simpleConfirmations ||
        confirmationPrefixes.any { text.startsWith(it) } ||
        countedConfirmation.matches(text)

private val simpleConfirmations = setOf(
    "Oturum silindi", "Silindi", "Kaydedildi", "Kopyalandı", "Taşındı",
    "Oturum kimliği kopyalandı", "Yol kopyalandı", "Proje silindi",
    "Güncel sürümdesiniz", "AI önerisi kaydedildi",
    "Yazı türevi güncellendi", "Yazı türevi oluşturuldu",
)

private val confirmationPrefixes = listOf(
    "Kaydedildi: ", "PC'ye kaydedildi: ", "Telefona kaydedildi: ",
    "PC'ye kopyalandı: ", "Not silindi: ",
    "Not oluşturuldu: ", "Not projeye bağlandı: ", "Not yeniden adlandırıldı: ",
    "Yeniden adlandırıldı: ", "Taşındı: ", "Yüklendi: ", "İndirildi: ", "Eklendi: ",
)

private val countedConfirmation = Regex(
    "(?:[0-9]+ (?:not|öğe) silindi|[0-9]+ dosya eklendi|Proje ve [0-9]+ oturum geçmişi silindi)"
)
