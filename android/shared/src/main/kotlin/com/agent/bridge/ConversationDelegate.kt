package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tam geçmiş yükleme sonucu. Boolean yerine kullanılır ki "gerçekten tamamlandı",
 * "kapasite sınırına takıldı" ve "hata" durumları birbirine karışmasın.
 */
sealed interface FullHistoryLoadResult {
    /** Geçmiş tamamen yüklendi (exhaust). */
    data object Complete : FullHistoryLoadResult
    /** cap sınırına takıldı; yüklü mesajlar var ama tamamı değil. */
    data class Truncated(val loadedCount: Int) : FullHistoryLoadResult
    /** Ağ/oturum hatası; eldeki mesajlarda arama yapılabilir ama eksik. */
    data class Failed(val loadedCount: Int, val cause: Throwable?) : FullHistoryLoadResult
}

internal data class PendingComposerDraft(
    val input: String,
    val attachments: List<ChatAttachment>,
)

// bu backendler iyimser temizler: prompt ucu turn'u baslatip HEMEN doner
// (codex/opencode ile ayni desen). Listede olmadigi icin yazi kutuda kaliyordu —
// ilk prompt'ta ACP session/new acildigi icin bu bekleme 10+ saniye surebiliyor
// ve kullanici mesaji gitmedi sanip tekrar gonderiyordu (canli goruldu).
internal fun shouldClearComposerOptimistically(state: RemoteUiState): Boolean = when (state.backend) {
    Backend.CODEX_APP.id, Backend.OPENCODE2_APP.id, Backend.OMP.id -> true
    Backend.COWORK.id -> normalizeCoworkProvider(state.cowork.provider) in
        setOf(Backend.CODEX_APP.id, Backend.OPENCODE2_APP.id, Backend.OMP.id)
    else -> false
}

internal fun clearComposerIfUnchanged(
    state: RemoteUiState,
    draft: PendingComposerDraft,
): RemoteUiState = if (state.input == draft.input && state.attachments == draft.attachments) {
    state.copy(input = "", attachments = emptyList())
} else {
    state
}

internal fun restoreComposerIfUntouched(
    state: RemoteUiState,
    draft: PendingComposerDraft,
): RemoteUiState = if (state.input.isBlank() && state.attachments.isEmpty()) {
    state.copy(input = draft.input, attachments = draft.attachments)
} else {
    state
}

