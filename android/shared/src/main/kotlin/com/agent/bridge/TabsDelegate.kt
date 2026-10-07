package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class TabsDelegate(
    private val prefs: KeyValueStore,
    private val scope: CoroutineScope,
    private val state: () -> RemoteUiState,
    private val update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    private val emit: suspend (String) -> Unit,
    private val client: BridgeClient,
    private val goToLanding: () -> Unit,
    private val enterAgyMode: () -> Unit,
    private val enterClaudeAppMode: () -> Unit,
    private val enterCodexAppMode: () -> Unit,
    private val enterOpencodeAppMode: () -> Unit,
    // Varsayilan BOS DEGIL: dal eklenmeden birakilirsa sekmeye donmek sessizce
    // hicbir sey yapar (26.09.2026'da opencode2'de tam bu oldu).
    private val enterOpencode2AppMode: () -> Unit,
    private val enterOmpMode: () -> Unit = {},
    private val enterCoworkMode: () -> Unit,
    private val onTabsChanged: () -> Unit = {},
    private val singleSession: Boolean = false,
) {

    private fun normalizedPath(value: String): String = value.trim().replace('\\', '/').trimEnd('/').lowercase()

    /**
     * Diskten silinen oturumların açık sekmelerini kapatır.
     *
     * Toplu silme (projeler ekranı) bunu zaten yapıyordu, çekmeceden tek tek
     * silme yapmıyordu. Kalan sekme artık var olmayan bir id'yi tazeliyor ve
     * HATA DA VERMİYOR — kullanıcıya "oturum silinmemiş" gibi görünüyor.
     * 1 Ağu 2026'da canlıda böyle rapor edildi; kayıt gerçekten gitmişti.
     */
    fun removeSessionTabs(deletedSessionIds: Set<String>) {
        if (deletedSessionIds.isEmpty()) return
        // projectPath boş → yalnız sessionId eşleşmesi; cwd kuralı devre dışı.
        removeProjectTabs("", deletedSessionIds)
    }

    fun removeProjectTabs(projectPath: String, deletedSessionIds: Set<String>) {
        val root = normalizedPath(projectPath)
        var removedActiveTab = false
        update { current ->
            val removedTabIds = current.visibleTabs.filter { tab ->
                val cwd = normalizedPath(current.tabStatuses[tab.id]?.cwd.orEmpty())
                tab.sessionId in deletedSessionIds || (root.isNotBlank() && (cwd == root || cwd.startsWith("$root/")))
            }.mapTo(mutableSetOf()) { it.id }
            if (removedTabIds.isEmpty()) return@update current
            removedActiveTab = current.activeTabId in removedTabIds
            val remaining = current.openTabs.filterNot { it.id in removedTabIds }.toMutableList()
            var nextActive = current.activeTabId
            if (removedActiveTab) {
                val replacement = AppTab(
                    id = UUID.randomUUID().toString(),
                    backend = "",
                    title = "Yeni Sekme",
                    bridgeProfileId = current.activeBridgeProfileId,
                )
                remaining.add(replacement)
                nextActive = replacement.id
            }
            current.copy(
                openTabs = remaining,
                tabStatuses = current.tabStatuses - removedTabIds,
            ).withActiveTab(nextActive).pruneComposerDrafts()
        }
        persistTabs()
        if (removedActiveTab) goToLanding()
    }

    fun restoreTabs() {
        val rawJson = prefs.getString("app_tabs")
        if (rawJson.isNullOrBlank()) return
        try {
            val obj = JSONObject(rawJson)
            val active = obj.optString("active", "")
            val tabsArray = obj.optJSONArray("tabs")
            val tabsList = mutableListOf<AppTab>()
            val activeBridgeProfileId = state().activeBridgeProfileId
            if (tabsArray != null) {
                for (i in 0 until tabsArray.length()) {
                    val tObj = tabsArray.getJSONObject(i)
                    tabsList.add(
                        AppTab(
                            id = tObj.getString("id"),
                            backend = tObj.getString("backend"),
                            provider = tObj.optString("provider", ""),
                            sessionId = tObj.optString("sessionId", ""),
                            title = tObj.optString("title", ""),
                            // Eski kayıtlarda köprü alanı yoktu. Bu sekmeleri silmek
                            // yerine ilk yüklemedeki etkin köprüye sahipleniyoruz.
                            bridgeProfileId = tObj.optString("bridgeProfileId", "")
                                .ifBlank { activeBridgeProfileId },
                        )
                    )
                }
            }
            update { state ->
                tabsList.removeAll { it.backend.isNotBlank() && !state.supportsEditionBackend(it.backend) }
                val restoredTabs = if (singleSession) {
                    tabsList.groupBy { it.bridgeProfileId }.values.mapNotNull { group ->
                        group.firstOrNull { it.id == active } ?: group.firstOrNull()
                    }
                } else tabsList
                val visibleTabs = restoredTabs.filter { it.bridgeProfileId == state.activeBridgeProfileId }
                val nextActive = active.takeIf { id -> visibleTabs.any { it.id == id } }
                    ?: visibleTabs.firstOrNull()?.id.orEmpty()
                state.copy(openTabs = restoredTabs)
                    .withActiveTab(nextActive)
                    .pruneComposerDrafts()
            }
            // Göç yalnız bellekte kalmasın; bir sonraki açılışta da sekmelerin
            // hangi köprüye ait olduğu kesin olarak bilinsin.
            persistTabs()
        } catch (e: Exception) {
            // Bozuk JSON'da sessizce boş listeyle başla.
        }
    }

    fun persistTabs() {
        val st = state()
        try {
            val obj = JSONObject()
            obj.put("active", st.activeTabId)
            val tabsArray = JSONArray()
            for (tab in st.openTabs) {
                val tObj = JSONObject()
                tObj.put("id", tab.id)
                tObj.put("backend", tab.backend)
                tObj.put("provider", tab.provider)
                tObj.put("sessionId", tab.sessionId)
                tObj.put("title", tab.title)
                tObj.put("bridgeProfileId", tab.bridgeProfileId)
                tabsArray.put(tObj)
            }
            obj.put("tabs", tabsArray)
            prefs.putString("app_tabs", obj.toString())
            onTabsChanged()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun newTab() {
        val newTabId = UUID.randomUUID().toString()
        val newTab = AppTab(
            id = newTabId,
            backend = "",
            provider = "",
            sessionId = "",
            title = "Yeni Sekme",
            bridgeProfileId = state().activeBridgeProfileId,
        )
        update { state ->
            val nextTabs = if (singleSession) {
                state.openTabs.filterNot { it.bridgeProfileId == state.activeBridgeProfileId } + newTab
            } else state.openTabs + newTab
            state.copy(openTabs = nextTabs).withActiveTab(newTabId).pruneComposerDrafts()
        }
        persistTabs()
        goToLanding()
    }

    fun closeTab(tabId: String) {
        var tabToActivate: String? = null
        update { state ->
            val currentTabs = state.openTabs
            val index = currentTabs.indexOfFirst { it.id == tabId }
            val tab = currentTabs.getOrNull(index)
            if (tab == null || tab.bridgeProfileId != state.activeBridgeProfileId) return@update state

            val nextTabs = currentTabs.toMutableList()
            nextTabs.removeAt(index)

            var nextActiveId = state.activeTabId
            if (state.activeTabId == tabId) {
                val visibleAfterClose = nextTabs.filter { it.bridgeProfileId == state.activeBridgeProfileId }
                if (visibleAfterClose.isNotEmpty()) {
                    val oldVisibleIndex = state.visibleTabs.indexOfFirst { it.id == tabId }
                    val neighborIndex = oldVisibleIndex.coerceAtMost(visibleAfterClose.lastIndex)
                    nextActiveId = visibleAfterClose[neighborIndex].id
                    tabToActivate = nextActiveId
                } else {
                    val newTabId = UUID.randomUUID().toString()
                    val newTab = AppTab(
                        id = newTabId,
                        backend = "",
                        provider = "",
                        sessionId = "",
                        title = "Yeni Sekme",
                        bridgeProfileId = state.activeBridgeProfileId,
                    )
                    nextTabs.add(newTab)
                    nextActiveId = newTabId
                    tabToActivate = newTabId
                }
            }

            state.copy(openTabs = nextTabs)
                .withActiveTab(nextActiveId)
                .pruneComposerDrafts()
        }
        persistTabs()

        tabToActivate?.let {
            activateTab(it)
        }
    }

    // Chrome benzeri davranış: geri tuşuyla ana menüye çıkınca aktif sekme
    // "Yeni Sekme" durumuna döner — ayrılınan oturum sekmede açık görünmez.
    // Oturumun kendisi köprüde canlı kalır; sadece sekme bağlantısı kopar.
    fun resetActiveTab() {
        update { state ->
            val index = state.openTabs.indexOfFirst { it.id == state.activeTabId }
            if (index == -1 || state.openTabs[index].bridgeProfileId != state.activeBridgeProfileId) return@update state
            val nextTabs = state.openTabs.toMutableList()
            nextTabs[index] = nextTabs[index].copy(
                backend = "",
                provider = "",
                sessionId = "",
                title = "Yeni Sekme"
            )
            state.copy(openTabs = nextTabs)
        }
        persistTabs()
    }

    // notifyIfDead=false: soğuk açılışta oturumu yeniden bağlarken kullanılır —
    // köprü henüz oturum listesini yüklememişken "canlı değil" uyarısı yanıltıcı
    // olur; sessizce bağlanılır, gerçek durum ilk status tazelemesinde belli olur.
    fun activateTab(tabId: String, notifyIfDead: Boolean = true) {
        val current = state()
        val tab = current.openTabs.find {
            it.id == tabId && it.bridgeProfileId == current.activeBridgeProfileId
        } ?: return
        if (tab.backend.isNotBlank() && !current.supportsEditionBackend(tab.backend)) return

        // "Daha eskiyi göster" ile büyütülen pencere oturumlar arasında global
        // taşınmamalı. Aksi halde uzun bir konuşmadan sonra her sekme değişimi
        // yüzlerce/binlerce mesajı yeniden indirip parse ediyordu.
        update { it.withActiveTab(tabId).copy(messagePageSize = 100) }
        persistTabs()
        scope.launch { refreshTabStatuses() }

        if (tab.sessionId.isEmpty()) {
            goToLanding()
            return
        }

        // Check if session is live in bridge (non-blocking, async check)
        if (notifyIfDead) scope.launch {
            val requestState = state()
            if (requestState.activeBridgeProfileId != tab.bridgeProfileId) return@launch
            val liveResult = runCatching {
                client.listBackendSessions(
                    requestState.settings,
                    if (tab.backend == "cowork") tab.provider else tab.backend,
                )
            }
            // Ağ/bridge timeout'u "başarılı boş liste" değildir. Eski kod hatayı
            // emptyList'e çevirip yaşayan oturuma "artık canlı değil" diyordu.
            val live = liveResult.getOrElse { return@launch }
            val isLive = live.any { it.id == tab.sessionId }
            if (!isLive && state().activeBridgeProfileId == tab.bridgeProfileId) {
                emit("Oturum artık canlı değil")
            }
        }

        // ÖNCE mevcut moddan çık: exit* fonksiyonları last* alanını AYRILINAN
        // oturumla yazar. Hedef oturum kimliği bu yüzden çıkıştan SONRA yazılmalı;
        // aksi halde aynı-backend geçişinde exit bizim yazdığımızı ezer ve enter*
        // sekmenin oturumuna değil eski oturuma geri bağlanır.
        goToLanding()

        update { state ->
            when (tab.backend) {
                // claude/codex/opencode: enter* yalnız tek atımlık pendingBind hedefine
                // bağlanır (lastSessionId artık otomatik bağlanma kaynağı değil).
                "claude-app" -> state.copy(
                    claude = state.claude.copy(lastSessionId = tab.sessionId),
                    pendingBindBackend = "claude-app", pendingBindSessionId = tab.sessionId,
                )
                "codex-app" -> state.copy(
                    codex = state.codex.copy(lastSessionId = tab.sessionId),
                    pendingBindBackend = "codex-app", pendingBindSessionId = tab.sessionId,
                )
                // cowork da artık aynı kuralda: `enterCoworkMode` yalnız bu tek
                // atımlık hedefe bağlanır. `lastCowork*` duruyor — sağlayıcıyı ve
                // "en son neredeydik"i başka yerler okuyor — ama otomatik
                // bağlanma kaynağı değil (gerekçe CoworkDelegate.enterCoworkMode).
                "cowork" -> state.copy(
                    lastCoworkSessionId = tab.sessionId,
                    lastCoworkProvider = tab.provider,
                    pendingBindBackend = "cowork", pendingBindSessionId = tab.sessionId,
                )
                // v1 ve v2 AYNI dal: durum ailesini `withOpencodeFamily` seciyor.
                // Ayri yazilsaydi biri unutulurdu — nitekim v2 dali hic yoktu ve
                // sekmeden cikip geri gelince oturum yuklenmiyordu.
                "opencode2-app" -> state
                    .withOpencodeFamily(tab.backend) { f -> f.copy(lastSessionId = tab.sessionId) }
                    .copy(pendingBindBackend = tab.backend, pendingBindSessionId = tab.sessionId)
                "omp" -> state.copy(
                    omp = state.omp.copy(lastSessionId = tab.sessionId),
                    pendingBindBackend = "omp", pendingBindSessionId = tab.sessionId,
                )
                // 25.08.2026: agy de aynı kurala geçti — `enterAgyMode` artık yalnız
                // pendingBind hedefine bağlanıyor. Bu dal pendingBind yazmasaydı
                // sekmeye dokunmak oturumu geri getirmez, kurulum ekranına düşerdi.
                // `lastAgySessionId` duruyor (çıkışta "en son neredeydik" hafızası)
                // ama otomatik bağlanma kaynağı değil.
                "agy" -> state.copy(
                    lastAgySessionId = tab.sessionId,
                    pendingBindBackend = "agy", pendingBindSessionId = tab.sessionId,
                )
                else -> state
            }
        }

        // goToLanding sonrası backend=null; hedefe landing'den girilir. Aynı ve
        // farklı backend geçişleri böylece tek kod yolunda birleşir.
        when (tab.backend) {
            "agy" -> enterAgyMode()
            "claude-app" -> enterClaudeAppMode()
            "codex-app" -> enterCodexAppMode()
            "opencode2-app" -> enterOpencode2AppMode()
            "omp" -> enterOmpMode()
            "cowork" -> enterCoworkMode()
        }
    }

    fun syncActiveTab(backend: String, provider: String, sessionId: String, title: String) {
        // Lite'ta görünmeyen sekmeler yok: yalnız o anda seçili backend tek sohbet
        // yuvasını güncelleyebilir. Önceki backend'den gecikerek dönen ağ cevabı
        // aksi halde yeni sohbeti ezip bir sonraki açılışta eski sağlayıcıyı getirir.
        if (singleSession && state().backend != backend) return
        update { state ->
            if (singleSession && state.backend != backend) return@update state
            val tabs = state.openTabs.toMutableList()
            val profileId = state.activeBridgeProfileId
            if (singleSession) {
                val existing = tabs.firstOrNull { it.bridgeProfileId == profileId }
                val replacement = AppTab(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    backend = backend,
                    provider = provider,
                    sessionId = sessionId,
                    title = title,
                    bridgeProfileId = profileId,
                )
                return@update state.copy(
                    openTabs = tabs.filterNot { it.bridgeProfileId == profileId } + replacement,
                ).withActiveTab(replacement.id).pruneComposerDrafts()
            }
            val activeIndex = tabs.indexOfFirst {
                it.id == state.activeTabId && it.bridgeProfileId == profileId
            }
            val active = tabs.getOrNull(activeIndex)

            // SEKME KORUMASI: dolu bir sekme başka bir oturumla asla ezilmez.
            // - Aktif sekme boşsa ya da zaten bu oturumu taşıyorsa: yerinde güncelle
            //   (yeni-oturum akışı, activateTab yeniden bağlanışı, başlık tazeleme).
            // - Aktif sekmede BAŞKA oturum yaşıyorsa: hedef oturum hangi sekmedeyse
            //   o aktifleşir; hiçbirinde yoksa sona YENİ sekme açılır. Merkez'den
            //   (proje/operasyon/arama) oturum açmak mevcut sekmeyi kaybettirmez.
            val targetIndex = when {
                active != null && (active.sessionId.isEmpty() || active.sessionId == sessionId) -> activeIndex
                else -> {
                    val existing = tabs.indexOfFirst {
                        sessionId.isNotEmpty() &&
                            it.bridgeProfileId == profileId &&
                            it.backend == backend && it.provider == provider && it.sessionId == sessionId
                    }
                    if (existing != -1) existing
                    else {
                        tabs.add(
                            AppTab(
                                id = UUID.randomUUID().toString(),
                                backend = backend,
                                provider = provider,
                                sessionId = sessionId,
                                title = title,
                                bridgeProfileId = profileId,
                            )
                        )
                        tabs.lastIndex
                    }
                }
            }
            tabs[targetIndex] = tabs[targetIndex].copy(
                backend = backend,
                provider = provider,
                sessionId = sessionId,
                title = title,
            )
            val nextActiveId = tabs[targetIndex].id
            val targetTab = tabs[targetIndex]

            // Tekilleştirme: aynı (backend, provider, sessionId) başka bir sekmede de
            // varsa o sekme kaldırılır — bir oturum tek sekmede yaşar (Chrome mantığı).
            // Kural: sessionId boş olanlara dokunma (iki landing sekmesi meşru).
            val finalTabs = if (targetTab.sessionId.isNotEmpty()) {
                tabs.filter {
                    it.id == nextActiveId ||
                    it.bridgeProfileId != profileId ||
                    !(it.backend == targetTab.backend && it.provider == targetTab.provider && it.sessionId == targetTab.sessionId)
                }
            } else {
                tabs
            }

            state.copy(openTabs = finalTabs)
                .withActiveTab(nextActiveId)
                .pruneComposerDrafts()
        }
        persistTabs()
        scope.launch { refreshTabStatuses() }
    }

    /**
     * Disk listesinden yapılan rename'i açık sekmelere anında yansıtır.
     *
     * Codex'te çekmecenin kimliği threadId, açık sekmenin kimliği ise restore
     * edilmiş kabuk id'si olabilir. Bu yüzden yalnız AppTab.sessionId değil,
     * son canlı durumdan öğrendiğimiz TabStatus.threadId de eşleştirilir.
     */
    fun renameSessionTitle(backend: String, provider: String, sessionId: String, title: String) {
        if (sessionId.isBlank()) return
        val cleanTitle = title.replace(Regex("\\s+"), " ").trim().take(80)
        update { state ->
            val profileId = state.activeBridgeProfileId
            val matchingTabIds = state.openTabs.asSequence()
                .filter { tab ->
                    tab.bridgeProfileId == profileId &&
                        tab.backend == backend &&
                        tab.provider == provider &&
                        (tab.sessionId == sessionId || state.tabStatuses[tab.id]?.threadId == sessionId)
                }
                .map { it.id }
                .toSet()
            if (matchingTabIds.isEmpty()) return@update state

            state.copy(
                tabStatuses = state.tabStatuses.toMutableMap().apply {
                    for (tab in state.openTabs.filter { it.id in matchingTabIds }) {
                        val previous = this[tab.id] ?: TabStatus(sessionId = tab.sessionId)
                        this[tab.id] = previous.copy(
                            liveTitle = cleanTitle,
                            sessionId = tab.sessionId,
                            threadId = previous.threadId.ifBlank { sessionId },
                        )
                    }
                },
            )
        }
    }

    /**
     * Rewind gibi aynı mantıksal sohbeti yeni bir backend sessionId'siyle
     * sürdüren işlemlerde sekmenin kimliğini yerinde yeniler. syncActiveTab bu
     * amaçla kullanılamaz: dolu sekmeyi koruma kuralı yeni kimliği başka bir
     * oturum sayıp yeni sekme açar veya o kimliği taşıyan sekmeye geçer.
     */
    fun replaceSessionId(backend: String, provider: String, oldSessionId: String, newSessionId: String) {
        if (oldSessionId.isBlank() || newSessionId.isBlank() || oldSessionId == newSessionId) return
        update { state ->
            val profileId = state.activeBridgeProfileId
            val target = state.openTabs.firstOrNull {
                it.bridgeProfileId == profileId &&
                    it.backend == backend &&
                    it.provider == provider &&
                    it.sessionId == oldSessionId
            } ?: return@update state
            val replaced = state.openTabs.map {
                if (it.id == target.id) it.copy(sessionId = newSessionId) else it
            }
            // Yeni kimlik başka bir sekmede zaten varsa rewind edilen sekmeyi
            // koru; aynı mantıksal sohbet iki sekmede görünmesin.
            val duplicateIds = replaced.asSequence()
                .filter {
                    it.id != target.id &&
                        it.bridgeProfileId == profileId &&
                        it.backend == backend &&
                        it.provider == provider &&
                        it.sessionId == newSessionId
                }
                .map { it.id }
                .toSet()
            state.copy(
                openTabs = replaced.filterNot { it.id in duplicateIds },
                tabStatuses = state.tabStatuses - duplicateIds,
            )
                .withActiveTab(if (state.activeTabId in duplicateIds) target.id else state.activeTabId)
                .pruneComposerDrafts()
        }
        persistTabs()
        scope.launch { refreshTabStatuses() }
    }

    suspend fun refreshTabStatuses() {
        val st = state()
        val visibleTabs = st.visibleTabs
        val tabsWithSession = visibleTabs.filter { it.sessionId.isNotEmpty() }
        if (tabsWithSession.isEmpty()) {
            if (st.tabStatuses.isNotEmpty()) update { it.copy(tabStatuses = emptyMap()) }
            return
        }
        val backends = tabsWithSession.map { if (it.backend == "cowork") it.provider else it.backend }
            .filter { it.isNotEmpty() }.distinct()
        val liveByBackend = mutableMapOf<String, List<LiveSession>?>()
        for (backend in backends) {
            // Profil çağrı sürerken değiştiyse eski dilimin sonucunu yeni profile
            // uygulama ve kalan eski sekmeler için yeni köprüye istek gönderme.
            if (state().activeBridgeProfileId != st.activeBridgeProfileId) return
            liveByBackend[backend] =
                runCatching { client.listBackendSessions(st.settings, backend) }.getOrNull()
        }
        update { s ->
            if (s.activeBridgeProfileId != st.activeBridgeProfileId) return@update s
            val currentVisibleTabs = s.visibleTabs
            s.copy(
                tabStatuses = computeTabStatuses(
                    currentVisibleTabs,
                    s.activeTabId,
                    liveByBackend,
                    s.tabStatuses,
                ),
            )
        }
    }

    fun closeOtherTabs(tabId: String) {
        val current = state()
        val targetTab = current.visibleTabs.find { it.id == tabId } ?: return
        update { state ->
            // "Diğerleri" yalnız etkin köprünün görünen sekmeleridir. Gizli
            // köprülerin sekmeleri tam listede aynen korunur.
            state.copy(
                openTabs = state.openTabs.filter {
                    it.bridgeProfileId != state.activeBridgeProfileId || it.id == targetTab.id
                },
            ).pruneComposerDrafts()
        }
        if (state().activeTabId != tabId) {
            activateTab(tabId)
        } else {
            persistTabs()
            scope.launch { refreshTabStatuses() }
        }
    }

    fun moveTab(fromIndex: Int, toIndex: Int) {
        update { state ->
            val visibleTabs = state.visibleTabs
            if (fromIndex !in visibleTabs.indices || toIndex !in visibleTabs.indices) return@update state
            val reorderedVisible = visibleTabs.toMutableList()
            reorderedVisible.add(toIndex, reorderedVisible.removeAt(fromIndex))
            var visibleIndex = 0
            // UI indeksleri süzülmüş listeye aittir. Tam listedeki gizli sekme
            // yuvalarını bozmadan yalnız etkin köprünün sırasını değiştiriyoruz.
            val reorderedTabs = state.openTabs.map { tab ->
                if (tab.bridgeProfileId == state.activeBridgeProfileId) {
                    reorderedVisible[visibleIndex++]
                } else {
                    tab
                }
            }
            state.copy(openTabs = reorderedTabs)
        }
        persistTabs()
    }

    /**
     * Köprü değişince tam sekme listesini korur, fakat aktifliği yalnız yeni
     * köprünün görünür sekmelerinden birine taşır. Görünür sekme yoksa boş id
     * landing durumunu temsil eder; gizli sekme hiçbir kod yolunda aktif kalmaz.
     */
    fun reconcileActiveTabForBridge(): String? {
        var nextActiveId: String? = null
        update { state ->
            val visibleTabs = state.visibleTabs
            nextActiveId = state.activeTabId
                .takeIf { id -> visibleTabs.any { it.id == id } }
                ?: visibleTabs.firstOrNull()?.id
            val visibleIds = visibleTabs.mapTo(mutableSetOf()) { it.id }
            state.copy(tabStatuses = state.tabStatuses.filterKeys { it in visibleIds })
                .withActiveTab(nextActiveId.orEmpty())
        }
        persistTabs()
        return nextActiveId
    }
}

// generateTabTitle, computeTabStatuses, chatBackAction, tabDotState,
// ChatBackAction, TabDotState shared modülünde (TabLogic.kt).
