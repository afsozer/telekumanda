package com.agent.bridge

// Faz 2b: backend-basina heterojen secenek listelerinin JENERIK okuma modeli.
// UI (ui2) yalniz bunu okur; tip cesitliligi bu dosyada olur ve biter.
data class BackendOption(val id: String, val label: String, val detail: String = "")


fun RemoteUiState.backendModelOptions(id: String): List<BackendOption> = when (id) {
    "agy" -> agyModels.map { BackendOption(it.id, it.label) }
    "claude-app" -> claudeModels.map { BackendOption(it.id, it.label) }
    "codex-app" -> codexModels.map { BackendOption(it.id, it.label) }
    "omp" -> omp.availableModels.map { BackendOption(it.id, it.label) }
    // listesinin kullanıcı sırasını sonradan ezmesini engeller.
    "cowork" -> backendModelOptions(normalizeCoworkProvider(coworkProvider))
    else -> emptyList()
}

fun RemoteUiState.backendPermissionModeOptions(id: String): List<BackendOption> = when (id) {
    // ACP kipleri: default / accept-edits / plan (kopru /agy/permission-modes).
    "agy" -> agy.permissionModes.map { BackendOption(it.id, it.name.ifBlank { it.id }, it.description) }
    "claude-app" -> {
        val modes = claudeAppInfo?.permissionModes?.ifEmpty { null } ?: listOf("", "auto", "plan", "acceptEdits", "bypassPermissions")
        modes.map { BackendOption(it, permissionModeLabel(it)) }
    }
    "codex-app" -> codexAppPermissionModes.map { BackendOption(it.id, it.label) }
    "omp" -> {
        val modes = omp.permissionModes.ifEmpty {
            listOf(
                PermissionMode("yolo", "YOLO", "Onay sormadan çalışır"),
                PermissionMode("write", "Write", "Yazma işlemlerinde sorar"),
                PermissionMode("ask", "Ask", "Araç çağrılarında sorar"),
            )
        }
        modes.map { BackendOption(it.id, it.name.ifBlank { permissionModeLabel(it.id) }, it.description) }
    }
    // Bilinen modlarda tek, kararlı Android metnini kullan. Gelecekte ACP yeni
    // bir mod eklerse onu canlı adı/açıklamasıyla kaybetmeden göster.
    "cowork" -> listOf(
        BackendOption("yolo", "Yolo", "Otomatik onay"),
        BackendOption("onay", "Onay", "Her izin için sor")
    )
    else -> emptyList()
}

fun RemoteUiState.backendEffortOptions(id: String): List<BackendOption> = when (id) {
    "claude-app" -> listOf(BackendOption("", "default")) +
        claudeAppEfforts.ifEmpty { listOf("low", "medium", "high", "xhigh", "max") }
            .map { BackendOption(it, it) }
    "codex-app" -> {
        val model = codexAppModel
        val levels = codexAppEffortsByModel[model]?.takeIf { it.isNotEmpty() }
            ?: codexAppEfforts.ifEmpty { listOf("low", "medium", "high", "xhigh") }
        val resolvedDefault = codexAppDefaultEffort.ifBlank {
            codexAppModelDefaultEfforts[model].orEmpty()
        }
        listOf(
            BackendOption(
                id = "",
                label = if (resolvedDefault.isBlank()) "default" else "default · $resolvedDefault",
            )
        ) + levels.map { BackendOption(it, it) }
    }
    // OMP'de de efor MODELE BAGLI (deepseek-flash low/high/max, v4-pro
    // high/max, qwen3-max hic). Statik 7 kademelik liste modelin desteklemedigi
    // seviyeleri gosteriyordu — kullanici bildirdi.
    "omp" -> {
        val modelDefault = ompDefaultVariantFor(ompModel)
        listOf(
            BackendOption(
                id = "",
                label = if (modelDefault.isBlank()) "varsayılan" else "varsayılan · $modelDefault",
                detail = if (modelDefault.isBlank()) "" else "bu model varsayılanda $modelDefault ile koşar",
            )
        ) + ompVariantsFor(ompModel).map { BackendOption(it, it) }
    }
    "cowork" -> backendEffortOptions(normalizeCoworkProvider(coworkProvider))
    else -> emptyList()
}

/**
 * "Degisiklikler" gorunumu var mi — GET /<b>/diff kayitli mi.
 *
 * Bugun yalniz codex-app (kaynagi akan fileChange item'lari).
 * claude-app/omp'de karsiligi yok.
 * Cowork dali skill dallariyla AYNI: cowork bir sunum katmani, ucu secili
 * saglayicinin ucu.
 */
fun RemoteUiState.backendDiffSupported(id: String): Boolean = when (id) {
    "codex-app" -> true
    "cowork" -> normalizeCoworkProvider(coworkProvider) == "codex-app"
    else -> false
}

/**
 * Ekranin cizecegi diff kaydi — hangi backend'in kutusundan okunacagi BURADA
 * karara baglaniyor.
 *
 * Ayri bir yardimci SART: kok, sheet govdesine tek bir backend'in kutusunu
 * yazsaydi baska sekmede satir gorunur, sheet acilir ve HEP bos kalirdi. Gorunurluk
 * ([backendDiffSupported]), yonlendirme (`loadBackendDiff`) ve okuma ayni
 * kimlik kumesine bakmak zorunda.
 */