/** Konuşma yükleme, gönderme, sayfalama ve geri sarma akışları. */
class ConversationDelegate(
    private val client: BridgeClient,
    private val scope: CoroutineScope,
    private val state: () -> RemoteUiState,
    private val update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    private val emit: suspend (String) -> Unit,
    private val reportError: suspend (String, Throwable) -> Unit,
    private val cache: ConversationCache,
    private val startCowork: (String) -> Unit,
    private val handleClaudeCleared: (org.json.JSONObject) -> Unit,
    private val refreshCoworkOutputs: () -> Unit,
    private val releaseCoworkLease: (RemoteUiState) -> Unit,
    private val openClaudeSocket: (String) -> Unit,
    private val replaceTabSessionId: (backend: String, provider: String, oldSessionId: String, newSessionId: String) -> Unit,
    private val onConversationApplied: () -> Unit = {},
) {
    fun refresh(showErrors: Boolean = true): Job = scope.launch {
        val current = state()
        suspend fun load(block: suspend () -> ConversationResult) {
            runCatching { block() }.onSuccess(::apply).onFailure { handleRefreshFailure(showErrors, it) }
        }
        when (current.backend) {
            Backend.AGY.id -> if (current.agy.sessionId.isNotBlank()) load { client.agyConversation(current.settings, current.agy.sessionId) }
            Backend.COWORK.id -> when (normalizeCoworkProvider(current.cowork.provider)) {
                Backend.CODEX_APP.id -> if (current.codex.sessionId.isNotBlank()) load { client.codexAppConversation(current.settings, current.codex.sessionId, limit = current.messagePageSize.coerceAtLeast(100)) }
                Backend.OPENCODE2_APP.id -> if (current.opencode.sessionId.isNotBlank()) load { client.opencodeAppConversation(current.settings, current.opencode.sessionId, Backend.OPENCODE2_APP.id) }
                Backend.OMP.id -> if (current.omp.sessionId.isNotBlank()) load { client.ompConversation(current.settings, current.omp.sessionId) }
                else -> if (current.claude.sessionId.isNotBlank()) load { client.claudeAppConversation(current.settings, current.claude.sessionId, limit = current.messagePageSize.coerceAtLeast(100)) }
            }
            Backend.CODEX_APP.id -> if (current.codex.sessionId.isNotBlank()) load { client.codexAppConversation(current.settings, current.codex.sessionId, limit = current.messagePageSize.coerceAtLeast(100)) }
            Backend.OPENCODE2_APP.id -> if (current.opencode.sessionId.isNotBlank()) load { client.opencodeAppConversation(current.settings, current.opencode.sessionId, Backend.OPENCODE2_APP.id) }
            Backend.OMP.id -> if (current.omp.sessionId.isNotBlank()) load { client.ompConversation(current.settings, current.omp.sessionId) }
            Backend.CLAUDE_APP.id -> if (current.claude.sessionId.isNotBlank()) load { client.claudeAppConversation(current.settings, current.claude.sessionId, limit = current.messagePageSize.coerceAtLeast(100)) }
        }
    }

    fun send(): Job = scope.launch {
        val initial = state()
        val composerDraft = PendingComposerDraft(initial.input, initial.attachments)
        val baseText = initial.input.trim()
        val text = if (initial.attachments.isEmpty()) baseText else baseText + "\n\nEk dosyalar:\n" + initial.attachments.joinToString("\n") { "- ${it.name}: ${it.path}" }
        if (text.isEmpty()) return@launch
        // /compact (ve /summarize) — bağlamı AI ile özetler. Slash komutlarını her
        // iki sunucu da düz metin olarak ANLAMAZ: opencode'da /compact istemci
        // özelliği, codex'te ise TUI özelliğidir (app-server yorumlamaz), yani metin
        // olarak gönderilince model onu sıradan bir mesaj sanıp cevap yazar — canlı
        // görüldü. Burada yakalayıp ilgili özetleme ucuna yönlendiriyoruz; prompt
        // olarak GÖNDERİLMEZ.
        run {
            val isCompactCmd = baseText.equals("/compact", ignoreCase = true) ||
                baseText.equals("/summarize", ignoreCase = true)
            if (!isCompactCmd || initial.attachments.isNotEmpty()) return@run
            val provider = if (initial.backend == Backend.COWORK.id) {
                normalizeCoworkProvider(initial.cowork.provider)
            } else {
                initial.backend
            }
            val sid = when (provider) {
                Backend.OPENCODE2_APP.id -> initial.opencode.sessionId
                Backend.CODEX_APP.id -> initial.codex.sessionId
                Backend.OMP.id -> initial.omp.sessionId
                else -> ""
            }
            if (sid.isBlank()) return@run
            if (initial.running) { emit("Tur sürerken özetlenemez — önce bitmesini bekle."); return@launch }
            update { clearComposerIfUnchanged(it, composerDraft) }
            // codexAppCompact ok=false'ta fırlatır ve sunucunun mesajını taşır
            // ("thread not started" gibi); opencodeAppCompact fırlatmaz, false döner
            // — o yüzden ikisi de aynı biçimde "başarılı mı" diye sınanıyor.
            val r = runCatching {
                if (provider == Backend.CODEX_APP.id) {
                    client.codexAppCompact(initial.settings, sid)
                    true
                } else if (provider == Backend.OMP.id) {
                    client.ompCompact(initial.settings, sid)
                } else {
                    client.opencodeAppCompact(initial.settings, sid, if (provider == Backend.OPENCODE2_APP.id) provider else Backend.OPENCODE2_APP.id)
                }
            }
            val started = r.getOrNull() == true
            if (started) {
                refresh(false)
            } else {
                val detay = r.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                emit(if (detay != null) "Özetleme başlatılamadı: $detay"
                     else "Özetleme başlatılamadı — tur sürüyor olabilir.")
                update { restoreComposerIfUntouched(it, composerDraft) }
            }
            return@launch
        }
        if (initial.backend == Backend.COWORK.id) {
            val provider = normalizeCoworkProvider(initial.cowork.provider)
            val sid = when (provider) {
                Backend.CODEX_APP.id -> initial.codex.sessionId
                Backend.OPENCODE2_APP.id -> initial.opencode.sessionId
                Backend.OMP.id -> initial.omp.sessionId
                else -> initial.claude.sessionId
            }
            if (sid.isBlank()) { startCowork(text); return@launch }
            if (initial.cowork.activeProjectPath.isBlank()) { emit("Aktif Cowork çalışma alanı bulunamadı"); return@launch }
            val acquired = runCatching { client.coworkAcquireLease(initial.settings, initial.cowork.activeProjectPath, provider, sid) }
            // Mesaj client'ta zaten okunur halde ("Codex bu çalışma alanını
            // kullanıyor…"); reportError'ın öneki onu "Çalışma alanı
            // kullanılamıyor: …" diye ikiye katlıyordu.
            if (acquired.isFailure) {
                emit(acquired.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: "Çalışma alanı kullanılamıyor")
                return@launch
            }
        }
        val optimistic = initial.backend in setOf(Backend.AGY.id, Backend.CLAUDE_APP.id, Backend.COWORK.id, Backend.CODEX_APP.id, Backend.OPENCODE2_APP.id, Backend.OMP.id)
        val clearComposerNow = shouldClearComposerOptimistically(initial)
        update {
            val next = it.copy(sending = true, running = it.running || optimistic, truncateAfterIndex = null)
            if (clearComposerNow) clearComposerIfUnchanged(next, composerDraft) else next
        }
        val current = state()
        suspend fun missing(message: String): Result<Any?> {
            emit(message)
            update {
                val next = it.copy(sending = false, running = false)
                if (clearComposerNow) restoreComposerIfUntouched(next, composerDraft) else next
            }
            return Result.failure(MissingSessionException)
        }
        val result: Result<*> = when (current.backend) {
            Backend.AGY.id -> if (current.agy.sessionId.isBlank()) missing("Antigravity CLI session yok") else runCatching { client.agyPrompt(current.settings, current.agy.sessionId, text, current.agy.model) }
            Backend.CLAUDE_APP.id -> if (current.claude.sessionId.isBlank()) missing("Claude App session yok") else runCatching { client.claudeAppPrompt(current.settings, current.claude.sessionId, text, current.claude.model, current.claude.permissionMode) }.onSuccess { if (it.optBoolean("cleared", false)) handleClaudeCleared(it) }
            Backend.COWORK.id -> when (normalizeCoworkProvider(current.cowork.provider)) {
                Backend.CODEX_APP.id -> if (current.codex.sessionId.isBlank()) missing("Codex App session yok") else runCatching { client.codexAppPrompt(current.settings, current.codex.sessionId, text, current.codex.model) }
                Backend.OPENCODE2_APP.id -> if (current.opencode.sessionId.isBlank()) missing("OpenCode 2 session yok") else runCatching { client.opencodeAppPrompt(current.settings, current.opencode.sessionId, text, current.opencode.model, current.opencode.permissionMode, current.opencode.variant, current.opencode.agent, Backend.OPENCODE2_APP.id) }
                Backend.OMP.id -> if (current.omp.sessionId.isBlank()) missing("OMP session yok") else runCatching { client.ompPrompt(current.settings, current.omp.sessionId, text, current.omp.model, current.omp.permissionMode, current.omp.variant) }
                else -> if (current.claude.sessionId.isBlank()) missing("Claude App session yok") else runCatching { client.claudeAppPrompt(current.settings, current.claude.sessionId, text, current.claude.model, current.claude.permissionMode) }.onSuccess { if (it.optBoolean("cleared", false)) handleClaudeCleared(it) }
            }
            Backend.CODEX_APP.id -> if (current.codex.sessionId.isBlank()) missing("Codex App session yok") else runCatching { client.codexAppPrompt(current.settings, current.codex.sessionId, text, current.codex.model) }
            Backend.OPENCODE2_APP.id -> if (current.opencode.sessionId.isBlank()) missing("OpenCode 2 session yok") else runCatching { client.opencodeAppPrompt(current.settings, current.opencode.sessionId, text, current.opencode.model, current.opencode.permissionMode, current.opencode.variant, current.opencode.agent, Backend.OPENCODE2_APP.id) }
            Backend.OMP.id -> if (current.omp.sessionId.isBlank()) missing("OMP session yok") else runCatching { client.ompPrompt(current.settings, current.omp.sessionId, text, current.omp.model, current.omp.permissionMode, current.omp.variant) }
            else -> Result.success(Unit)
        }
        if (result.exceptionOrNull() === MissingSessionException) return@launch
        result.onSuccess {
            if (!clearComposerNow) update { clearComposerIfUnchanged(it, composerDraft) }
            refresh(false)
        }
            .onFailure {
                update { value ->
                    val next = if (optimistic) value.copy(running = false) else value
                    if (clearComposerNow) restoreComposerIfUntouched(next, composerDraft) else next
                }
                if (current.backend == Backend.COWORK.id) releaseCoworkLease(current)
                reportError("Send failed", it)
            }
        update { it.copy(sending = false) }
    }

    fun apply(conversation: ConversationResult) {
        val cacheKey = offlineConversationKey(state())
        val coworkFinished = state().backend == Backend.COWORK.id && state().running && !conversation.running
        val refreshOutputs = coworkFinished && normalizeCoworkProvider(state().cowork.provider) != Backend.CLAUDE_APP.id
        update {
            val reduced = it.copy(
                transcript = conversation.text.ifBlank { it.transcript },
                // Kuyruk penceresi yüklü geçmişi kırpmaz (bkz. mergeTailWindow).
                messagesList = ConversationPaging.mergeTailWindow(it.messagesList, conversation.messages),
                offlineConversation = false, staleConversation = false, running = conversation.running, awaitingApproval = conversation.awaitingApproval,
                approval = if (conversation.awaitingApproval) conversation.approval else null,
                awaitingFirstOutput = conversation.awaitingFirstOutput, choices = conversation.choices, contextTokens = conversation.contextTokens,
                contextWindow = conversation.contextWindow, cost = conversation.cost,
                claude = it.claude.copy(
                    permissionMode = conversation.permissionMode.ifBlank { it.claude.permissionMode },
                    effort = if (it.backend == Backend.CLAUDE_APP.id || (it.backend == Backend.COWORK.id && normalizeCoworkProvider(it.cowork.provider) == Backend.CLAUDE_APP.id)) conversation.effort else it.claude.effort,
                ),
                codex = it.codex.copy(
                    effort = if (it.backend == Backend.CODEX_APP.id || (it.backend == Backend.COWORK.id && normalizeCoworkProvider(it.cowork.provider) == Backend.CODEX_APP.id)) conversation.effort else it.codex.effort,
                    // Hedef poll yolunda uc-durumlu: goalPresent=false (eski kopru) -> dokunma;
                    // codex aktifse ve alan geldiyse yaz (null gelirse temizle). Ayni backend kapisi.
                    goal = if (conversation.goalPresent && (it.backend == Backend.CODEX_APP.id || (it.backend == Backend.COWORK.id && normalizeCoworkProvider(it.cowork.provider) == Backend.CODEX_APP.id))) conversation.goal else it.codex.goal,
                ),
                // GÖREV PANOSU — poll yolu. Aynı alanların soket yolu
                // SessionStateReducer'da; ikisi de yazılmazsa pano yalnız bir
                // taşımada canlanır (bu depoda tekrarlayan hata: delege yazıldı,
                // okuma dalı unutuldu).
                // AKTIF OpenCode AILESINE yazilir (v1 ya da v2). Eski kapi
                // yalniz v1'i taniyordu: v2 sekmesinde pano/geri-sarma/baglam
                // poll yolundan hic guncellenmiyordu.
                opencode = if (it.aktifOpencodeBackendId() == Backend.OPENCODE2_APP.id) {
                    it.opencode.pollIleBirlestir(conversation)
                } else it.opencode,
                omp = run {
                    // Cowork bir sunum katmanı: backend "cowork" iken de asıl oturum
                    // OMP'nin, dolayısıyla efor/izin kipi yine omp state'ine yazılmalı
                    // (claude/codex dallarındaki aynı kapı).
                    val ompActive = it.backend == Backend.OMP.id ||
                        (it.backend == Backend.COWORK.id && normalizeCoworkProvider(it.cowork.provider) == Backend.OMP.id)
                    it.omp.copy(
                        variant = if (ompActive && conversation.effort.isNotBlank()) conversation.effort else it.omp.variant,
                        permissionMode = if (ompActive && conversation.permissionMode.isNotBlank()) conversation.permissionMode else it.omp.permissionMode,
                    )
                },
            )
            SessionStateReducer.reduceBackendOptions(
                state = reduced,
                availableModels = conversation.availableModels.takeIf { models -> models.isNotEmpty() },
                permissionMode = conversation.permissionMode.takeIf { mode -> mode.isNotBlank() },
                permissionModes = conversation.permissionModes.takeIf { modes -> modes.isNotEmpty() },
                commands = conversation.commands.takeIf { commands -> commands.isNotEmpty() },
            )
        }
        if (cacheKey != null && conversation.messages.isNotEmpty()) scope.launch(Dispatchers.IO) { cache.save(cacheKey.backend, cacheKey.sessionId, conversation) }
        if (refreshOutputs) refreshCoworkOutputs()
        if (coworkFinished) releaseCoworkLease(state())
        onConversationApplied()
    }

    // Sondan sayımlı geri-sarma hedefi: (backend, sessionId). returnToMessage ve
    // forkFromMessage aynı eşlemeyi kullanır.
    private fun rewindTarget(current: RemoteUiState): Pair<String, String>? = when (current.backend) {
        Backend.CLAUDE_APP.id -> Backend.CLAUDE_APP.id to current.claude.sessionId
        Backend.COWORK.id -> when (normalizeCoworkProvider(current.cowork.provider)) {
            Backend.CODEX_APP.id -> Backend.CODEX_APP.id to current.codex.sessionId
            Backend.OPENCODE2_APP.id -> Backend.OPENCODE2_APP.id to current.opencode.sessionId
            // OMP'yi claude'a DÜŞÜRME. Eski `else ->` dalı cowork-OMP sohbetinde
            // geri dön/çatalla hedefi olarak claude'un AKTİF oturumunu veriyordu:
            // kullanıcının açık bir claude oturumu varsa "mesaja geri dön" ALAKASIZ
            // o oturumu geri sarardı (drop sayısı da OMP listesinden). null →
            // localRewind'e düşer: yalnız görünüm sarılır, hiçbir oturum bozulmaz.
            Backend.CLAUDE_APP.id -> Backend.CLAUDE_APP.id to current.claude.sessionId
            else -> null
        }
        Backend.CODEX_APP.id -> Backend.CODEX_APP.id to current.codex.sessionId
        // v2: /opencode2-app/rewind ve /fork-from köprüde var (26.09.2026) —
        // aile opencode2'nin kendi durum ailesi (Opencode2Support.kt).
        Backend.OPENCODE2_APP.id -> Backend.OPENCODE2_APP.id to current.opencode.sessionId
        else -> null
    }

    // Çatallama hedefi geri sarmadan AYRI: OMP'de köprüde /omp/fork-from var
    // (native `branch` RPC'si) ama /omp/rewind YOK — rewindTarget'a omp eklemek
    // returnToMessage'ı 404'e gönderirdi. OMP yalnız burada hedeflenir.
    private fun forkTarget(current: RemoteUiState): Pair<String, String>? = when {
        current.backend == Backend.OMP.id -> Backend.OMP.id to current.omp.sessionId
        current.backend == Backend.COWORK.id &&
            normalizeCoworkProvider(current.cowork.provider) == Backend.OMP.id ->
            Backend.OMP.id to current.omp.sessionId
        else -> rewindTarget(current)
    }

    // Buradan çatalla: orijinal oturuma dokunmadan bu mesajın ÖNCESİNE kadar kopya
    // oturum açtırır; başarıda onForked(backend, yeniSessionId, mesajMetni) çağrılır
    // (ViewModel yeni sekmede açar). opencode/agy'de bridge fork ucu yok.
    fun forkFromMessage(index: Int, onForked: (String, String, String) -> Unit) {
        val current = state(); val messages = current.messagesList
        if (index !in messages.indices) return
        val text = messages[index].text
        val drop = messages.drop(index).count { it.role.equals("user", ignoreCase = true) }
        val target = forkTarget(current)
        if (target == null || target.second.isBlank() || drop < 1) { scope.launch { emit("Bu mesajdan çatallanamıyor") }; return }
        if (target.first !in setOf(Backend.CLAUDE_APP.id, Backend.CODEX_APP.id, Backend.OMP.id, Backend.OPENCODE2_APP.id)) { scope.launch { emit("Bu sağlayıcı çatallamayı desteklemiyor") }; return }
        if (current.running) { scope.launch { emit("Tur sürerken çatallanamaz") }; return }
        scope.launch {
            runCatching { client.forkFromMessage(current.settings, target.first, target.second, drop) }
                .onSuccess { result ->
                    if (!result.ok || result.sessionId.isBlank()) { emit("Çatallanamadı: ${result.error.ifBlank { "bilinmeyen hata" }}"); return@onSuccess }
                    onForked(target.first, result.sessionId, text)
                }
                .onFailure { reportError("Çatallanamadı", it) }
        }
    }

    /**
     * Yalnız GÖRÜNÜMÜ bu mesaja geri sarar: sonrası gizlenir, metin yazma
     * kutusuna döner. Ajanın hafızası olduğu gibi kalır.
     *
     * İki yerde kullanılır: köprüde geri sarma hedefi olmayan sağlayıcılarda ve
     * köprünün geri sarmayı reddettiği durumda. İkincisinde [reason] doldurulur
     * ki kullanıcı neden tam geri sarma olmadığını bilsin — sessizce yarım iş
     * yapmak, hiç yapmamak kadar kötü.
     */
    private fun localRewind(index: Int, text: String, reason: String) {
        update { it.copy(truncateAfterIndex = index, input = text) }
        val detail = reason.trim()
        scope.launch {
            emit(
                if (detail.isBlank()) "Bu mesaja dönüldü, sonrası gizlendi."
                else "Yalnız görünüm geri sarıldı (ajan hafızası tam kalır) — $detail"
            )
        }
    }

    fun returnToMessage(index: Int) {
        val current = state(); val messages = current.messagesList
        if (index !in messages.indices) return
        val text = messages[index].text
        val drop = messages.drop(index).count { it.role.equals("user", ignoreCase = true) }
        val target = rewindTarget(current)
        if (target == null || target.second.isBlank() || drop < 1) {
            localRewind(index, text, ""); return
        }
        if (current.running) { scope.launch { emit("Tur sürerken mesaja geri dönülemez") }; return }
        scope.launch {
            runCatching { client.rewindSession(current.settings, target.first, target.second, drop) }
                .onSuccess { result ->
                    // Köprü geri saramıyorsa (ör. transcript boş: "geri dönülecek
                    // mesaj bulunamadı") ESKIDEN hiçbir şey olmuyordu — tuşa
                    // basılıyor, mesaj olduğu yerde kalıyordu. En azından görünümü
                    // geri sar: hedef yokken zaten yapılan şey bu.
                    if (!result.ok) { localRewind(index, text, result.error); return@onSuccess }
                    update { it.copy(truncateAfterIndex = null, input = text) }
                    if (target.first == Backend.CLAUDE_APP.id && result.sessionId.isNotBlank() && result.sessionId != target.second) {
                        update { it.copy(claude = it.claude.copy(sessionId = result.sessionId)) }
                        replaceTabSessionId(
                            current.backend.orEmpty(),
                            if (current.backend == Backend.COWORK.id) normalizeCoworkProvider(current.cowork.provider) else "",
                            target.second,
                            result.sessionId,
                        )
                        openClaudeSocket(result.sessionId)
                    }
                    refresh(false)
                    emit(if (result.partial) "Görünüm geri sarıldı (ajan hafızası tam kalır)" else "Bu mesaja dönüldü; düzenleyip yeniden gönderebilirsin.")
                }
                .onFailure { reportError("Geri dönülemedi", it) }
        }
    }

    /**
     * Sohbet içi arama için TÜM geçmişi belleğe yükler (plan §16: son yüklü sayfayla
     * sınırlama yok). claude/codex sayfalı sağlayıcılarda `before` ile geriye doğru
     * exhaust edilene kadar sayfalar; diğer sağlayıcılar (opencode/agy) transcript'i
     * zaten tek seferde döndürdüğü için ek iş yapılmaz.
     *
     * Çağrandan ÖNCE hedef oturumun açılıp konuşmasının yüklendiği (messagesList dolu)
     * garanti edilmelidir; bu fonksiyon aktif oturum üzerinde çalışır. Sonuç Complete /
     * Truncated / Failed olarak ayrışır ki hata "tamamlandı" gibi yorumlanmasın: ağ
     * hatasında eldeki mesajlarda arama sürer ama sonucun eksik olduğu bilinir.
     */
    suspend fun loadFullHistory(cap: Int = 6000): FullHistoryLoadResult {
        if (state().messagesList.isEmpty()) return FullHistoryLoadResult.Failed(0, null)
        var guard = 0
        while (guard < 200) {
            val current = state()
            val count = current.messagesList.size
            if (count >= cap) return FullHistoryLoadResult.Truncated(count)
            // Sayfalanamayan sağlayıcı (opencode/agy) tam transcript'i zaten yükledi.
            val target = ConversationPaging.target(current) ?: return FullHistoryLoadResult.Complete
            val firstBefore = current.messagesList.firstOrNull()?.rowId
            val page = runCatching {
                if (target.provider == Backend.CODEX_APP.id)
                    client.codexAppConversation(current.settings, target.sessionId, before = target.beforeRowId, limit = 100)
                else
                    client.claudeAppConversation(current.settings, target.sessionId, before = target.beforeRowId, limit = 100)
            }.getOrElse { return FullHistoryLoadResult.Failed(count, it) }
            if (page.messages.isEmpty()) return FullHistoryLoadResult.Complete // exhaust
            update { ConversationPaging.mergeOlder(it, page) }
            // İlerleme yoksa (yalnız mükerrer satırlar geldi) exhaust say.
            if (state().messagesList.firstOrNull()?.rowId == firstBefore) return FullHistoryLoadResult.Complete
            guard++
        }
        return FullHistoryLoadResult.Truncated(state().messagesList.size)
    }

    fun showOlderMessages() {
        val current = state(); val target = ConversationPaging.target(current)
        if (target == null) { update { ConversationPaging.growLocalPage(it) }; return }
        scope.launch {
            runCatching {
                if (target.provider == Backend.CODEX_APP.id) client.codexAppConversation(current.settings, target.sessionId, before = target.beforeRowId, limit = 100)
                else client.claudeAppConversation(current.settings, target.sessionId, before = target.beforeRowId, limit = 100)
            }.onSuccess { page -> update { ConversationPaging.mergeOlder(it, page) } }
                .onFailure { update { ConversationPaging.growLocalPage(it) }; reportError("Eski mesajlar yuklenemedi", it) }
        }
    }

    private suspend fun handleRefreshFailure(showErrors: Boolean, error: Throwable) {
        if (restoreOffline()) emit("Bridge kapalı; son kaydedilen konuşma gösteriliyor") else if (showErrors) reportError("Refresh failed", error)
    }

    private suspend fun restoreOffline(): Boolean {
        val key = offlineConversationKey(state()) ?: return false
        val cached = withContext(Dispatchers.IO) { cache.load(key.backend, key.sessionId) }?.takeIf { it.messages.isNotEmpty() } ?: return false
        var restored = false
        update { current ->
            if (offlineConversationKey(current) != key) current else {
                restored = true
                current.copy(transcript = cached.transcript, messagesList = cached.messages, running = false, awaitingApproval = false, approval = null, awaitingFirstOutput = false, offlineConversation = true, staleConversation = false)
            }
        }
        return restored
    }

    private object MissingSessionException : IllegalStateException()
}

