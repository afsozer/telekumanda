package com.agent.bridge

/** Model seçicide çevrimdışı gösterilen, kısa ve kullanıcı odaklı açıklama. */
data class TextModelInfo(
    val description: String,
    val bestFor: String,
    val profile: String,
    val sourceUrl: String? = null,
)

private fun info(
    description: String,
    bestFor: String,
    profile: String,
    sourceUrl: String? = null,
) = TextModelInfo(description, bestFor, profile, sourceUrl)

private val TEXT_MODEL_INFO = mapOf(
    "deepseek-flash" to info(
        "Ham kapasitesi yüksek, hızlı MoE modeli; bu listedeki unrestricted ve yaratıcı kullanım önceliğine uymaz. " +
            "1M token bağlam, ucuz uç: ekonomik reasoning, kodlama ve uzun bağlam işi için. " +
            "Akıl yürütme eforu ayarlanabilir (high / max).",
        "Ekonomik reasoning, kodlama, uzun bağlam",
        "Yüksek kapasite · kontrollü · 1M bağlam",
        "https://huggingface.co/deepseek-ai/deepseek-flash",
    ),
    // v4-pro EKSIKTI: liste basina sabitlenmis iki modelden biri olmasina ragmen
    // bilgi karti bostu (flash vardi, pro yoktu). Veriler models.dev kaydindan.
    "deepseek-v4-pro" to info(
        "DeepSeek'in açık MoE amiral gemisi; 1M token bağlamla kodlama ve uzun ajan koşuları için. " +
            "Flash ile aynı bağlam ve efor ayarına (high / max) sahip ama token başına ~3 kat pahalı.",
        "Zor kodlama ve uzun ajan görevleri",
        "Amiral gemisi · 1M bağlam · pahalı",
        "https://huggingface.co/deepseek-ai/DeepSeek-V4-Pro",
    ),
)


/**
 * Model kimliğinden katalog anahtarını çıkarır. Kimlik biçimi backend'e göre
 * değişiyor: OpenCode `<saglayici>/<model>`, Codex düz `<model>`, bazı
 * istemciler `custom:<saglayici>:<model>`.
 */
private fun normalizedTextModelName(id: String, label: String): String = when {
    id.startsWith("custom:", ignoreCase = true) ->
        id.substringAfterLast(':').ifBlank { id }
    id.contains('/') -> id.substringAfterLast('/')
    id.isNotBlank() -> id
    else -> label.substringAfter('·', label).trim()
}

fun textModelInfo(id: String, label: String): TextModelInfo? =
    TEXT_MODEL_INFO[normalizedTextModelName(id, label).lowercase()]

