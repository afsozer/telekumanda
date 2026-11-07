package com.agent.bridge

// RemoteViewModel'in dışa dönük UI durumu ve ona bağlı UI model tipleri.
// Bu dosya yalnızca state tanımlarını içerir — ViewModel davranışı RemoteViewModel.kt'de
// kalır. Tüm referans verilen tipler (BridgeSettings, McpServer, SessionInfo, ...) zaten
// bridge paketindeki ayrı dosyalarda tanımlı, bu yüzden ek import gerekmez.
//
// Taşınma: madde 11.1 (salt taşıma, davranış değişikliği yok).

// Yer tutucu localhost adresi. Telefondan PC'ye ulaşmaz; ilk açılışta
// Ayarlar'dan kendi makinenin adresiyle değiştirilmeli.
const val DEFAULT_URL = "http://127.0.0.1:8787"

// Cowork workspace klasörü — GET /claude-app/workspaces cevabındaki { name, path }.
data class CoworkWorkspace(val name: String, val path: String, val matter: String = "")

// Uygulama geneli sekme: herhangi bir backend'in herhangi bir oturumuna yer imi.
// id: sekmenin kendi kimliği (UUID); sessionId boş olabilir (landing sekmesi).
data class AppTab(
    val id: String,
    val backend: String,          // "claude-app" | "codex-app" | "cowork" | ...
    val provider: String = "",    // yalnız cowork için anlamlı ("claude-app"/"codex-app"/...)
    val sessionId: String = "",   // boş = henüz oturum bağlanmamış (landing sekmesi)
    val title: String = "",       // kısa etiket; boşsa UI backend etiketini gösterir
    val bridgeProfileId: String = "", // sekmenin oturum kimliklerinin geçerli olduğu köprü
)

// Onay bekleyen sekme kapatma isteği. fromBack: geri jestiyle geldiyse onay
// sonrası Merkez'e dönüş kuralı (returnToHubOnChatBack) da işletilir.
data class PendingTabClose(val tabId: String, val fromBack: Boolean = false)

data class TabStatus(
    val live: Boolean = true,            // bridge canlı listesinde var mı
    val running: Boolean = false,
    val awaitingApproval: Boolean = false,
    val finishedUnseen: Boolean = false, // arka plandayken bitti, henüz bakılmadı
    val cwd: String = "",
    val lastText: String = "",           // önizleme menüsü için
    val liveTitle: String = "",          // bridge title — ilk prompt sonrası bir kez kilitli
    val threadId: String = "",           // disk kimliği; canlı kabuk id'sinden farklı olabilir
    // Bu status'un yazıldığı oturum kimliği. syncActiveTab tab.id'yi koruyup
    // sessionId'yi değiştirebildiği için liveTitle kilidi buna bağlı tutulur
    // (aynı sekmede başka oturuma geçince kilit sıfırlansın).
    val sessionId: String = "",
)

// Composer'daki ekin UI modeli: path bridge'in PC tarafındaki kayıt yolu,
// localUri thumbnail çizimi için telefondaki kaynak URI.
data class ChatAttachment(
    val name: String,
    val path: String,
    val localUri: String? = null,
    val isImage: Boolean = false,
)

/** Tur sürerken bekleyen, ait olduğu sekme ve ekleriyle birlikte kalıcı prompt. */
@androidx.compose.runtime.Immutable
data class QueuedPrompt(
    val id: String,
    val text: String,
    val attachments: List<ChatAttachment> = emptyList(),
    val bridgeProfileId: String = "",
    val tabId: String = "",
    val backend: String = "",
    val provider: String = "",
    val sessionId: String = "",
)

/** Dosya gezgini ve telefon indirilenleri tek feature state altında yaşar. */
@androidx.compose.runtime.Immutable
data class FilesUiState(
    val openDownloadFolder: DownloadRecord? = null,
    val downloadFolderEntries: List<DownloadRecord> = emptyList(),
    val browserEntries: List<DirEntry> = emptyList(),
    val browserBase: String = "",
    val browserLoading: Boolean = false,
    // PC'nin sürücü kökleri (C:\, D:\ …). Bir kez çekilir; sürücü kökünün üstü
    // olmadığı için gezginin diskler arasında geçebilmesinin TEK yolu bu.
    val driveRoots: List<DirEntry> = emptyList(),
    val browserColumns: Int = 2,
    val lastOpenedPath: String = "",
    val activeDownloadName: String? = null,
    val activeDownloadProgress: Float = 0f,
    val downloadRecords: List<DownloadRecord> = emptyList(),
    // Telefon gezgini PC'den AYRI state tutar: sekme değiştirince iki tarafın da
    // bulunduğu klasör korunsun, geri dönünce baştan başlamasın.
    val phoneEntries: List<DirEntry> = emptyList(),
    val phoneBase: String = "",
    val phoneLoading: Boolean = false,
    // Klasör okunamadı (izin yok ya da yol kayıp). "Boş klasör" ile karıştırmamak
    // için ayrı tutulur — kullanıcıya farklı şey söylememiz gerekiyor.
    val phoneError: String = "",
)

