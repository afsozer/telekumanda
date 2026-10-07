package com.agent.bridge.widget

import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Ana ekran kota kartının verisi: köprünün /usage yanıtından yalnız iki kart
// ayıklanır — Claude Code limitleri ve Nano-GPT bakiyesi. Saf Kotlin (android.*
// yok), JVM birim testinde doğrudan sınanır.
//
// Tasarım dili Mac'teki KotaWidget küçük kartlarının birebir karşılığı
// (~/Developer/KotaWidget, Sources/Widget/KotaWidgets.swift ve
// NanoGPTWidget.swift): HER YER "kalan"ı gösterir, halka ve barlar kalan kadar
// dolar; eşikler kalan %25 altı turuncu, %10 altı kırmızı.

/** Bir Claude limit penceresi. `remaining` 0..100 (kalan yüzde). */
data class KotaBucket(
    val id: String,
    val label: String,
    val remaining: Double,
    val resetAt: Instant?,
)

data class KotaClaude(
    val plan: String?,
    /** Halkadaki pencere: 5 saatlik. Köprü yalnız yerel token özetine düştüyse null. */
    val hero: KotaBucket?,
    /** Halkanın altındaki barlar (en çok iki): 7 gün, model bazlı haftalık. */
    val rest: List<KotaBucket>,
    val stale: Boolean,
)

data class KotaNano(
    val usd: Double,
    /** Köprünün kullanım ucu düştüyse null: "0 $" ile "bilinmiyor" ayrı kalsın. */
    val todaySpendUsd: Double?,
    val todayRequests: Int?,
)

data class KotaSnapshot(
    val claude: KotaClaude?,
    val nano: KotaNano?,
    /** Köprünün ölçtüğü an (`updatedAt`); eski köprüde telefonun aldığı an. */
    val measuredAt: Instant,
)

/** Rengin anlamı; gerçek renk açık/koyu temaya göre çizicide seçilir. */
enum class KotaTone { ACCENT, ORANGE, RED, OVERFLOW, MUTED }

object KotaWidgetData {
    /** Mac kartıyla aynı eşik: bu yaştan eski veri başlıkta uyarı üçgeni yakar. */
    val STALE_AFTER: Duration = Duration.ofMinutes(30)

    /** Köprünün bakiye ölçeği (bridge/usage.mjs CREDIT_BAR_FULL_USD). */
    const val CREDIT_FULL_USD = 10.0

    private val trLocale = Locale.forLanguageTag("tr-TR")

    fun parse(json: JSONObject, receivedAt: Instant): KotaSnapshot {
        val groups = json.optJSONArray("groups")
        var claude: KotaClaude? = null
        var nano: KotaNano? = null
        if (groups != null) {
            for (i in 0 until groups.length()) {
                val group = groups.optJSONObject(i) ?: continue
                when (group.optString("source")) {
                    "claude" -> claude = parseClaude(group)
                    "nanogpt" -> nano = parseNano(group)
                }
            }
        }
        val measured = parseInstant(json.optString("updatedAt")) ?: receivedAt
        return KotaSnapshot(claude, nano, measured)
    }

    private fun parseClaude(group: JSONObject): KotaClaude {
        val buckets = buildList {
            val array = group.optJSONArray("buckets") ?: return@buildList
            for (i in 0 until array.length()) {
                val b = array.optJSONObject(i) ?: continue
                val id = b.optString("id")
                // Kredi satırı kota değil; yerel token özeti (claude-local-*)
                // "kalan" taşımıyor (remainingFraction hep 1) — halkaya giremez.
                if (id == "claude-credit" || id.startsWith("claude-local")) continue
                add(
                    KotaBucket(
                        id = id,
                        label = b.optString("label"),
                        remaining = (b.optDouble("remainingFraction", 0.0) * 100).coerceIn(0.0, 100.0),
                        resetAt = parseInstant(b.optString("resetTime")),
                    ),
                )
            }
        }
        val hero = buckets.firstOrNull { it.id == "claude-5h" }
            ?: buckets.firstOrNull { it.label.contains("5 saat") }
        val rest = buckets.filter { it !== hero }.take(2)
        val plan = Regex("Abonelik: ([^•]+)").find(group.optString("description"))
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        return KotaClaude(plan, hero, if (hero == null) emptyList() else rest, group.optBoolean("stale"))
    }

