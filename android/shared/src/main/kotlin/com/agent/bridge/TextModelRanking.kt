package com.agent.bridge

/**
 * Model seçicilerinin sırası.
 *
 * 06.08.2026'da sadeleştirildi. Önceki sürüm bir bulut sağlayıcısının 88
 * modelini elle sıralanmış bir kalite listesiyle diziyor, eski sürümleri ve
 * yinelenen sağlayıcı kayıtlarını eliyordu. O sağlayıcı kaldırılınca listelerin
 * TAMAMI ölü koda döndü: hiçbir model adı artık eşleşmiyordu (ölçüldü — katalog
 * yalnız deepseek, yerel llamacpp/llamaswap ve opencode modellerini taşıyor).
 * Ölü sıralama tablosunu taşımak yerine kural sadeleştirildi.
 *
 * NOT: KDoc içinde yıldızlı desen (eğik çizgi + yıldız) YAZMA — Kotlin blok
 * yorumları iç içe geçtiği için yeni bir yorum açar ve dosyayı kırar.
 *
 * Katalogda sonradan beliren bilinmeyen modeller kaybolmasın diye bilinenlerin
 * ardından alfabetik tutulur — eleme YOK.
 */

/**
 * OpenCode App model seçicisi: günlük iki model en üstte (sabit sırayla),
 * gerisi alfabetik. Yerel modeller de listede kalır.
 */
fun rankOpencodeModels(models: List<BackendModel>): List<BackendModel> =
    models.sortedWith(
        compareBy<BackendModel>(
            { preferredOpencodeRank(it.id) },
            { it.label.lowercase() },
        )
    )

// En üstte istenen iki model. Rank: flash 0, pro 1, gerisi 2.
private fun preferredOpencodeRank(id: String): Int = when (id) {
    "deepseek/deepseek-flash" -> 0
    "deepseek/deepseek-v4-pro" -> 1
    else -> 2
}