/**
 * Poll yolundan gelen OpenCode alanlarini ailenin kutusuna birlestirir.
 *
 * Ayni alanlarin soket yolu [SessionStateReducer]'da; ikisi de yazilmazsa pano
 * yalniz bir tasimada canlanir (bu depoda tekrarlayan hata: delege yazildi,
 * okuma dali unutuldu). Uc-durum kurallari orayla BIREBIR ayni: `null` =
 * karede alan yok -> dokunma, `*Present` bayragi = alan geldi, degeri null
 * olsa bile yaz.
 */
private fun OpencodeUiState.pollIleBirlestir(c: ConversationResult): OpencodeUiState = copy(
    todos = c.todos ?: todos,
    contextPct = if (c.contextPctPresent) c.contextPct else contextPct,
    // Geri sarma seridi — soket kapaliyken tek kaynagi bu yol.
    reverted = if (c.revertedPresent) c.reverted else reverted,
    // Alt-ajan kartlari — soket kapaliyken tek kaynagi bu yol.
    subagents = c.subagents ?: subagents,
    // Paylasim linki — soket kapaliyken tek kaynagi bu yol.
    share = c.share ?: share,
    // Cozumlenen ajan — soket kapaliyken gostergenin tek kaynagi bu yol.
    resolvedAgent = c.resolvedAgent ?: resolvedAgent,
)