/** Cihaz anahtarı eşleştirmesi. */
@androidx.compose.runtime.Immutable
data class DeviceUiState(
    val pairingCode: String = "",
    val pairingExpiresAt: String = "",
    val paired: Boolean = false,
    val authLoading: Boolean = false,
)

/** Antigravity CLI'ye ait oturum, model ve disk kayıtları. */
@androidx.compose.runtime.Immutable
data class AgyUiState(
    val sessionId: String = "",
    val models: List<AgyModel> = emptyList(),
    val cwd: String = "",
    val model: String = "gemini-3.8-flash-high",
    val setupPending: Boolean = false,
    val diskSessions: List<AgyDiskSession> = emptyList(),
    val diskLoading: Boolean = false,
    val opening: Boolean = false,
)

/** Cowork workspace, sağlayıcı, teslimat ve içe aktarma durumu. */
@androidx.compose.runtime.Immutable
data class CoworkUiState(
    val outputs: List<CoworkOutput> = emptyList(),
    val workspaces: List<CoworkWorkspace> = emptyList(),
    val workspacesLoading: Boolean = false,
    val provider: String = "claude-app",
    val activeProjectPath: String = "",
    val selectedWorkspace: String = "",
    val sessions: List<CoworkSessionRecord> = emptyList(),
    val sessionsLoading: Boolean = false,
    // Varsayılan YOLO (kullanıcı kararı 21.07): her provider'da yeni cowork
    // oturumları otomatik onayla açılır; "Onay" bilinçli opt-out.
    val yoloMode: Boolean = true,
    val importBase: String = "",
    val importEntries: List<DirEntry> = emptyList(),
    val importLoading: Boolean = false,
    // Cowork dosya gezgini: rootPath = CoworkSpaces kökü (/cowork/projects'ten),
    // filesStart = gezginin açıldığı başlangıç klasörü (kök veya bir workspace).
    val rootPath: String = "",
    val filesStart: String = "",
    // Bu ziyarette gerçekten AÇILACAK klasör. filesStart'tan ayrı, çünkü
    // sohbetteki klasör tuşu alanın kökü yerine son gezilen alt klasörü açıyor
    // (coworkResumeDir); filesStart ise alanın kendisi kalmalı — kök kilidi ve
    // telefon aynası oradan türetiliyor. Boşsa filesStart geçerli.
    val filesOpenDir: String = "",
)

/** OMP'ye ait oturum, model, izin ve bilgi durumu. */
@androidx.compose.runtime.Immutable
data class OmpUiState(
    val sessionId: String = "",
    val lastSessionId: String = "",
    val cwd: String = "",
    val model: String = "deepseek/deepseek-flash",
    val defaultModel: String = "deepseek/deepseek-flash",
    val setupPending: Boolean = false,
    val diskSessions: List<AppDiskSession> = emptyList(),
    val diskLoading: Boolean = false,
    val availableModels: List<BackendModel> = emptyList(),
    val permissionMode: String = "yolo",
    val permissionModes: List<PermissionMode> = emptyList(),
    val info: OmpAppInfo? = null,
    // Secili akil yurutme eforu ("" = modelin varsayilani). Gecerli degerler
    // modele gore degisir; availableModels icindeki variants listesinden gelir.
    val variant: String = "",
)

/**
 * `GET /<backend>/diff` yanıtı — oturumun dosya değişiklikleri.
 *
 * BACKEND'DEN BAĞIMSIZ, bilerek: dönüşüm köprüde yapılıyor (gerekçesi
 * codex-app.mjs'in sessionDiff başlığında). Bugün tek kaynağı codex-app.
 *
 * [turns] kaç ayrı turda dokunulduğu; 0/1 ise arayüz tur satırını hiç yazmıyor.
 * [truncated] köprünün listeyi kestiğini söyler (dosya başına ~100KB patch ya da
 * dosya sayısı tavanı). Arayüz bunu göstermek ZORUNDA: eksik bir diff'i tam
 * sanmak, incelemenin tamamını boşa çıkarır.
 */