    private fun parseNano(group: JSONObject): KotaNano? {
        val array = group.optJSONArray("buckets") ?: return null
        for (i in 0 until array.length()) {
            val b = array.optJSONObject(i) ?: continue
            if (b.optString("id") != "nanogpt-balance") continue
            val usd = when {
                b.has("creditUsd") && !b.isNull("creditUsd") -> b.optDouble("creditUsd")
                else -> b.optString("value").removePrefix("$").toDoubleOrNull()
            }?.takeIf { it.isFinite() } ?: return null
            val spend = if (b.has("todaySpendUsd")) b.optDouble("todaySpendUsd").takeIf { it.isFinite() } else null
            val requests = if (b.has("todayRequests")) b.optInt("todayRequests") else null
            return KotaNano(usd, spend, requests)
        }
        return null
    }

    /** Köprü iki biçim yolluyor: `…T14:59:59.925739+00:00` (Claude) ve `…Z`. */
    fun parseInstant(raw: String?): Instant? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(text) }.getOrNull()
    }

    fun isStale(snapshot: KotaSnapshot, now: Instant): Boolean =
        Duration.between(snapshot.measuredAt, now) > STALE_AFTER

    // ── Biçim ──────────────────────────────────────────────────────────────

    /** Kalan yüzdenin rengi (kota): %10 altı kırmızı, %25 altı turuncu. */
    fun remainingTone(remaining: Double): KotaTone = when {
        remaining < 10 -> KotaTone.RED
        remaining < 25 -> KotaTone.ORANGE
        else -> KotaTone.ACCENT
    }

    /** 10 $ ölçeğinde doluluk (0..100). */
    fun nanoFill(nano: KotaNano): Double = (nano.usd / CREDIT_FULL_USD * 100).coerceIn(0.0, 100.0)

    fun nanoOverflow(nano: KotaNano): Boolean = nano.usd > CREDIT_FULL_USD

    /** Bakiye rengi: 10 $ üstü gök mavisi, ölçeğin %10'u altı kırmızı, %25'i altı turuncu. */
    fun nanoTone(nano: KotaNano): KotaTone = when {
        nanoOverflow(nano) -> KotaTone.OVERFLOW
        else -> remainingTone(nanoFill(nano))
    }

    /** Türkçe ondalık ayracıyla dolar: "$1,24"; 100 $ üstünde kuruş yok. */
    fun usdLabel(value: Double): String {
        val digits = if (abs(value) >= 100) 0 else 2
        return "$" + String.format(Locale.US, "%.${digits}f", value).replace('.', ',')
    }

    /** Küçük kartın alt satırı: bugünün harcaması, yoksa ölçek cümlesi. */
    fun nanoBottomLine(nano: KotaNano): String {
        val spend = nano.todaySpendUsd
        return when {
            spend != null && nano.todayRequests != null ->
                "bugün ${usdLabel(spend)} · ${nano.todayRequests} istek"
            spend != null -> "bugün ${usdLabel(spend)}"
            nanoOverflow(nano) -> "10 $ ölçeğin üstünde"
            else -> "10 $ ölçeğinde %${nanoFill(nano).roundToInt()}"
        }
    }

    /** Bar sütunu dar: "7 gün (Fable)" → "Fable", "Son 7 gün" → "7 gün". */
    fun compactLabel(label: String): String {
        Regex("\\(([^)]+)\\)\\s*$").find(label)?.let { return it.groupValues[1] }
        val dot = label.substringAfterLast(" · ", "")
        if (dot.isNotEmpty()) return dot
        return label.removePrefix("Son ")
    }

    /**
     * Yenilenme anı SAAT olarak ("↻ 17:59"), Mac'teki gibi geri sayım değil:
     * bitmap ancak 15+ dakikada bir yeniden çiziliyor, "3dk" yazan geri sayım
     * arada yalan söylerdi. 24 saatten uzaksa gün adıyla ("Per 08:00").
     */
    fun resetLabel(resetAt: Instant, now: Instant, zone: ZoneId): String {
        if (!resetAt.isAfter(now)) return "yenilendi"
        val pattern = if (Duration.between(now, resetAt) < Duration.ofHours(24)) "HH:mm" else "EEE HH:mm"
        return DateTimeFormatter.ofPattern(pattern, trLocale).withZone(zone).format(resetAt)
    }
}
