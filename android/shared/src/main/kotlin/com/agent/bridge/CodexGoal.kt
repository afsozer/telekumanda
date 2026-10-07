package com.agent.bridge

import org.json.JSONObject

/**
 * Codex oturum hedefi (/goal). app-server'da bir thread'e bagli calisir; threadId
 * henuz yoksa kopru goal:null doner (oturum var ama hedef kurulmamis). Alan adlari
 * kopru Goal nesnesiyle birebir.
 *
 * ONEMLI DAVRANIS: status:"active" ile hedef kurmak app-server'da KENDILIGINDEN bir
 * model turn'u baslatir — yani "hedefi kur" ile "Codex'i hedefe calistir" ayni islem.
 * Bu yuzden arayuz metinleri bunu "kur ve baslat" olarak anlatir, gizli sihir gibi
 * degil.
 */
@androidx.compose.runtime.Immutable
data class CodexGoal(
    val threadId: String,
    val objective: String,
    val status: String,           // "active"|"paused"|"blocked"|"usageLimited"|"budgetLimited"|"complete"
    val tokenBudget: Long? = null, // null = butce yok
    val tokensUsed: Long = 0,
    val timeUsedSeconds: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    companion object {
        /**
         * Kopru Goal JSON'unu cozer. null zarf ya da bos zarf (ne threadId ne objective)
         * = hedef yok -> null doner. Ayni cozucu hem HTTP hem WS/poll snapshot yolunda
         * kullanilir, iki yerde kaymasin diye tek noktada.
         */
        fun fromJson(obj: JSONObject?): CodexGoal? {
            if (obj == null) return null
            val threadId = obj.optString("threadId")
            val objective = obj.optString("objective")
            if (threadId.isBlank() && objective.isBlank()) return null
            return CodexGoal(
                threadId = threadId,
                objective = objective,
                status = obj.optString("status"),
                // tokenBudget nesnede acikca null gelebilir (butce yok); has()+isNull ile
                // "alan var ama null" ile "0 butce"yi karistirma.
                tokenBudget = if (obj.has("tokenBudget") && !obj.isNull("tokenBudget")) obj.optLong("tokenBudget") else null,
                tokensUsed = obj.optLong("tokensUsed"),
                timeUsedSeconds = obj.optLong("timeUsedSeconds"),
                createdAt = obj.optLong("createdAt"),
                updatedAt = obj.optLong("updatedAt"),
            )
        }
    }
}

/** /goal (tek basina) yakalandiginda emit edilecek tek satirlik ozet. Saf: birim test edilir. */
fun CodexGoal?.summaryLine(): String =
    if (this == null) "Hedef yok"
    else "Hedef: $objective ($status, $tokensUsed token)"

/**
 * Hedef HALA yuruyor mu? Yalniz "active" canlidir; complete/paused/blocked/
 * usageLimited/budgetLimited hepsi durmustur. Pill'i kapatma (ve app-server'daki
 * hedefi dusurme) yalniz durmus hedefte sunulur — calisan hedefi "gordum" diye
 * kapatmak onu silmek olurdu.
 */
val CodexGoal.live: Boolean get() = status.equals("active", ignoreCase = true)

/** app-server durum kodunun Turkce karsiligi; bilinmeyen kod oldugu gibi gosterilir. */
fun CodexGoal.statusLabel(): String = when (status.lowercase()) {
    "active" -> "çalışıyor"
    "complete" -> "tamamlandı"
    "paused" -> "duraklatıldı"
    "blocked" -> "engellendi"
    "usagelimited" -> "kullanım sınırına takıldı"
    "budgetlimited" -> "bütçe sınırına takıldı"
    else -> status.ifBlank { "bilinmiyor" }
}

/** "1 sa 20 dk" / "45 dk" / "30 sn". Saf: birim test edilir. */
fun formatGoalDuration(seconds: Long): String {
    if (seconds <= 0) return "0 sn"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> "$hours sa ${minutes} dk"
        minutes > 0 -> "$minutes dk"
        else -> "$seconds sn"
    }
}

/** Hedef diyalogunun govde metni: durum + harcanan token + sure. Saf. */
fun CodexGoal.detailText(): String = buildList {
    add("Durum: ${statusLabel()}")
    add("Harcanan: $tokensUsed token" + (tokenBudget?.let { " / $it bütçe" } ?: ""))
    if (timeUsedSeconds > 0) add("Süre: ${formatGoalDuration(timeUsedSeconds)}")
}.joinToString("\n")

/**
 * Codex composer'inda "/goal ..." girdisinin anlami. Yakalama saf mantik olarak burada
 * durur ki hem Android ViewModel hem ileride desktop istemcisi ayni sozdizimini paylassin;
 * birim testi de UI'ye girmeden kosar.
 */
sealed interface CodexGoalCommand {
    /** "/goal" tek basina: mevcut hedefi getir + goster. */
    object Show : CodexGoalCommand
    /** "/goal temizle" | "/goal clear": hedefi temizle. */
    object Clear : CodexGoalCommand
    /** "/goal <metin>": hedefi kur (ve status:active ile baslat). */
    data class Set(val objective: String) : CodexGoalCommand

    companion object {
        /**
         * Girdi "/goal" ile baslamiyorsa null doner (normal mesaj — gonderime devam).
         * lowercase() yerel-bagimsiz (Locale.ROOT) calisir; tr-TR'nin I/i tuzagi burada
         * "/GOAL"i "/goal"a cevirirken bozulmaz.
         */
        fun parse(input: String): CodexGoalCommand? {
            val trimmed = input.trim()
            val lower = trimmed.lowercase()
            if (lower != "/goal" && !lower.startsWith("/goal ") && !lower.startsWith("/goal\n")) return null
            val rest = trimmed.substring("/goal".length).trim()
            if (rest.isEmpty()) return Show
            return when (rest.lowercase()) {
                "temizle", "clear" -> Clear
                else -> Set(rest)
            }
        }
    }
}