@androidx.compose.runtime.Immutable
data class BackendSessionDiff(
    val files: List<BackendDiffFile> = emptyList(),
    val additions: Int = 0,
    val deletions: Int = 0,
    val turns: Int = 0,
    val truncated: Boolean = false,
    // Köprü listenin BAŞININ eksik olduğunu söylüyor: codex'te geçmişi rollout
    // dosyasından kurulan oturumlarda (köprü yeniden başladıktan sonra ya da
    // diskten açılan eski bir oturumda) daha önceki dosya değişiklikleri
    // okunamıyor. Bu bayrak varken BOŞ liste "hiçbir şey değişmedi" DEMEK
    // DEĞİLDİR ve arayüz o cümleyi kurmamalı.
    val historyGap: Boolean = false,
)

/**
 * Değişen tek dosya. [patch] birleşik (unified) diff metni; çok turlu oturumda
 * turların yamaları ARDIŞIK durur (köprü tek yamaya kaynatmıyor, satır
 * numaraları yanlış olurdu). [status] added|deleted|modified.
 */
@androidx.compose.runtime.Immutable
data class BackendDiffFile(
    val path: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String = "modified",
    val patch: String = "",
    val truncated: Boolean = false,
)

/** Codex App'e ait oturum, model, plan, izin ve reasoning durumu. */
@androidx.compose.runtime.Immutable
data class CodexUiState(
    val sessionId: String = "",
    val models: List<CodexModel> = emptyList(),
    val lastSessionId: String = "",
    val cwd: String = "",
    // BOŞ = "köprü henüz söylemedi". Buraya sabit bir model adı yazmak kullanıcının
    // config.toml'daki varsayılanını EZİYORDU: loadCodexAppModels `model.ifBlank { default }`
    // ile dolduruyor, alan doluysa köprünün cevabı hiç uygulanmıyordu (canlı: config
    // deepseek derken uygulama gpt gösteriyordu). Tek doğruluk kaynağı köprü.
    val model: String = "",
    val defaultModel: String = "",
    val setupPending: Boolean = false,
    val diskSessions: List<CodexDiskSession> = emptyList(),
    val diskLoading: Boolean = false,
    val plan: List<PlanItem> = emptyList(),
    val planDraft: String = "",
    val info: CodexAppInfo? = null,
    // DEĞİŞİKLİKLER — backend'den bağımsız tip ve ekran. null = hiç
    // istenmedi; boş `files` taşıyan kayıt "gerçekten hiçbir dosya değişmedi"
    // demek (ikisi ayrı, boş durum metni ancak ÖLÇTÜKTEN sonra yazılabilir).
    val diff: BackendSessionDiff? = null,
    val diffLoading: Boolean = false,
    val commands: List<CodexCommand> = emptyList(),
    val steerMode: Boolean = false,
    val contextPercent: Int = 0,
    val permissionMode: String = "yolo",
    val permissionModes: List<PermissionModeItem> = emptyList(),
    val effort: String = "",
    val efforts: List<String> = emptyList(),
    val effortsByModel: Map<String, List<String>> = emptyMap(),
    val defaultEffort: String = "",
    val modelDefaultEfforts: Map<String, String> = emptyMap(),
    // Oturum hedefi (/goal). null = hedef yok (ya da eski kopru hic yollamadi).
    val goal: CodexGoal? = null,
)

/** Claude App'e ait oturum, hesap, model, izin ve reasoning durumu. */
@androidx.compose.runtime.Immutable
data class ClaudeUiState(
    val models: List<ClaudeModel> = emptyList(),
    val sessionId: String = "",
    val lastSessionId: String = "",
    val cwd: String = "",
    val model: String = "claude-opus-5",
    val defaultModel: String = "claude-opus-5",
    val permissionMode: String = "auto",
    val effort: String = "",
    val efforts: List<String> = emptyList(),
    val setupPending: Boolean = false,
    val diskSessions: List<ClaudeDiskSession> = emptyList(),
    val diskLoading: Boolean = false,
    val info: ClaudeAppInfo? = null,
)

@androidx.compose.runtime.Immutable
enum class ProjectFilter {
    ALL, PINNED, ACTIVE, NEW_DELIVERY
}

data class ProjectChatRequest(
    val projectId: String,
    val projectPath: String,
    val workspacePath: String? = null,
    val provider: String,
    val model: String = "",
    val permissionMode: String = "",
    val effort: String = "",
)

