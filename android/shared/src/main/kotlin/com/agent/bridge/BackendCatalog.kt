package com.agent.bridge

// Tum backend kimlikleri. String id'ler telefon-bridge kontratinda ve prefs'te
// kullanildigi icin AYNEN korunur; enum sadece derleme-zamani exhaustiveness icin.
//
// apiBackend: ag katmaninin (BridgeClient/session stream) kullandigi gercek backend id'si.
// Cogu backend kendisiyle ayni uclari kullanir; COWORK ise bir *sunum + konfigurasyon
// katmanidir* ve ag katmaninda claude-app uclarini kullanir (kendi endpoint'i yok).
data class BackendCapabilities(
    val approvals: Boolean = false,
    // KABA (eski) alan: "tur surerken girdi kabul edilir". Tek basina composer
    // kipini cizmeye YETMEZ — asagidaki iki alan hangi ucun gercekten kayitli
    // oldugunu soyler ve arayuzu onlar surer.
    val userInput: Boolean = false,
    val userInputSteer: Boolean = false,  // POST /<b>/steer — suren tura enjeksiyon
    val userInputQueue: Boolean = false,  // POST /<b>/follow-up — tur bitince islensin
    val permissionModes: Boolean = false,
    val context: Boolean = true,
    val plan: Boolean = false,
    val outputs: Boolean = false,
)

// userInputSteer/userInputQueue TABANA GIRMEZ: hangi ucun kayitli oldugu backend
// basina degisiyor (claude-app'te ikisi de yok, codex-app'te yalniz steer,
// opencode-app'te yalniz queue) ve tabana koymak olmayan bir uca buton cizdirirdi.
private val APP_AGENT_CAPABILITIES = BackendCapabilities(
    approvals = true,
    userInput = true,
    permissionModes = true,
    context = true,
)

enum class Backend(
    val id: String,
    val apiBackend: String = id,
    val capabilities: BackendCapabilities = BackendCapabilities(),
) {
    CLAUDE_APP("claude-app"),
    AGY("agy", capabilities = BackendCapabilities(context = false)),
    // Codex'in steer'i VAR ama /follow-up'i yok; ustelik telefonda kendi eski
    // "steer kipi" yolunu kullaniyor (CodexAppActionsDelegate), ortak tur-ici
    // composer'i DEGIL — userInputQueue=false onu ortak kipin disinda tutar.
    CODEX_APP("codex-app", capabilities = APP_AGENT_CAPABILITIES.copy(plan = true, userInputSteer = true)),
    // OpenCode (v2.0.x). v1 girisi (OPENCODE_APP, "opencode2-app") 30.09.2026'da
    // SOKULDU. v2 sunucusu "delivery" alaniyla hem kuyrugu hem tur-ici
    // enjeksiyonu tasiyor; kimlik "opencode2-app" KALDI (telefondaki sekme,
    // pin ve izin kayitlari bu kimlikle saklaniyor), etiket "OpenCode".
    OPENCODE2_APP("opencode2-app", capabilities = APP_AGENT_CAPABILITIES.copy(userInputSteer = true, userInputQueue = true)),
    OMP("omp", capabilities = APP_AGENT_CAPABILITIES.copy(userInputSteer = true, userInputQueue = true)),
    COWORK(
        "cowork",
        "claude-app",
        APP_AGENT_CAPABILITIES.copy(outputs = true),
    );

    companion object {
        fun from(id: String?): Backend? = entries.firstOrNull { it.id == id }
    }
}

// Bridge'in /backends ucundan gelen versiyonlu yetenek kataloğu (Faz 4). Bridge TEK
// KAYNAKtır; boş/erişilemez olduğunda aşağıdaki gömülü enum çevrimdışı yedek olur.
data class BackendCatalogInfo(
    val contractVersion: Int = 0,
    val entries: Map<String, BackendCapabilities> = emptyMap(),
) {
    val loaded: Boolean get() = entries.isNotEmpty()
}

// Yetenekleri çözer: mümkünse bridge kataloğunu, yoksa gömülü enum'u kullanır.
// COWORK için yetenek seçili provider'dan türetilir (provider caps + outputs).
fun backendCapabilities(
    id: String?,
    coworkProvider: String = "",
    catalog: BackendCatalogInfo? = null,
): BackendCapabilities {
    fun caps(backendId: String?): BackendCapabilities? =
        backendId?.let { catalog?.entries?.get(it) ?: Backend.from(it)?.capabilities }

    val backend = Backend.from(id)
    if (backend != Backend.COWORK) {
        return caps(id) ?: BackendCapabilities(context = false)
    }
    val providerCapabilities = caps(coworkProvider) ?: Backend.CLAUDE_APP.capabilities
    return providerCapabilities.copy(outputs = true)
}

// Backend id → görünen etiket (saf, Compose'suz — SettingsModels vb. shared kod
// kullanır). İkonlu UI kataloğu (BACKEND_CATALOG/BACKEND_ICONS) app modülündedir.
val BACKEND_LABELS: Map<String, String> = mapOf(
    "claude-app" to "Claude App",
    "agy" to "Antigravity CLI",
    "codex-app" to "Codex App",
    "opencode2-app" to "OpenCode",
    "omp" to "OMP",
    "cowork" to "Cowork",
)

// Dar yerler için kısa etiket (süzgeç çipleri): "Claude App" bir çip satırına
// beşi yan yana sığmıyor. Bilinmeyen id'de uzun etikete, o da yoksa id'ye düşer.
val BACKEND_SHORT_LABELS: Map<String, String> = mapOf(
    "claude-app" to "Claude",
    "agy" to "Antigravity",
    "codex-app" to "Codex",
    "opencode2-app" to "OpenCode",
    "omp" to "OMP",
    "cowork" to "Cowork",
)

fun backendShortLabel(id: String): String =
    BACKEND_SHORT_LABELS[id] ?: BACKEND_LABELS[id] ?: id

internal const val MCP_TARGET_CLAUDE = "claude"
internal const val MCP_TARGET_ANTIGRAVITY = "antigravity"
internal fun mcpTargetFor(backend: String?, coworkProvider: String): String? = when (backend) {
    "claude-app" -> MCP_TARGET_CLAUDE
    "cowork" -> when (normalizeCoworkProvider(coworkProvider)) {
        "codex-app" -> "codex-app"
        "opencode2-app" -> "opencode2-app"
        "omp" -> "omp"
        else -> MCP_TARGET_CLAUDE
    }
    "agy" -> MCP_TARGET_ANTIGRAVITY
    "codex-app", "opencode2-app", "omp" -> backend
    else -> null
}
internal fun mcpTargetLabel(target: String?): String = when (target) {
    MCP_TARGET_CLAUDE -> "Claude"
    MCP_TARGET_ANTIGRAVITY -> "Antigravity"
    "codex-app" -> "Codex"
    "opencode2-app" -> "OpenCode"
    "omp" -> "OMP"
    else -> ""
}
internal fun mcpEffectNote(target: String): String = when (target) {
    "codex-app" -> "yeni Codex oturumunda etkin olur"
    "opencode2-app" -> "OpenCode sunucusu yeniden başlayınca etkin olur"
    "omp" -> "yeni OMP oturumunda etkin olur"
    // ACP süreci uzun ömürlü; config değişikliği ancak yeni süreçte okunur.
    else -> "yeni oturumda etkin olur"
}
