package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// Cowork backend oturum yaşam döngüsü — RemoteViewModel'den ayrılan delege (madde 11.6c).
// Üç backend delegesinin son ve en büyüğü. Cowork bir sunum + konfigürasyon katmanıdır:
// asıl oturum seçili sağlayıcıya (claude-app / codex-app / opencode-app /
// opencode-app) dağıtılır.
//
// Kapsam: uploadToCoworkWorkspace (Uri çözme VM'de), loadCoworkImportDir,
// importPcIntoCoworkWorkspace, convertCoworkOutputToUdf, downloadCoworkWorkspaceZip,
// enterCoworkMode, loadCoworkWorkspaces, createCoworkWorkspace,
// createLawsuitWorkspaceAndStart, setCoworkWorkspaceMatter, startCoworkSession,
// startCoworkWithAutoWorkspace, switchCoworkProvider, setCoworkModel,
// coworkProjectPath/coworkModelFor/coworkPermissionMode (private),
// applyCoworkSessionResult (private, hub), deleteCoworkSession, deleteCoworkProject,
// loadCoworkSessions, selectCoworkWorkspace, resumeCoworkSession, setCoworkYoloMode,
// openCoworkSocket (internal), refreshCoworkOutputs (internal).
//
// Davranış değişikliği yok. Cowork'un kendi pollJob'u yok — polling seçili sağlayıcının
// sibling delegesine/VM metoduna dağıtılır (startCodexAppPolling/startOpencodeAppPolling/
// claudeAppDelegate.startClaudeAppPolling). Cross-backend state alanları (claudeApp*,
// codexApp*, opencodeApp*) RemoteUiState'te — state/update ile erişilir. Sibling
// socket/polling/info-load callback olarak verilir. openCoworkSocket + refreshCoworkOutputs
// shared infra'dan (applyConversation/handleStreamEnd/applyStreamSnapshot/session-adopt)
// çağrıldığı için internal expose edilir. setCoworkYoloMode sibling permission setter'ları
// client üzerinden doğrudan çağırır (claude/codex/opencode API'leri).
//
// Kalıp önceki delegelerle aynı. uploadToCoworkWorkspace Uri çözmesi VM'de (FileBrowserDelegate
// deseni); downloadCoworkWorkspaceZip downloadRepo gerektirir (Android Context bağımlı,
// arayüze çıkarma madde 13).
class CoworkDelegate(
    private val client: BridgeClient,
    private val scope: CoroutineScope,
    private val prefill: ConversationPrefiller,
    private val state: () -> RemoteUiState,
    private val update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    private val emit: suspend (String) -> Unit,
    private val thoughtDetails: MutableStateFlow<Map<Int, String>>,
    private val openSocket: (provider: String, sessionId: String) -> Unit,
    private val startCodexAppPolling: () -> Unit,
    private val startOpencodeAppPolling: () -> Unit,
    private val startOpencode2Polling: () -> Unit = {},
    private val startOmpPolling: () -> Unit,
    private val startClaudeAppPolling: () -> Unit,
    private val loadCodexAppInfo: () -> Unit,
    private val loadOpencodeAppInfo: () -> Unit,
    private val loadOpencode2Info: () -> Unit = {},
    private val loadOmpInfo: () -> Unit,
    private val loadClaudeAppInfo: () -> Unit,
    private val refreshConversation: (showErrors: Boolean) -> Unit,
    private val updateInput: (String) -> Unit,
    private val sendPrompt: () -> Unit,
    private val downloadRepo: DownloadRepo?,
    private val syncActiveTab: (String, String, String, String) -> Unit,
    private val onActiveCoworkDeleted: () -> Unit,
    // Silinen oturumun acik sekmesini kapatir. Aktif oturum icin
    // onActiveCoworkDeleted yetiyordu, arka plandaki sekme aciik kaliyordu.
    private val removeSessionTabs: (Set<String>) -> Unit = {},
    private val onProjectDeletedCompletely: (CompleteProjectDeleteResult) -> Unit = {},
    private val loadProjects: () -> Unit = {},
) {
    // uploadToCoworkWorkspace(uri) ViewModel'de Uri çözülüp (name, bytes) buraya gelir.
    suspend fun uploadToCoworkWorkspace(name: String, bytes: ByteArray) {
        val dir = state().selectedCoworkWorkspace.ifBlank { coworkProjectPath() }
        if (dir.isBlank()) { emit("Önce bir çalışma alanı seçin"); return }
        if (bytes.isEmpty()) { emit("Dosya okunamadı"); return }
        val result = client.uploadFile(state().settings, dir, name, bytes, coworkOnly = true)
        if (result.ok) emit("Çalışma alanına yüklendi: ${result.name}")
        else emit("Yükleme başarısız: ${result.error}")
    }

    // Cowork "PC'den seç" gezgini: dosyalar dahil dizin listesi (kendi state alanına).
    fun loadCoworkImportDir(root: String = "") = scope.launch {
        update { it.copy(cowork = it.cowork.copy(importLoading = true)) }
        runCatching { client.workerDirs(state().settings, root, includeFiles = true) }
            .onSuccess { dirs ->
                update { it.copy(cowork = it.cowork.copy(importEntries = dirs.dirs, importBase = dirs.base, importLoading = false)) }
            }
            .onFailure {
                update { it.copy(cowork = it.cowork.copy(importLoading = false)) }
                reportError("Klasör yüklenemedi", it)
            }
    }

    // PC'deki seçili dosya/klasörleri workspace'e kopyalat (bridge tarafında cp).
    fun importPcIntoCoworkWorkspace(sources: List<String>) = scope.launch {
        val dir = state().selectedCoworkWorkspace.ifBlank { coworkProjectPath() }
        if (dir.isBlank()) { emit("Önce bir çalışma alanı seçin"); return@launch }
        if (sources.isEmpty()) { emit("Dosya veya klasör seçin"); return@launch }
        runCatching { client.coworkImport(state().settings, dir, sources) }
            .onSuccess { r ->
                val parts = mutableListOf<String>()
                if (r.copied > 0) parts.add("${r.copied} öğe kopyalandı")
                if (r.errors.isNotEmpty()) parts.add("${r.errors.size} hata: ${r.errors.first()}")
                emit(parts.joinToString(" · ").ifBlank { "Kopyalanacak bir şey yok" })
            }
            .onFailure { reportError("İçe aktarma başarısız", it) }
    }

    // Teslimat kartındaki DOCX için tek dokunuşla UDF dönüşümü: aktif cowork
    // oturumuna hazır bir talimat gönderir; dönüşümü ajan udf skill ile yapar.
    fun convertCoworkOutputToUdf(outputName: String) {
        val st = state()
        if (st.backend != "cowork") return
        if (st.running) {
            scope.launch { emit("Tur sürerken dönüşüm başlatılamaz") }
            return
        }
        updateInput(
            "outputs/" + outputName + " dosyasını udf skill kullanarak UYAP UDF formatına çevir " +
            "ve aynı ada sahip .udf dosyası olarak outputs/ klasörüne kaydet. " +
            "İçeriği değiştirme, yalnızca format dönüşümü yap."
        )
        sendPrompt()
    }

    // Workspace'in tamamını KLASÖR olarak telefona indirir: bridge zip'i hazırlar,
    // telefon zip'i indirir ve Download/AgentBridge/<workspace>/ altına açar.
    fun downloadCoworkWorkspaceZip() = scope.launch {
        val repo = downloadRepo ?: run { emit("İndirme servisi kullanılamıyor"); return@launch }
        val dir = state().selectedCoworkWorkspace.ifBlank { coworkProjectPath() }
        if (dir.isBlank()) { emit("Önce bir çalışma alanı seçin"); return@launch }
        emit("Arşiv hazırlanıyor…")
        runCatching { client.coworkArchive(state().settings, dir) }
            .onSuccess { r ->
                val folderName = r.name.removeSuffix(".zip")
                update { it.copy(files = it.files.copy(activeDownloadName = folderName, activeDownloadProgress = 0f)) }
                try {
                    runCatching {
                        repo.downloadAndExtract(client, state().settings, r.path, folderName) { p ->
                            update { it.copy(files = it.files.copy(activeDownloadProgress = p)) }
                        }
                    }.onSuccess { rec ->
                        update { it.copy(files = it.files.copy(activeDownloadName = null, activeDownloadProgress = 0f, downloadRecords = repo.history())) }
                        emit("Çalışma alanı klasör olarak indirildi: ${rec.name}")
                    }.onFailure {
                        update { it.copy(files = it.files.copy(activeDownloadName = null, activeDownloadProgress = 0f)) }
                        reportError("İndirme başarısız", it)
                    }
                } finally {
                    // Bridge geçici zip'ini başarı/hata fark etmeden temizle.
                    runCatching { client.cleanupProjectOutputsArchive(state().settings, r.path) }
                }
            }
            .onFailure { reportError("Arşiv oluşturulamadı", it) }
    }

    // Cowork sağlayıcı kataloğunu (dört sağlayıcının model listesi + default) yükler.
    // enterCoworkMode dışında, model çipi açılırken de çağrılır (loadBackendInfo):
    // hızlı-başlat akışında enterCoworkMode çalışmadığından modeller aksi halde boş
    // kalıyordu.
    fun loadCoworkProviderCatalog(): Job = scope.launch {
        runCatching { client.coworkProviderCatalog(state().settings) }
            .onSuccess { catalog -> update { current ->
                val claude = catalog.firstOrNull { it.id == "claude-app" }
                val codex = catalog.firstOrNull { it.id == "codex-app" }
                val opencode = catalog.firstOrNull { it.id == "opencode2-app" }
                val claudeDefault = claude?.defaultModel.orEmpty().ifBlank { current.claudeAppDefaultModel }
                val codexDefault = codex?.defaultModel.orEmpty().ifBlank { current.codexAppDefaultModel }
                val opencodeDefault = opencode?.defaultModel.orEmpty().ifBlank { current.opencodeAppDefaultModel }
                current.copy(
                    claude = current.claude.copy(models = claude?.models?.map { ClaudeModel(it.label, it.id) }.orEmpty(), defaultModel = claudeDefault, model = if (current.claudeAppModel.isBlank() || current.claudeAppModel == current.claudeAppDefaultModel) claudeDefault else current.claudeAppModel),
                    codex = current.codex.copy(
                        models = codex?.models?.map { CodexModel(it.label, it.id) }.orEmpty(),
                        defaultModel = codexDefault,
                        model = if (current.codexAppModel.isBlank() || current.codexAppModel == current.codexAppDefaultModel) codexDefault else current.codexAppModel,
                    ),
                    opencode = current.opencode.copy(
                        availableModels = opencode?.models.orEmpty(),
                        defaultModel = opencodeDefault,
                        model = if (current.opencodeAppModel.isBlank() || current.opencodeAppModel == current.opencodeAppDefaultModel) opencodeDefault else current.opencodeAppModel,
                    ),
                )
            } }
            .onFailure { reportError("Cowork modelleri yuklenemedi", it) }
    }

    fun enterCoworkMode() = scope.launch {
        thoughtDetails.value = emptyMap()
        // BAĞLANMA HEDEFİ TEK ATIMLIK (pendingBind) — `lastCoworkSessionId` DEĞİL.
        //
        // Cowork, diğer dört sağlayıcının çoktan geçtiği düzeltmenin dışında
        // kalmıştı: her girişte `lastCoworkSessionId`e geri bağlanıyordu. Boş
        // sekmede "Cowork" çipine dokunmak `enterCoworkMode` çağırıyor, o da
        // AYRILDIĞIN oturuma geri bağlanıyor; oturum kimliği dolar dolmaz
        // yeni-oturum ekranı yerini eski sohbete bırakıyordu. Yani bir cowork
        // alanında ikinci bir oturum açmak imkânsızdı (kullanıcı bildirdi
        // 20.08.2026: "cowork seçip bir çalışma alanı seçip başlat diyince yine
        // açılmıyor").
        //
        // Aynı hata claude-app'te yaşanmış ve aynı şekilde çözülmüştü — oradaki
        // not birebir geçerli: "Eski lastSessionId bağlanması her girişte mevcut
        // sohbeti kapıyordu, yeni sekmeden ikinci bir oturum açılamıyordu."
        //
        // Hedefi sekme geçişi yazıyor ([TabsDelegate.activateTab]); hedef yoksa
        // ekran seçicide/yeni-oturumda kalır ve kullanıcı seçer. `lastCowork*`
        // alanları duruyor: sağlayıcıyı ve "en son neredeydik" bilgisini başka
        // yerler okuyor, yalnız OTOMATİK BAĞLANMA kaynağı olmaktan çıktılar.
        val warmSid = state().pendingBindSessionId
            .takeIf { state().pendingBindBackend == "cowork" }.orEmpty()
        val warm = prefill.warm(
            "cowork:${normalizeCoworkProvider(state().lastCoworkProvider)}",
            warmSid,
        )
        update {
            it.copy(
                backend = "cowork",
                wsConnected = false,
                claude = it.claude.copy(setupPending = false),
                currentSession = if (warm != null) "Cowork" else "",
                messagesList = warm?.messages ?: emptyList(),
                transcript = warm?.transcript ?: "",
                staleConversation = warm != null,
                running = false,
                awaitingApproval = false,
                awaitingFirstOutput = false,
                cowork = it.cowork.copy(outputs = emptyList(), activeProjectPath = "", workspacesLoading = true),
            )
        }
        // Dört sağlayıcının model listesi ve default'u tek Cowork kataloğundan gelir.
        loadCoworkProviderCatalog().join()
        loadCoworkWorkspaces()
        // Sekme geçişinin işaret ettiği canlı cowork oturumuna bağlan. Sağlayıcıya
        // duyarlı (lastCoworkProvider, sekmeyle birlikte yazılır): codex/opencode/omp
        // oturumları da tanınır. Hedef TÜKETİLİR — bir sonraki girişte tekrar
        // bağlanmasın (yukarıdaki uzun not).
        val bekleyen = state()
        val prior = bekleyen.pendingBindSessionId
            .takeIf { bekleyen.pendingBindBackend == "cowork" }.orEmpty()
        if (prior.isNotBlank()) {
            update { it.copy(pendingBindBackend = "", pendingBindSessionId = "") }
            val providerId = normalizeCoworkProvider(state().lastCoworkProvider)
            val live = runCatching { client.listBackendSessions(state().settings, providerId) }.getOrDefault(emptyList())
            val target = live.firstOrNull { it.id == prior }
            if (target != null) {
                when (providerId) {
                    "codex-app" -> {
                        update {
                            it.copy(
                                cowork = it.cowork.copy(provider = "codex-app", activeProjectPath = target.cwd),
                                // Diff oturuma ait: başka bir oturuma geçerken
                                // taşınırsa "Değişiklikler" ÖNCEKİ koşunun dosya
                                // listesini gösterir ve onu yalanlayacak bir şey
                                // yok (liste kendiliğinden tazelenmiyor).
                                codex = it.codex.copy(sessionId = target.id, cwd = target.cwd.ifBlank { it.codex.cwd }, model = target.model.ifBlank { it.codex.model }, diff = null, diffLoading = false),
                                currentSession = "Cowork",
                                model = "Cowork · ${target.model.ifBlank { it.codexAppModel }}",
                            )
                        }
                        openSocket("codex-app", target.id)
                        startCodexAppPolling()
                        refreshConversation(false)
                    }
                    "opencode2-app" -> {
                        update {
                            it.copy(
                                cowork = it.cowork.copy(provider = "opencode2-app", activeProjectPath = target.cwd),
                                opencode = it.opencode.copy(
                                    sessionId = target.id,
                                    cwd = target.cwd.ifBlank { it.opencode.cwd },
                                    model = target.model.ifBlank { it.opencode.model },
                                    // Codex dalıyla aynı gerekçe: diff oturuma ait.
                                    diff = null,
                                    diffLoading = false,
                                ),
                                currentSession = "Cowork",
                                model = "Cowork · ${target.model.ifBlank { it.opencodeAppModel }}",
                            )
                        }
                        openSocket("opencode2-app", target.id)
                        startOpencodeAppPolling()
                        refreshConversation(false)
                    }
                    // OMP DALI EKSİKTİ. `normalizeCoworkProvider` omp'yi geçerli
                    // sayıyor ve `applyCoworkSessionResult` onu ayrı ele alıyor;
                    // burada yoktu, yani cowork/omp sekmesine dönünce OMP'nin
                    // oturum kimliği CLAUDE durumuna yazılıyordu (else dalı).
                    // Sonuç: yanlış sokete bağlanma ve claude'un oturumu sanılan
                    // ölü bir kimlik.
                    "omp" -> {
                        update {
                            it.copy(
                                cowork = it.cowork.copy(provider = "omp", activeProjectPath = target.cwd),
                                omp = it.omp.copy(
                                    sessionId = target.id,
                                    cwd = target.cwd.ifBlank { it.omp.cwd },
                                    model = target.model.ifBlank { it.omp.model },
                                ),
                                currentSession = "Cowork",
                                model = "Cowork · ${target.model.ifBlank { it.omp.model }}",
                            )
                        }
                        openSocket("omp", target.id)
                        startOmpPolling()
                        refreshConversation(false)
                    }
                    else -> {
                        update {
                            it.copy(
                                cowork = it.cowork.copy(provider = "claude-app", activeProjectPath = target.cwd),
                                claude = it.claude.copy(sessionId = target.id, cwd = target.cwd.ifBlank { it.claude.cwd }, model = target.model.ifBlank { it.claude.model }, info = null),
                                currentSession = "Cowork",
                                model = "Cowork · ${target.model.ifBlank { it.claudeAppModel }}",
                            )
                        }
                        openSocket("claude-app", target.id)
                        startClaudeAppPolling()
                        refreshConversation(false)
                        loadClaudeAppInfo()
                    }
                }
            } else {
                // Dönülecek oturum artık canlı değil: prefill tazelenmeyecek, bayrağı düşür.
                update { it.copy(staleConversation = false) }
            }
        }
    }

    fun loadCoworkWorkspaces() = scope.launch {
        update { it.copy(cowork = it.cowork.copy(workspacesLoading = true)) }
        runCatching { client.coworkProjects(state().settings) }
            .onSuccess { page ->
                update {
                    it.copy(
                        cowork = it.cowork.copy(
                            workspaces = page.projects.map { p -> CoworkWorkspace(p.name, p.path, p.matter) },
                            rootPath = page.root.ifBlank { it.cowork.rootPath },
                            workspacesLoading = false,
                        ),
                    )
                }
            }
            .onFailure {
                update { it.copy(cowork = it.cowork.copy(workspacesLoading = false)) }
                reportError("Çalışma alanı listesi yüklenemedi", it)
            }
    }

    fun createCoworkWorkspace(
        name: String,
        template: String = "",
        onResult: (Boolean) -> Unit = {},
    ) = scope.launch {
        val clean = name.trim()
        if (clean.isBlank()) {
            emit("Çalışma alanı adı boş")
            onResult(false)
            return@launch
        }
        runCatching { client.coworkCreateProject(state().settings, clean, template) }
            .onSuccess {
                emit("Çalışma alanı oluşturuldu: $clean")
                loadCoworkWorkspaces()
                onResult(true)
            }
            .onFailure {
                reportError("Çalışma alanı oluşturulamadı", it)
                onResult(false)
            }
    }

    // Dava dosyası: cowork workspace seçicisindeki "Dava dosyası" şablonuyla tetiklenir —
    // dava-dosyasi şablonlu workspace oluşturur, oturumu açar, hazır talimatı doldurur.
    fun createLawsuitWorkspaceAndStart(name: String) = scope.launch {
        val clean = name.trim()
        if (clean.isBlank()) { emit("Dava adı boş"); return@launch }
        update { it.copy(sending = true, running = true) }
        runCatching { client.coworkCreateProject(state().settings, clean, "dava-dosyasi") }
            .onSuccess { project ->
                emit("Dava dosyası oluşturuldu: $clean")

                // Select cowork mode
                update { it.copy(backend = "cowork") }

                // Start session in the newly created project
                val provider = normalizeCoworkProvider(state().coworkProvider)
                val model = coworkModelFor(provider)
                val nativeMode = if (state().coworkYoloMode) "yolo" else null

                runCatching { client.coworkStartSession(state().settings, project.path, provider, model, null, nativeMode) }
                    .onSuccess { result ->
                        applyCoworkSessionResult(result, project.path, provider, model)
                        // Hazır prompt doldurma kaldırıldı (kullanıcı geri bildirimi:
                        // kullanışsız) — yönlendirme zaten workspace CLAUDE.md'sinde.
                    }
                    .onFailure {
                        update { it.copy(sending = false, running = false) }
                        reportError("Dava oturumu başlatılamadı", it)
                    }
            }
            .onFailure {
                update { it.copy(sending = false, running = false) }
                reportError("Dava dosyası oluşturulamadı", it)
            }
    }

    fun setCoworkWorkspaceMatter(path: String, matter: String) = scope.launch {
        runCatching { client.coworkSetMatter(state().settings, path, matter) }
            .onSuccess { emit("Etiket kaydedildi"); loadCoworkWorkspaces() }
            .onFailure { reportError("Etiket kaydedilemedi", it) }
    }

    // startClaudeAppSession'in cowork varyanti: workspace'i cwd yapar, cowork:true gönderir.
    // Claude için onaylar zaten interaktif (cowork'ün kalbi). Codex/OpenCode için
    // permissionMode yolo toggle'dan gelir (madde 6) — açık opt-in değilse bridge 'ask' varsayıyor.
    // Drawer'daki "Başlat" tuşu: aynı workspace içinde HER ZAMAN yeni bir oturum açar
    // (forceNew=true). Aktif oturum varken bile taze oturum başlatılır; mevcut oturuma
    // dönmek isteyen kullanıcı drawer'daki oturum kaydına dokunur (resumeCoworkSession).
    // forceNew olmadan bridge en son oturumu adopt ediyordu → "yeni oturum açılmıyor" hatası.
    fun startCoworkSession(workspacePath: String, model: String = "") = scope.launch {
        val cleanCwd = workspacePath.trim()
        // Workspace seçilmeden "Başlat" → rastgele iki kelimelik workspace oluşturup başlat.
        if (cleanCwd.isBlank()) { startCoworkWithAutoWorkspace(); return@launch }
        val provider = normalizeCoworkProvider(state().coworkProvider)
        val cleanModel = model.ifBlank { coworkModelFor(provider) }
        val nativeMode = if (state().coworkYoloMode) "yolo" else null
        runCatching { client.coworkStartSession(state().settings, cleanCwd, provider, cleanModel, null, nativeMode, forceNew = true) }
            .onSuccess { result -> applyCoworkSessionResult(result, cleanCwd, provider, cleanModel) }
            .onFailure { reportError("Cowork oturumu başlatılamadı", it) }
    }

    // Rastgele iki kelimelik workspace adı (ör. flying-potato). Kullanıcı workspace
    // seçmeden başlattığında veya doğrudan prompt yazdığında otomatik oluşturmak için.
    private val WS_ADJECTIVES = listOf(
        "flying","brave","calm","clever","cosmic","curious","dizzy","eager","fuzzy","gentle",
        "happy","jolly","lucky","mellow","nimble","quiet","rapid","shiny","silent","sleepy",
        "snappy","sunny","swift","witty","zesty","bold","breezy","chill","daring","frosty",
        "golden","hidden","icy","jazzy","keen","lively","misty","noble","proud","quirky",
    )
    private val WS_NOUNS = listOf(
        "potato","otter","falcon","maple","comet","pixel","cactus","walrus","noodle","panda",
        "rocket","pebble","lemon","badger","willow","cobra","ember","ferret","gecko","hazel",
        "iris","jaguar","koala","llama","mango","nebula","olive","puffin","quartz","raven",
        "salmon","tiger","urchin","viper","walnut","yak","zebra","acorn","bison","cedar",
    )
    private fun randomWorkspaceName(): String = "${WS_ADJECTIVES.random()}-${WS_NOUNS.random()}"

    // Yeni bir cowork workspace'i oluşturup oturumu başlatır. name boşsa rastgele iki
    // kelimelik ad üretilir; kullanıcı NewSessionScreen'de ad yazdıysa o kullanılır.
    // pendingPrompt verilirse oturum açıldıktan sonra o metni otomatik gönderir.
    fun startCoworkWithAutoWorkspace(pendingPrompt: String? = null, name: String = ""): Job = scope.launch {
        val wsName = name.trim().ifBlank { randomWorkspaceName() }
        update { it.copy(backend = "cowork") }
        runCatching { client.coworkCreateProject(state().settings, wsName, "") }
            .onSuccess { project ->
                emit("Yeni workspace: ${project.name.ifBlank { wsName }}")
                loadCoworkWorkspaces()
                val provider = normalizeCoworkProvider(state().coworkProvider)
                val cleanModel = coworkModelFor(provider)
                val nativeMode = if (state().coworkYoloMode) "yolo" else null
                runCatching { client.coworkStartSession(state().settings, project.path, provider, cleanModel, null, nativeMode) }
                    .onSuccess { result ->
                        applyCoworkSessionResult(result, project.path, provider, cleanModel)
                        if (!pendingPrompt.isNullOrBlank()) {
                            update { it.copy(input = pendingPrompt) }
                            sendPrompt()
                        }
                    }
                    .onFailure { reportError("Cowork oturumu başlatılamadı", it) }
            }
            .onFailure { reportError("Çalışma alanı oluşturulamadı", it) }
    }

    fun switchCoworkProvider(provider: String) = scope.launch {
        val cleanProvider = normalizeCoworkProvider(provider)
        val cleanModel = coworkModelFor(cleanProvider)
        val projectPath = coworkProjectPath()
        val label = coworkProviderLabel(cleanProvider)
        val applyProviderUi: (RemoteUiState) -> RemoteUiState = {
            when (cleanProvider) {
                "codex-app" -> it.copy(cowork = it.cowork.copy(provider = cleanProvider), codex = it.codex.copy(model = cleanModel), model = "Cowork / $label / $cleanModel")
                "opencode2-app" -> it.copy(cowork = it.cowork.copy(provider = cleanProvider), opencode = it.opencode.copy(model = cleanModel), model = "Cowork / $label / $cleanModel")
                "omp" -> it.copy(cowork = it.cowork.copy(provider = cleanProvider), omp = it.omp.copy(model = cleanModel), model = "Cowork / $label / $cleanModel")
                else -> it.copy(cowork = it.cowork.copy(provider = cleanProvider), claude = it.claude.copy(model = cleanModel), model = "Cowork / $label / $cleanModel")
            }
        }
        if (projectPath.isBlank()) {
            update(applyProviderUi)
            return@launch
        }
        if (state().running) {
            emit("Tur sürerken sağlayıcı değiştirilemez")
            return@launch
        }
        runCatching { client.coworkSwitchSession(state().settings, projectPath, cleanProvider, cleanModel, null, coworkPermissionMode()) }
            .onSuccess { result ->
                applyCoworkSessionResult(result, projectPath, cleanProvider, cleanModel)
            }
            .onFailure { error ->
                reportError("Cowork sağlayıcısı değiştirilemedi", error)
            }
    }

    fun setCoworkModel(id: String) = scope.launch {
        val provider = normalizeCoworkProvider(state().coworkProvider)
        val cleanModel = id.ifBlank { coworkModelFor(provider) }
        val projectPath = coworkProjectPath()
        val sessionId = coworkSessionIdFor(provider)
        val label = coworkProviderLabel(provider)
        if (state().running) {
            emit("Tur surerken model degistirilemez")
            return@launch
        }
        if (projectPath.isBlank()) {
            update {
                when (provider) {
                    "codex-app" -> it.copy(codex = it.codex.copy(model = cleanModel), model = "Cowork / $label / $cleanModel")
                    "opencode2-app" -> it.copy(opencode = it.opencode.copy(model = cleanModel), model = "Cowork / $label / $cleanModel")
                    "omp" -> it.copy(omp = it.omp.copy(model = cleanModel), model = "Cowork / $label / $cleanModel")
                    else -> it.copy(claude = it.claude.copy(model = cleanModel), model = "Cowork / $label / $cleanModel")
                }
            }
            return@launch
        }
        // Model değişimi aktif Cowork oturumunu yerinde günceller. sessionId
        // gönderilmezse bridge aynı workspace'teki "en son" provider oturumunu
        // seçer; birden çok Claude oturumu varken bu başka sekmeye sıçratır.
        runCatching {
            client.coworkSwitchSession(
                state().settings,
                projectPath,
                provider,
                cleanModel,
                sessionId.takeIf { it.isNotBlank() },
                coworkPermissionMode(),
            )
        }
            .onSuccess { result -> applyCoworkSessionResult(result, projectPath, provider, cleanModel) }
            .onFailure { reportError("Cowork modeli degistirilemedi", it) }
    }

    // Aktif cowork workspace yolu: hangi provider'la başlatıldıysa onun cwd'sinde durur.
    // Shared infra (refreshSession cowork branch) de çağırdığı için internal.
    fun coworkProjectPath(): String =
        state().activeCoworkProjectPath

    private fun coworkModelFor(provider: String): String = when (normalizeCoworkProvider(provider)) {
        "codex-app" -> state().codexAppModel.ifBlank { state().codexAppDefaultModel }
        "opencode2-app" -> state().opencode.model.ifBlank { state().opencode.defaultModel }
        "omp" -> state().ompModel.ifBlank { state().ompDefaultModel }
        else -> state().claudeAppModel.ifBlank { state().claudeAppDefaultModel }
    }

    // Sağlayıcının aktif oturum kimliği. Üç ayrı yerde (model değişimi, oturum
    // silme, devir) aynı eşleme tekrarlanıyordu; yeni sağlayıcı eklerken biri
    // atlanınca sessizce yanlış oturuma dokunuyor.
    private fun coworkSessionIdFor(provider: String): String = when (normalizeCoworkProvider(provider)) {
        "codex-app" -> state().codexAppSessionId
        "opencode2-app" -> state().opencode.sessionId
        "omp" -> state().ompSessionId
        else -> state().claudeAppSessionId
    }

    // Cowork yolo (otomatik onay) — tüm provider'lar için, VARSAYILAN AÇIK
    // (CoworkUiState.yoloMode = true; kullanıcı kararı 21.07). Bridge eşler:
    // codex 'yolo'→danger-full-access, opencode 'yolo'→native yolo,
    // claude 'yolo'→bypassPermissions. null → onay modu (codex/opencode 'ask',
    // claude default → interaktif onay) — kullanıcı "Onay"ı seçerse.
    private fun coworkPermissionMode(): String? =
        if (state().coworkYoloMode) "yolo" else null

    fun applyCoworkSessionResult(result: CoworkStartResult, projectPath: String, fallbackProvider: String, fallbackModel: String) {
        val activeProvider = normalizeCoworkProvider(result.provider.ifBlank { fallbackProvider })
        val activeModel = result.model.ifBlank { fallbackModel.ifBlank { coworkModelFor(activeProvider) } }
        val label = coworkProviderLabel(activeProvider)
        thoughtDetails.value = emptyMap()
        update {
            it.copy(
                backend = "cowork",
                cowork = it.cowork.copy(provider = activeProvider, activeProjectPath = projectPath, selectedWorkspace = projectPath, outputs = result.outputs),
                claude = it.claude.copy(sessionId = if (activeProvider == "claude-app") result.sessionId else "", cwd = projectPath, model = if (activeProvider == "claude-app") activeModel else it.claude.model, permissionMode = if (activeProvider == "claude-app") "" else it.claude.permissionMode, info = null),
                // diff = null İKİSİNDE DE: oturum burada her hâlükârda değişiyor
                // (yeni cowork oturumu ya da başka sağlayıcı), taşınan liste
                // "bu oturum şunları değiştirdi" diye YALAN söylerdi.
                codex = it.codex.copy(sessionId = if (activeProvider == "codex-app") result.sessionId else "", cwd = projectPath, model = if (activeProvider == "codex-app") activeModel else it.codex.model, diff = null, diffLoading = false),
                opencode = it.opencode.copy(
                    sessionId = if (activeProvider == "opencode2-app") result.sessionId else "",
                    cwd = projectPath,
                    model = if (activeProvider == "opencode2-app") activeModel else it.opencode.model,
                    permissionMode = if (activeProvider == "opencode2-app") (if (it.cowork.yoloMode) "yolo" else "ask") else it.opencode.permissionMode,
                    diff = null,
                    diffLoading = false,
                ),
                omp = it.omp.copy(
                    sessionId = if (activeProvider == "omp") result.sessionId else "",
                    cwd = projectPath,
                    model = if (activeProvider == "omp") activeModel else it.omp.model,
                ),
                model = "Cowork / $label / $activeModel",
                currentSession = "Cowork",
                messagesList = emptyList(),
                contextTokens = 0, contextWindow = 0,
                transcript = "",
                running = false,
                awaitingApproval = false,
                approval = null,
                wsConnected = false,
            )
        }
        openSocket(activeProvider, result.sessionId)
        when (activeProvider) {
            "codex-app" -> startCodexAppPolling()
            "opencode2-app" -> startOpencode2Polling()
            "omp" -> startOmpPolling()
            else -> startClaudeAppPolling()
        }
        refreshConversation(false)
        when (activeProvider) {
            "codex-app" -> loadCodexAppInfo()
            "opencode2-app" -> loadOpencode2Info()
            "omp" -> loadOmpInfo()
            else -> loadClaudeAppInfo()
        }
        syncActiveTab("cowork", activeProvider, result.sessionId, generateTabTitle("cowork", activeProvider, projectPath, activeModel))
    }

    fun deleteCoworkSession(projectPath: String, id: String) = scope.launch {
        val proj = projectPath.ifBlank { state().selectedCoworkWorkspace }
        val activeId = coworkSessionIdFor(state().coworkProvider)
        val deletesActive = proj == coworkProjectPath() && id == activeId
        runCatching { client.coworkDeleteSession(state().settings, proj, id) }
            .onSuccess {
                emit("Oturum silindi")
                removeSessionTabs(setOf(id))
                if (deletesActive) onActiveCoworkDeleted()
                loadCoworkSessions(proj)
            }
            .onFailure { reportError("Oturum silinemedi", it) }
    }

    // Cowork projesini (workspace klasörünü) hem app listesinden hem diskten tamamen sil.
    fun deleteCoworkProject(projectPath: String) = scope.launch {
        val deletesActive = projectPath == coworkProjectPath()
        runCatching { client.coworkDeleteProject(state().settings, projectPath) }
            .onSuccess { result ->
                emit("Proje silindi")
                onProjectDeletedCompletely(result)
                // Silinen proje seçiliyse seçimi temizle; workspace listesini tazele.
                if (state().selectedCoworkWorkspace == projectPath) {
                    update { it.copy(cowork = it.cowork.copy(selectedWorkspace = "")) }
                }
                if (deletesActive) onActiveCoworkDeleted()
                loadCoworkWorkspaces()
                loadProjects()
            }
            .onFailure { reportError("Proje silinemedi", it) }
    }

    // Çekmecenin "tüm oturumlar" görünümü için: BÜTÜN workspace'lerin cowork
    // kayıtları tek listede toplanır (loadCoworkSessions tek workspace'e bakar).
    // cwd boş dönen kayıtlar workspace yoluyla doldurulur ki resume doğru
    // klasörü hedeflesin.
    fun loadAllCoworkSessions() = scope.launch {
        update { it.copy(cowork = it.cowork.copy(sessionsLoading = true)) }
        runCatching {
            client.coworkProjects(state().settings).projects.flatMap { project ->
                runCatching { client.coworkSessions(state().settings, project.path) }
                    .getOrDefault(emptyList())
                    .map { record -> if (record.cwd.isBlank()) record.copy(cwd = project.path) else record }
            }
        }
            .onSuccess { list -> update { it.copy(cowork = it.cowork.copy(sessions = list, sessionsLoading = false)) } }
            .onFailure { update { it.copy(cowork = it.cowork.copy(sessionsLoading = false)) }; reportError("Cowork oturumları yüklenemedi", it) }
    }

    fun loadCoworkSessions(projectPath: String = state().selectedCoworkWorkspace) = scope.launch {
        if (projectPath.isBlank()) {
            update { it.copy(cowork = it.cowork.copy(sessions = emptyList(), sessionsLoading = false)) }
            return@launch
        }
        update { it.copy(cowork = it.cowork.copy(sessionsLoading = true)) }
        runCatching {
            // Boş cwd burada da doldurulur (loadAllCoworkSessions'daki ile aynı
            // sebep): çekmece repo kipinde kayıtları cwd'ye göre süzüyor, boş cwd
            // kaydı hiçbir zaman eşleşmez ve resume de yanlış klasörü hedefler.
            client.coworkSessions(state().settings, projectPath)
                .map { record -> if (record.cwd.isBlank()) record.copy(cwd = projectPath) else record }
        }
            .onSuccess { list -> update { it.copy(cowork = it.cowork.copy(sessions = list, sessionsLoading = false)) } }
            .onFailure { update { it.copy(cowork = it.cowork.copy(sessionsLoading = false)) }; reportError("Cowork oturumları yüklenemedi", it) }
    }

    // Workspace seçimi: seçimi sabitler ve o projenin oturumlarını yükler.
    // Drawer "workspace selection stays fixed while listing sessions" gereksinimi.
    fun selectCoworkWorkspace(path: String) {
        update { it.copy(cowork = it.cowork.copy(selectedWorkspace = path)) }
        loadCoworkSessions(path)
    }

    // Drawer'daki bir .cowork oturum kaydına dokunulunca: o belirli sessionId'yi
    // resume eder (provider + sessionId hedefli) ve aktif oturuma uygular.
    fun resumeCoworkSession(record: CoworkSessionRecord) = scope.launch {
        val projectPath = record.cwd.ifBlank { state().selectedCoworkWorkspace }
        if (projectPath.isBlank()) { emit("Çalışma alanı seçin"); return@launch }
        // "Tur sürerken oturum değiştirilemez" korumasi KALDIRILDI: Merkez'den bir
        // oturuma dokunmak calisan turu ezmiyor, cunku akisin sonundaki
        // syncActiveTab dolu sekmenin uzerine YAZMIYOR — oturum baska sekmedeyse
        // oraya gecer, hicbirinde yoksa yeni sekme acar. Calisan tur kendi
        // sekmesinde devam eder. Zaten activateTab de tur surerken sekme
        // degisimini engellemiyordu; bu koruma o davranisla tutarsizdi ve
        // Merkez'den oturum acmayi tumden imkansiz kiliyordu (canli sikayet).
        val model = record.model.ifBlank { coworkModelFor(record.provider) }
        // Resume: only preserve a stored permissionMode when the bridge marked it
        // as an explicit user choice; implicit records fall back to the current
        // toggle / safe bridge default. Her iki provider için de geçerli.
        val mode = if (record.permissionModeExplicit) record.permissionMode.ifBlank { coworkPermissionMode() }
                   else coworkPermissionMode()
        runCatching { client.coworkStartSession(state().settings, projectPath, record.provider, model, record.sessionId, mode) }
            .onSuccess { result -> applyCoworkSessionResult(result, projectPath, record.provider, model) }
            .onFailure { reportError("Cowork oturumu resume edilemedi", it) }
    }

    // Yolo (otomatik onay) toggle'ı — tüm provider'lar için (madde 6). Aktif cowork
    // oturumu varsa modu hemen uygular (best effort; codex/opencode tur sürerken reddeder,
    // o durumda bir sonraki oturum başlangıcında zaten toggle'dan uygulanır).
    fun setCoworkYoloMode(enabled: Boolean) {
        update { it.copy(
            cowork = it.cowork.copy(yoloMode = enabled),
            opencode = if (normalizeCoworkProvider(it.coworkProvider) == "opencode2-app")
                it.opencode.copy(permissionMode = if (enabled) "yolo" else "ask") else it.opencode,
        ) }
        val st = state()
        if (st.backend != "cowork") return
        scope.launch {
            when (normalizeCoworkProvider(st.coworkProvider)) {
                "codex-app" -> {
                    val sid = st.codexAppSessionId
                    if (sid.isNotBlank()) runCatching {
                        client.codexAppSetPermissionMode(st.settings, sid, if (enabled) "yolo" else "ask")
                    }
                }
                "opencode2-app" -> {
                    val sid = st.opencodeAppSessionId
                    if (sid.isNotBlank()) runCatching {
                        client.opencodeAppSetPermissionMode(st.settings, sid, if (enabled) "yolo" else "ask")
                    }
                }
                "omp" -> {
                    val sid = st.ompSessionId
                    if (sid.isNotBlank()) runCatching {
                        client.ompSetPermissionMode(st.settings, sid, if (enabled) "yolo" else "ask")
                    }
                }
                else -> {
                    val sid = st.claudeAppSessionId
                    if (sid.isNotBlank()) runCatching {
                        client.claudeAppSetPermissionMode(st.settings, sid, if (enabled) "bypassPermissions" else "")
                    }
                }
            }
        }
    }

    fun handoffCoworkTo(provider: String) = scope.launch {
        val st = state()
        val fromProvider = normalizeCoworkProvider(st.coworkProvider)
        val toProvider = normalizeCoworkProvider(provider)
        if (fromProvider == toProvider) { emit("Devralacak farklı bir sağlayıcı seçin"); return@launch }
        if (st.running) { emit("Tur sürerken devir yapılamaz"); return@launch }
        val projectPath = coworkProjectPath()
        if (projectPath.isBlank()) { emit("Önce bir Cowork oturumu başlatın"); return@launch }
        val sessionId = coworkSessionIdFor(fromProvider)
        if (sessionId.isBlank()) { emit("Devredilecek aktif oturum bulunamadı"); return@launch }
        update { it.copy(sending = true) }
        runCatching {
            client.coworkCreateHandoff(st.settings, projectPath, fromProvider, sessionId, toProvider)
            val model = coworkModelFor(toProvider)
            val result = client.coworkSwitchSession(st.settings, projectPath, toProvider, model, null, coworkPermissionMode())
            result to model
        }.onSuccess { (result, model) ->
            applyCoworkSessionResult(result, projectPath, toProvider, model)
            updateInput(
                ".cowork/handoff.md dosyasındaki devir notunu oku. Workspace'in mevcut durumunu doğrula, " +
                    "tamamlanmış işleri tekrarlama ve açık kalan işlerden devam et. Önce kısa bir devralma özeti ver."
            )
            emit("${coworkProviderLabel(toProvider)} devralıyor")
            sendPrompt()
        }.onFailure { reportError("Cowork devri başarısız", it) }
        update { it.copy(sending = false) }
    }

    // Shared infra (applyConversation/handleStreamEnd/applyStreamSnapshot/session-adopt)
    // ve ClaudeAppDelegate bu yollarla socket/outputs tazeler — internal expose.
    internal fun openCoworkSocket(provider: String, sessionId: String) {
        openSocket(provider, sessionId)
    }

    fun refreshCoworkOutputs(showErrors: Boolean = false) = scope.launch {
        val st = state()
        val projectPath = coworkProjectPath()
        if (st.backend != "cowork" || projectPath.isBlank()) return@launch
        runCatching { client.coworkProject(st.settings, projectPath) }
            .onSuccess { details -> update { it.copy(cowork = it.cowork.copy(outputs = details.outputs)) } }
            .onFailure { if (showErrors) reportError("Cowork outputs yenilenemedi", it) }
    }

    private suspend fun reportError(prefix: String, throwable: Throwable) {
        emit("$prefix: ${throwable.message ?: "unknown error"}")
    }
}