data class BulkSessionResult(
    val backend: String,
    val sessionId: String,
    val ok: Boolean,
    val error: String,
)

data class BulkSessionActionResponse(
    val ok: Boolean,
    val action: String,
    val succeeded: Int,
    val failed: Int,
    val results: List<BulkSessionResult>,
)

data class RemoteUiState(
    /** Derleme politikası; shared katman Android BuildConfig'e bağımlı kalmasın. */
    val liteEdition: Boolean = false,
    /** Lite oturumlarının tek görünür çalışma kökü; /dirs?scope=lite yanıtından gelir. */
    val liteWorkspaceRoot: String = "",
    val projectsFilter: ProjectFilter = ProjectFilter.ALL,
    val projectsSearchQuery: String = "",
    val settings: BridgeSettings = BridgeSettings(DEFAULT_URL, ""),
    val bridgeProfiles: List<BridgeProfile> = listOf(
        BridgeProfile("default", "Köprü 1", DEFAULT_URL, ""),
    ),
    val activeBridgeProfileId: String = "default",
    val mcpServers: List<McpServer> = emptyList(),
    val mcpLoading: Boolean = false,
    val healthOk: Boolean = false,
    val protocolCompatible: Boolean = true,
    val bridgeConnected: Boolean = false,
    val wsConnected: Boolean = false,
    val model: String = "Unknown model",
    val defaultModel: String = "",
    val availableModels: List<String> = emptyList(),
    val running: Boolean = false,
    val awaitingApproval: Boolean = false,
    val approval: ApprovalInfo? = null,
    val interruptStuck: Boolean = false,
    val awaitingFirstOutput: Boolean = false,
    val choices: List<String> = emptyList(),
    // BU İKİSİ OTURUMA AİT ve ikisi AYRI kanallardan geliyor: token sayısı her
    // karede tazeleniyor, pencere ise ancak köprü modelin kataloğunu ısıttıktan
    // sonra. Yeni oturuma geçerken sıfırlanmazlarsa ikisi FARKLI oturumlardan
    // kalır ve bölümleri yalan söyler — canlıda tam bu oldu: eski oturumun 12k
    // penceresi yeni oturumun 44k token'ıyla bölününce ada %100 gösterdi, oysa
    // köprü "ölçemiyorum" diyordu. Bu yüzden transkriptin boşaldığı her yerde
    // (oturum/sekme/backend değişimi) ikisi de 0'a çekiliyor; 0 = "bilinmiyor",
    // okuyan taraf yüzdeyi HİÇ çizmiyor.
    val contextTokens: Int = 0,
    val contextWindow: Int = 0,
    val cost: Double = 0.0,
    val currentSession: String = "",
    val currentProject: String = "",
    val sessions: List<SessionInfo> = emptyList(),
    val projects: List<String> = emptyList(),
    val slashCommands: List<SlashCommand> = emptyList(),
    val usage: UsageResult = UsageResult(emptyList(), ""),
    // "Yenile" basıldığında canlı çekim sürüyor: tuş yerinde ilerleme halkası
    // gösterilir, aksi halde tuş hiçbir şey yapmıyormuş gibi hissettiriyordu.
    val usageLoading: Boolean = false,
    // Kullanım ekranında GİZLENEN kart anahtarları (bkz. UsageCardVisibility:
    // görünenler değil gizlenenler saklanır, yeni kartlar kendiliğinden gelsin).
    val hiddenUsageCards: Set<String> = emptySet(),
    val messagesList: List<ChatMessage> = emptyList(),
    // Bridge'e erişilemezken Room'dan geri yüklenen son konuşma gösteriliyor mu?
    val offlineConversation: Boolean = false,
    // Sohbetteki "Yenile" pill'i çalışıyor mu? Tuş görsel geri bildirim
    // vermediği için "hiçbir şey yapmıyor" hissi veriyordu (kullanıcı raporu
    // 06.08.2026) — pill bu bayrakla "Yenileniyor…" gösterir.
    val conversationRefreshing: Boolean = false,
    // Sekme geçişinde ConversationMemory'den prefill edilen BAYAT görüntü mü?
    // Taze veri (HTTP refresh/poll ya da WS snapshot) gelince düşer; açıkken
    // recordFrom bayat görüntüyü belleğe geri yazmaz.
    val staleConversation: Boolean = false,
    val transcript: String = "",
    val attachments: List<ChatAttachment> = emptyList(),
    val input: String = "",
    // ETKİN OLMAYAN sekmelerin gönderilmemiş taslakları (tabId -> taslak).
    //
    // Etkin sekmenin taslağı bu haritada DEĞİL, `input`/`attachments`te yaşar:
    // tek bir gerçek kaynak olsun, composer iki yerden beslenmesin. Sekme
    // değişince `withActiveTab` ikisi arasında takas yapar.
    val composerDrafts: Map<String, ComposerDraft> = emptyMap(),
    // Tur sürerken gönderilen prompt'lar, fotoğraf/dosya ekleri ve hedef sekme
    // kimliğiyle burada birikir; süreç ölümüne karşı prefs'e de yazılır.
    val promptQueue: List<QueuedPrompt> = emptyList(),
    val testing: Boolean = false,
    val bridgeRestarting: Boolean = false,
    val sending: Boolean = false,
    val device: DeviceUiState = DeviceUiState(),
    val workerDirs: WorkerDirs? = null,
    val folderSearchResults: List<DirEntry> = emptyList(),
    val cowork: CoworkUiState = CoworkUiState(),
    // null = landing kok state (hicbir ajanda degil). Bir ajan kartina basinca
    // backend = <id> olur; ajandan cikinca null'a doner. Eski "antigravity"
    // sentinel'i kaldirildi — landing artik bunun yerine cizilir.
    val backend: String? = null,
    // Sekme çubuğu: açık sekmeler + aktif sekme id'si. Sekmeler yer imidir;
    // oturum state'i bridge'de yaşar, geçişte yeniden bağlanılır.
    val openTabs: List<AppTab> = emptyList(),
    val activeTabId: String = "",
    val tabStatuses: Map<String, TabStatus> = emptyMap(),
    val agy: AgyUiState = AgyUiState(),
    val claude: ClaudeUiState = ClaudeUiState(),
    // Backend değiştirince (exit) hangi oturumdaydık — geri dönünce ona yeniden bağlanmak için.
    // Cowork ve claude-app AYNI backend'i (claude-app) paylaşır; bu yüzden son-oturum
    // hafızası mod başına AYRI tutulur, aksi halde claude-app cowork workspace'ine bağlanır.
    val lastCoworkSessionId: String = "",
    val lastCoworkProvider: String = "",
    val lastAgySessionId: String = "",
    // TEK ATIMLIK bağlanma hedefi (claude-app/codex-app/omp): sekme
    // etkinleştirme, bildirim/operasyon ve fork akışları enter*Mode'dan hemen önce
    // yazar; enter* okuyup tüketir. Hedef BOŞKEN enter* hiçbir canlı oturuma
    // otomatik yapışmaz, kurulum/oturum seçici gösterilir. (Eski lastSessionId +
    // "koşan herhangi biri" bağlanması yeni sekmeden ikinci bir oturum açmayı
    // imkânsız kılıyordu: mevcut sohbet kapılıyor, tekilleştirme eski sekmeyi siliyordu.)
    val pendingBindBackend: String = "",
    val pendingBindSessionId: String = "",
    // Bridge'den gelen tek-kaynak default'lar (madde 7).
    val drawerSearchQuery: String = "",
    val drawerShowArchived: Boolean = false,
    val activeState: ActiveStateResult? = null,
    val activeStateLoading: Boolean = false,
    val backendCatalog: BackendCatalogInfo? = null,
    val operations: OperationsResult = OperationsResult(),
    val operationsLoading: Boolean = false,
    val operationsLoaded: Boolean = false,
    val lastSeenOperationEventId: String = "",
    val projectSummaries: List<ProjectSummary> = emptyList(),
    val projectsLoading: Boolean = false,
    val selectedProjectDetail: ProjectDetail? = null,
    val projectDetailLoading: Boolean = false,
    val projectMcpServers: List<McpServer> = emptyList(),
    val projectMcpLoading: Boolean = false,
    val codex: CodexUiState = CodexUiState(),
    val harnessProcesses: List<ProcInfo> = emptyList(),
    val processCount: Int = 0,
    val activeSessionCount: Int = 0,
    val backendSessionCounts: Map<String, Int> = emptyMap(),
    val backendProcessCounts: Map<String, Int> = emptyMap(),
    val truncateAfterIndex: Int? = null,
    val messagePageSize: Int = 100,
    val omp: OmpUiState = OmpUiState(),
    // Dosya gezgini + indirilenler, ayrı aile olarak taşındı.
    val files: FilesUiState = FilesUiState(),
    val visibleBackends: Set<String> = setOf("claude-app", "agy", "codex-app", "omp", "cowork"),
    val backendOrder: List<String> = listOf("claude-app","agy","codex-app","omp","cowork"),
    val search: SearchUiState = SearchUiState(),
    val chatSearch: ChatSearchState = ChatSearchState(),
    val projectDelete: ProjectDeleteUiState = ProjectDeleteUiState(),
)