fun RemoteUiState.backendDiff(id: String): BackendSessionDiff? = when (id) {
    "codex-app" -> codex.diff
    // Ozyineleme guvenli: normalizeCoworkProvider asla "cowork" dondurmuyor,
    // her zaman somut bir saglayici kimligine indiriyor.
    "cowork" -> backendDiff(normalizeCoworkProvider(coworkProvider))
    else -> null
}

fun RemoteUiState.backendDiffLoading(id: String): Boolean = when (id) {
    "codex-app" -> codex.diffLoading
    "cowork" -> backendDiffLoading(normalizeCoworkProvider(coworkProvider))
    else -> false
}

/**
 * "/" yazilinca cizilecek oneri listesi: statik istem sablonlari (secilince
 * composer'a YAZILIYOR).
 */
@Suppress("UNUSED_PARAMETER")
fun RemoteUiState.backendSlashSuggestions(id: String): List<SlashCommand> = slashCommands

// Skill pill'i: backend'in KENDI kurulu skill'leri (ad + kısa açıklama).
// Köprü açıklamaları vermiyorsa (eski sürüm) düz ad listesine düşülür, böylece
// pill boş görünmez. Cowork'te aktif sağlayıcının listesi gösterilir.
fun RemoteUiState.backendSkillOptions(id: String): List<BackendOption> {
    fun merge(details: List<SkillInfo>, names: List<String>): List<BackendOption> =
        if (details.isNotEmpty()) details.map { BackendOption(it.name, it.name, it.description) }
        else names.map { BackendOption(it, it) }
    return when (id) {
        "claude-app" -> merge(claudeAppInfo?.skillDetails.orEmpty(), claudeAppInfo?.skills.orEmpty())
        "codex-app" -> merge(codexAppInfo?.skillDetails.orEmpty(), codexAppInfo?.skills.orEmpty())
        "omp" -> merge(omp.info?.skillDetails.orEmpty(), omp.info?.skills.orEmpty())
        "cowork" -> backendSkillOptions(normalizeCoworkProvider(coworkProvider))
        else -> emptyList()
    }
}

// Skill'i KATEGORI klasorlerine bolen tek backend kaldirilinca
// (1 Agu 2026) gruplu skill kalmadi. Fonksiyon duruyor cunku arayuz rozet
// cizerken cagiriyor ve ileride baska bir backend gruplama getirirse tek
// dokunulacak yer burasi olsun — bugun her zaman bos doner.
fun RemoteUiState.backendSkillGroups(id: String): Map<String, String> = emptyMap()

fun RemoteUiState.backendSkillsSupported(id: String): Boolean = when (id) {
    // omp: skill envanteri RPC'nin get_available_commands çıktısından geliyor
    // (12 Ağu 2026); liste dolduğu hâlde çip gate'e takılıp görünmez kalıyordu.
    // agy'de Agent Skills ENVANTERI yok (ACP boyle bir uc vermiyor); slash
    // komutlari ayri yoldan, jenerik /slash ucuyla geliyor. Bu kapiyi acmak
    // menude calismayan bir satir cizerdi — Ui3MenuYonlendirmeTest yakaladi.
    "claude-app", "codex-app", "omp" -> true
    "cowork" -> true
    else -> false
}

fun RemoteUiState.backendEffortSupported(id: String): Boolean = when (id) {
    "claude-app", "codex-app" -> true
    // OMP'de reasoning'i olmayan model var (ör. qwen3-max): kademe yoksa cip cizilmez.
    "omp" -> ompVariantsFor(ompModel).isNotEmpty()
    "cowork" -> when (normalizeCoworkProvider(coworkProvider)) {
        "claude-app", "codex-app" -> true
        // OMP'de de kademe modele bağlı; doğrudan backend dalıyla aynı ölçüt.
        "omp" -> ompVariantsFor(ompModel).isNotEmpty()
        else -> false
    }
    else -> false
}

/** Secili OMP modelinin efor kademeleri; kopru RPC katalogundan okuyor. */
fun RemoteUiState.ompVariantsFor(modelId: String): List<String> =
    omp.availableModels.firstOrNull { it.id == modelId }?.variants.orEmpty()

/** Modelin kendi varsayilan kademesi (OMP `thinking.defaultLevel`). Bos = OMP karar verir. */
fun RemoteUiState.ompDefaultVariantFor(modelId: String): String =
    omp.availableModels.firstOrNull { it.id == modelId }?.defaultVariant.orEmpty()

fun RemoteUiState.backendEffortLabel(id: String): String {
    val session = backendSession(id)
    if (session.effort.isNotBlank()) return session.effort
    val provider = if (id == "cowork") normalizeCoworkProvider(coworkProvider) else id
    // OMP'de "varsayılan" seçiliyken modelin hangi kademeyle koştuğunu çipte
    // göster — codex'teki "default·high" ile aynı gerekçe (kullanıcı isteği).
    if (provider == "omp") {
        val modelDefault = ompDefaultVariantFor(ompModel)
        return if (modelDefault.isBlank()) "varsayılan" else "varsayılan·$modelDefault"
    }
    if (provider != "codex-app") return "default"
    val resolved = codexAppDefaultEffort.ifBlank {
        codexAppModelDefaultEfforts[codexAppModel].orEmpty()
    }
    return if (resolved.isBlank()) "default" else "default·$resolved"
}

fun RemoteUiState.activeProviderMonogramId(): String = when (backend) {
    // Ham provider degeri normalize edilir; aksi halde bos/eski degerde
    // monogram "?" olurdu (denetim duzeltmesi, 12.07.2026).
    "cowork" -> normalizeCoworkProvider(coworkProvider)
    null -> ""
    else -> backend
}
