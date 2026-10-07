package com.agent.bridge

/** Backend izin modlarının UI katmanından bağımsız, tek kaynak etiketi. */
fun permissionModeLabel(mode: String): String = when (mode) {
    "", "default" -> "Normal"
    "plan" -> "Plan"
    "acceptEdits", "accept_edits" -> "Düzenlemeleri otomatik kabul"
    "bypassPermissions" -> "İzinleri atla"
    "auto" -> "Otomatik"
    "dontAsk", "dont_ask" -> "Sorma"
    else -> mode
}

/**
 * İzin modu kullanıcıya sormadan iş yapıyor mu?
 *
 * ui3'te izin modu çipi composer şeridinde GÖRÜNMÜYOR — ⋯ menüsünün altında.
 * Gizlenen ayarlar içinde yanlış değerde kalınca zarar verebilecek tek şey bu
 * olduğu için, gevşek moddayken ⋯ tuşunun üstünde kehribar bir nokta yanar;
 * menüyü açmadan görülür.
 *
 * "plan" ve "" (normal) gevşek DEĞİL: ikisi de onay ister. Bilinmeyen bir mod
 * gevşek sayılmaz — uydurma bir uyarı, olmayan uyarıdan kötüdür.
 */
fun permissionModeIsPermissive(mode: String): Boolean = when (mode) {
    "acceptEdits", "accept_edits", "bypassPermissions", "auto", "dontAsk", "dont_ask" -> true
    else -> false
}

/**
 * Sağlayıcı kimliği monogramla gösterilir, renkle DEĞİL (anayasa 4.2) — renk
 * yalnız durumu anlatır. Cowork'te aktif sağlayıcının monogramı kullanılır
 * (çağıran taraf normalize edilmiş id vermeli).
 *
 * ui2'de `ui2/components/ProviderMark.kt` içindeydi; ui3'ün sekme çipi ve
 * oturum listesi de aynı monogramı çizdiği için buraya taşındı.
 */
fun providerMonogram(backendId: String): String = when (backendId) {
    "claude-app" -> "C"
    "codex-app" -> "X"
    // v1 sokuldu: OpenCode monogrami yine "O".
    "opencode2-app" -> "O"
    "omp" -> "P"
    "agy" -> "A"
    "cowork" -> "W"
    else -> "?"
}

/**
 * Model kimliğini çip için kısaltır: sağlayıcı öneki, "claude" ve sürüm/tarih
 * parçaları atılır. "claude-fable-5" → "fable", "claude-opus-4-8" → "opus",
 * "deepseek/deepseek-flash" → "deepseek flash". Tam kimlik seçim sheet'inde.
 *
 * ui2'de `ChatRootScreen`'in private'ıydı; ui3 de aynı kısaltmayı gösterdiği
 * için buraya taşındı. İki arayüz aynı modeli farklı adlandırmasın.
 */
/**
 * RunPod'da barındırılan RunPod modelinin kimliği.
 *
 * Köprüdeki `isRunPodModel` (bridge/opencode-app.mjs) ile AYNI tek kimlik —
 * sağlayıcı öneki taraması değil, çünkü "runpod/" altında başka model yok ve
 * olsaydı da pod'u onun için açmak yanlış olurdu.
 */
const val RUNPOD_MODEL_ID = "runpod/runpod"

/**
 * Seçili model RunPod pod'unu gerektiriyor mu?
 *
 * `Locale.ROOT` ŞART: tr-TR'de `lowercase()` "I" harfini "ı" yapıyor ve
 * karşılaştırma cihazın diline göre sessizce bozulurdu (bu depoda tekrarlayan
 * hata sınıfı).
 */
fun isRunPodModel(model: String): Boolean =
    model.trim().lowercase(java.util.Locale.ROOT) == RUNPOD_MODEL_ID

fun shortModelLabel(model: String): String {
    val base = model.substringAfterLast('/').trim()
    if (base.isBlank()) return model
    val tokens = base.split('-', '.', ' ').filter { it.isNotBlank() }.filterNot { t ->
        t.equals("claude", true) || t.equals("latest", true) || t.matches(Regex("v?\\d+\\w*"))
    }
    return if (tokens.isEmpty()) base else tokens.joinToString(" ")
}