/** Eski düz provider alanlarıyla yazılmış çağrılar için geçici kaynak-uyumluluk köprüsü. */
@Suppress("LongParameterList")
internal fun RemoteUiState.copy(
    claudeModels: List<ClaudeModel> = claude.models,
    claudeAppSessionId: String = claude.sessionId,
    lastClaudeAppSessionId: String = claude.lastSessionId,
    claudeAppCwd: String = claude.cwd,
    claudeAppModel: String = claude.model,
    claudeAppDefaultModel: String = claude.defaultModel,
    claudeAppPermissionMode: String = claude.permissionMode,
    claudeAppEffort: String = claude.effort,
    claudeAppEfforts: List<String> = claude.efforts,
    claudeAppSetupPending: Boolean = claude.setupPending,
    claudeAppDiskSessions: List<ClaudeDiskSession> = claude.diskSessions,
    claudeAppDiskLoading: Boolean = claude.diskLoading,
    claudeAppInfo: ClaudeAppInfo? = claude.info,
    codexAppSessionId: String = codex.sessionId,
    codexModels: List<CodexModel> = codex.models,
    lastCodexAppSessionId: String = codex.lastSessionId,
    codexAppCwd: String = codex.cwd,
    codexAppModel: String = codex.model,
    codexAppDefaultModel: String = codex.defaultModel,
    codexAppSetupPending: Boolean = codex.setupPending,
    codexAppDiskSessions: List<CodexDiskSession> = codex.diskSessions,
    codexAppDiskLoading: Boolean = codex.diskLoading,
    codexAppPlan: List<PlanItem> = codex.plan,
    codexAppPlanDraft: String = codex.planDraft,
    codexAppInfo: CodexAppInfo? = codex.info,
    codexAppDiff: BackendSessionDiff? = codex.diff,
    codexAppDiffLoading: Boolean = codex.diffLoading,
    codexAppCommands: List<CodexCommand> = codex.commands,
    codexAppSteerMode: Boolean = codex.steerMode,
    codexAppContextPercent: Int = codex.contextPercent,
    codexAppPermissionMode: String = codex.permissionMode,
    codexAppPermissionModes: List<PermissionModeItem> = codex.permissionModes,
    codexAppEffort: String = codex.effort,
    codexAppEfforts: List<String> = codex.efforts,
    codexAppEffortsByModel: Map<String, List<String>> = codex.effortsByModel,
    codexAppDefaultEffort: String = codex.defaultEffort,
    codexAppModelDefaultEfforts: Map<String, String> = codex.modelDefaultEfforts,
    cowork: CoworkUiState = this.cowork,
    lastCoworkSessionId: String = this.lastCoworkSessionId,
    lastCoworkProvider: String = this.lastCoworkProvider,
    backend: String? = this.backend,
    healthOk: Boolean = this.healthOk,
    protocolCompatible: Boolean = this.protocolCompatible,
    device: DeviceUiState = this.device,
    backendOrder: List<String> = this.backendOrder,
    visibleBackends: Set<String> = this.visibleBackends,
): RemoteUiState = copy(
    claude = claude.copy(models = claudeModels, sessionId = claudeAppSessionId, lastSessionId = lastClaudeAppSessionId, cwd = claudeAppCwd, model = claudeAppModel, defaultModel = claudeAppDefaultModel, permissionMode = claudeAppPermissionMode, effort = claudeAppEffort, efforts = claudeAppEfforts, setupPending = claudeAppSetupPending, diskSessions = claudeAppDiskSessions, diskLoading = claudeAppDiskLoading, info = claudeAppInfo),
    codex = codex.copy(sessionId = codexAppSessionId, models = codexModels, lastSessionId = lastCodexAppSessionId, cwd = codexAppCwd, model = codexAppModel, defaultModel = codexAppDefaultModel, setupPending = codexAppSetupPending, diskSessions = codexAppDiskSessions, diskLoading = codexAppDiskLoading, plan = codexAppPlan, planDraft = codexAppPlanDraft, info = codexAppInfo, diff = codexAppDiff, diffLoading = codexAppDiffLoading, commands = codexAppCommands, steerMode = codexAppSteerMode, contextPercent = codexAppContextPercent, permissionMode = codexAppPermissionMode, permissionModes = codexAppPermissionModes, effort = codexAppEffort, efforts = codexAppEfforts, effortsByModel = codexAppEffortsByModel, defaultEffort = codexAppDefaultEffort, modelDefaultEfforts = codexAppModelDefaultEfforts),
    cowork = cowork, lastCoworkSessionId = lastCoworkSessionId, lastCoworkProvider = lastCoworkProvider, backend = backend,
    healthOk = healthOk, protocolCompatible = protocolCompatible, device = device, backendOrder = backendOrder, visibleBackends = visibleBackends,
)

