package com.agent.bridge

/**
 * Cowork gezgininin iki ayrı ayarı. Tek değişkende birleştirilince canlı hata
 * çıktı (03.08.2026): çalışma alanının içinden "Dosyalar"a girildiğinde alanın
 * klasörü yerine genel CoworkSpaces kökü açılıyordu.
 *
 * @param rootPath CoworkSpaces kökü (köprüden gelir, geç dolabilir)
 * @param filesStart bu ziyaret için istenen başlangıç klasörü (bir alandan
 *   girildiyse o alanın yolu; genel girişte boş)
 */
data class CoworkBrowserScope(
    /** Gezginin ÇIKAMAYACAĞI sınır. */
    val rootLock: String,
    /** Bu ziyarette AÇILACAK klasör. */
    val startDir: String,
)

fun coworkBrowserScope(rootPath: String, filesStart: String): CoworkBrowserScope {
    // Kök henüz yüklenmediyse (gezgine derin girişle gelindi) sınır olarak
    // başlangıç klasörünü kullanırız — kök yerine alanın kendisi, yine güvenli.
    val rootLock = rootPath.ifBlank { filesStart }
    return CoworkBrowserScope(rootLock = rootLock, startDir = filesStart.ifBlank { rootLock })
}

data class CoworkProviderOption(val id: String, val label: String)

val COWORK_PROVIDER_OPTIONS = listOf(
    CoworkProviderOption("claude-app", "Claude"),
    CoworkProviderOption("codex-app", "Codex"),
    CoworkProviderOption("opencode2-app", "OpenCode"),
    CoworkProviderOption("opencode2-app", "OpenCode"),
    CoworkProviderOption("omp", "Oh My Pi"),
)

fun normalizeCoworkProvider(provider: String): String = when (provider) {
    "codex-app", "opencode2-app", "omp" -> provider
    else -> "claude-app"
}

fun isCoworkCodexProvider(provider: String): Boolean =
    normalizeCoworkProvider(provider) == "codex-app"



fun coworkProviderLabel(provider: String): String = when (normalizeCoworkProvider(provider)) {
    "codex-app" -> "Codex"
    "opencode2-app" -> "OpenCode"
    "omp" -> "Oh My Pi"
    else -> "Claude"
}

/**
 * Çalışma alanı kilidi (lease) alınamadığında gösterilecek metin.
 *
 * Köprü 409 gövdesinde hem `error` hem de kilidi TUTAN oturumu (`lease`)
 * yolluyor; eskiden istemci gövdeyi hiç okumadan "HTTP 409" basıyordu ve
 * kullanıcı kimin engellediğini göremiyordu (05.08.2026'da canlı görüldü:
 * aynı alanda Codex turu sürerken Claude'a prompt gönderildi).
 *
 * Kilit ya süren bir tura ya da 30 saniyelik acquire→prompt penceresine ait
 * olur; metin ikisini de kapsayacak şekilde "kullanıyor" der, "çalışıyor" diye
 * kesin konuşmaz.
 */
fun coworkLeaseBusyMessage(ownerProvider: String, fallback: String): String {
    val owner = ownerProvider.trim()
    if (owner.isBlank()) return fallback.ifBlank { "Çalışma alanı kullanımda" }
    return "${coworkProviderLabel(owner)} bu çalışma alanını kullanıyor — turu bitince tekrar gönder"
}

fun coworkModelProvider(provider: String): String =
    normalizeCoworkProvider(provider)

fun coworkApprovalBackend(backend: String?, coworkProvider: String): String =
    when (backend) {
        "cowork" -> normalizeCoworkProvider(coworkProvider)
        "codex-app", "opencode2-app", "omp" -> backend
        else -> "claude-app"
    }