internal fun RemoteUiState(
    claudeAppSessionId: String,
    backend: String? = null,
    messagesList: List<ChatMessage> = emptyList(),
    transcript: String = "",
    running: Boolean = false,
    cowork: CoworkUiState = CoworkUiState(),
    codexAppModel: String = "gpt-5.6-sol",
): RemoteUiState = RemoteUiState(backend = backend, messagesList = messagesList, transcript = transcript, running = running, cowork = cowork, claude = ClaudeUiState(sessionId = claudeAppSessionId), codex = CodexUiState(model = codexAppModel))

internal fun RemoteUiState(
    codexAppSessionId: String,
    backend: String? = null,
    messagesList: List<ChatMessage> = emptyList(),
    cowork: CoworkUiState = CoworkUiState(),
): RemoteUiState = RemoteUiState(backend = backend, messagesList = messagesList, cowork = cowork, codex = CodexUiState(sessionId = codexAppSessionId))

internal fun RemoteUiState(claudeAppEffort: String, backend: String? = null): RemoteUiState =
    RemoteUiState(backend = backend, claude = ClaudeUiState(effort = claudeAppEffort))

internal fun RemoteUiState(codexAppEffort: String, backend: String? = null, cowork: CoworkUiState = CoworkUiState()): RemoteUiState =
    RemoteUiState(backend = backend, cowork = cowork, codex = CodexUiState(effort = codexAppEffort))

internal fun RemoteUiState(codexAppContextPercent: Int, backend: String? = null): RemoteUiState =
    RemoteUiState(backend = backend, codex = CodexUiState(contextPercent = codexAppContextPercent))

// Okuma tarafındaki geçiş köprüsü. Mutasyonlar yalnızca `files.copy(...)` ile
// yapılır; böylece RemoteUiState'in düz alan ailesi yeniden büyümez.
val RemoteUiState.openDownloadFolder get() = files.openDownloadFolder
val RemoteUiState.downloadFolderEntries get() = files.downloadFolderEntries
val RemoteUiState.fileBrowserEntries get() = files.browserEntries
val RemoteUiState.fileBrowserBase get() = files.browserBase
val RemoteUiState.fileBrowserLoading get() = files.browserLoading
val RemoteUiState.fileDriveRoots get() = files.driveRoots
val RemoteUiState.fileBrowserColumns get() = files.browserColumns
val RemoteUiState.lastOpenedPath get() = files.lastOpenedPath
val RemoteUiState.activeDownloadName get() = files.activeDownloadName
val RemoteUiState.activeDownloadProgress get() = files.activeDownloadProgress
val RemoteUiState.downloadRecords get() = files.downloadRecords
val RemoteUiState.phoneEntries get() = files.phoneEntries
val RemoteUiState.phoneBase get() = files.phoneBase
val RemoteUiState.phoneLoading get() = files.phoneLoading
val RemoteUiState.phoneError get() = files.phoneError
val RemoteUiState.pairingCode get() = device.pairingCode
val RemoteUiState.pairingExpiresAt get() = device.pairingExpiresAt
val RemoteUiState.devicePaired get() = device.paired
val RemoteUiState.deviceAuthLoading get() = device.authLoading
val RemoteUiState.agySessionId get() = agy.sessionId
val RemoteUiState.agyModels get() = agy.models
val RemoteUiState.agyCwd get() = agy.cwd
val RemoteUiState.agyModel get() = agy.model
val RemoteUiState.agySetupPending get() = agy.setupPending
val RemoteUiState.agyDiskSessions get() = agy.diskSessions
val RemoteUiState.agyDiskLoading get() = agy.diskLoading
val RemoteUiState.agyOpening get() = agy.opening
val RemoteUiState.coworkOutputs get() = cowork.outputs
val RemoteUiState.coworkWorkspaces get() = cowork.workspaces
val RemoteUiState.coworkWorkspacesLoading get() = cowork.workspacesLoading
val RemoteUiState.coworkProvider get() = cowork.provider
val RemoteUiState.activeCoworkProjectPath get() = cowork.activeProjectPath
val RemoteUiState.selectedCoworkWorkspace get() = cowork.selectedWorkspace
val RemoteUiState.coworkSessions get() = cowork.sessions
val RemoteUiState.coworkSessionsLoading get() = cowork.sessionsLoading
val RemoteUiState.coworkYoloMode get() = cowork.yoloMode
val RemoteUiState.coworkImportBase get() = cowork.importBase
val RemoteUiState.coworkImportEntries get() = cowork.importEntries
val RemoteUiState.coworkImportLoading get() = cowork.importLoading
val RemoteUiState.coworkRootPath get() = cowork.rootPath
val RemoteUiState.coworkFilesStart get() = cowork.filesStart
val RemoteUiState.coworkFilesOpenDir get() = cowork.filesOpenDir
val RemoteUiState.ompSessionId get() = omp.sessionId
val RemoteUiState.ompCwd get() = omp.cwd
val RemoteUiState.ompModel get() = omp.model
val RemoteUiState.ompDefaultModel get() = omp.defaultModel
val RemoteUiState.ompSetupPending get() = omp.setupPending
val RemoteUiState.ompDiskSessions get() = omp.diskSessions
val RemoteUiState.ompDiskLoading get() = omp.diskLoading
val RemoteUiState.ompAvailableModels get() = omp.availableModels
val RemoteUiState.ompPermissionMode get() = omp.permissionMode
val RemoteUiState.ompPermissionModes get() = omp.permissionModes
val RemoteUiState.ompEffort get() = omp.variant

val RemoteUiState.codexAppSessionId get() = codex.sessionId
val RemoteUiState.codexModels get() = codex.models
val RemoteUiState.lastCodexAppSessionId get() = codex.lastSessionId
val RemoteUiState.codexAppCwd get() = codex.cwd
val RemoteUiState.codexAppModel get() = codex.model
val RemoteUiState.codexAppDefaultModel get() = codex.defaultModel
val RemoteUiState.codexAppSetupPending get() = codex.setupPending
val RemoteUiState.codexAppDiskSessions get() = codex.diskSessions
val RemoteUiState.codexAppDiskLoading get() = codex.diskLoading
val RemoteUiState.codexAppPlan get() = codex.plan
val RemoteUiState.codexAppPlanDraft get() = codex.planDraft
val RemoteUiState.codexAppInfo get() = codex.info
val RemoteUiState.codexAppDiff get() = codex.diff
val RemoteUiState.codexAppCommands get() = codex.commands
val RemoteUiState.codexAppSteerMode get() = codex.steerMode
val RemoteUiState.codexAppContextPercent get() = codex.contextPercent
val RemoteUiState.codexAppPermissionMode get() = codex.permissionMode
val RemoteUiState.codexAppPermissionModes get() = codex.permissionModes
val RemoteUiState.codexAppEffort get() = codex.effort
val RemoteUiState.codexAppEfforts get() = codex.efforts
val RemoteUiState.codexAppEffortsByModel get() = codex.effortsByModel
val RemoteUiState.codexAppDefaultEffort get() = codex.defaultEffort
val RemoteUiState.codexAppModelDefaultEfforts get() = codex.modelDefaultEfforts
val RemoteUiState.codexAppGoal get() = codex.goal
val RemoteUiState.claudeModels get() = claude.models
val RemoteUiState.claudeAppSessionId get() = claude.sessionId
val RemoteUiState.lastClaudeAppSessionId get() = claude.lastSessionId
val RemoteUiState.claudeAppCwd get() = claude.cwd
val RemoteUiState.claudeAppModel get() = claude.model
val RemoteUiState.claudeAppDefaultModel get() = claude.defaultModel
val RemoteUiState.claudeAppPermissionMode get() = claude.permissionMode
val RemoteUiState.claudeAppEffort get() = claude.effort
val RemoteUiState.claudeAppEfforts get() = claude.efforts
val RemoteUiState.claudeAppSetupPending get() = claude.setupPending
val RemoteUiState.claudeAppDiskSessions get() = claude.diskSessions
val RemoteUiState.claudeAppDiskLoading get() = claude.diskLoading
val RemoteUiState.claudeAppInfo get() = claude.info
