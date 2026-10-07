package com.agent.bridge

object SessionStateReducer {
    fun shouldRefreshCoworkOutputs(state: RemoteUiState, snapshot: BackendStreamSnapshot): Boolean =
        state.backend == Backend.COWORK.id &&
            normalizeCoworkProvider(state.coworkProvider) != Backend.CLAUDE_APP.id &&
            state.running &&
            snapshot.running == false

    fun shouldShowCodexPlan(state: RemoteUiState): Boolean {
        val provider = if (state.backend == Backend.COWORK.id) {
            normalizeCoworkProvider(state.coworkProvider)
        } else {
            state.backend
        }
        return provider == Backend.CODEX_APP.id &&
            state.running &&
            state.codex.plan.isNotEmpty()
    }

    // A1 savunması yalnız claude-app oturum kimliği anlamlıyken uygulanır; aksi halde
    // codex/opencode akışlarının id'leri claudeAppSessionId'yi kirletir.
    // opencode alanları (ajan seçimi gibi) yalnız opencode aktifken yazılır;
    // aksi halde başka backend'in snapshot'ı opencode state'ini kirletir.
    private fun claudeAppActive(state: RemoteUiState): Boolean =
        state.backend == Backend.CLAUDE_APP.id ||
            (state.backend == Backend.COWORK.id && normalizeCoworkProvider(state.coworkProvider) == Backend.CLAUDE_APP.id)

    /**
     * Conversation ve WebSocket snapshot'larındaki ortak provider seçeneklerini
     * yalnız aktif provider'ın state'ine yazar; koşulsuz tek bir provider'a
     * yazmak diğerlerinin model picker'ını boş bırakıyordu.
     *
     * Bugün yalnız opencode buraya düşüyor (diğer ACP backend'i 1 Ağu 2026'da kaldırıldı).
     * Dal yapısı korundu: ikinci bir ACP backend'i eklenirse izolasyonu yeniden
     * kurmak yerine sadece dal eklemek yeter. Bunu doğrulayan test iki backend
     * gerektirdiği için silindi — yeni backend gelirse geri yazılmalı.
     */
    // Sağlayıcıların izin kipleri ORTAK DEĞİL: Claude'un "auto"su opencode'da
    // geçersiz ve köprü 400 döndürüyor. applyStreamSnapshot gelen kareyi
    // sahibine bakmadan AKTİF backend'e yazdığı için, sekme değişimi sırasında
    // gelen geç bir Claude karesi opencode state'ine "auto" yazabiliyordu;
    // sonraki "oturuma devam et" çağrısı da "invalid permission mode: auto"
    // ile düşüyordu (canlıda ölçüldü, 15 Ağu 2026). Yabancı kipi burada ele.
    private val OPENCODE_FALLBACK_MODES = setOf("yolo", "ask", "plan")
    private val OMP_FALLBACK_MODES = setOf("yolo", "write", "ask")

    private fun acceptMode(value: String?, known: List<PermissionMode>, fallback: Set<String>): String? {
        val mode = value?.trim().orEmpty()
        if (mode.isEmpty()) return null
        // Köprüden gelen canlı liste varsa o otoriter; yoksa statik yedek.
        val allowed = known.map { it.id }.filter { it.isNotBlank() }.toSet().ifEmpty { fallback }
        return if (mode in allowed) mode else null
    }

    fun reduceBackendOptions(
        state: RemoteUiState,
        availableModels: List<BackendModel>? = null,
        permissionMode: String? = null,
        permissionModes: List<PermissionMode>? = null,
        commands: List<SlashCommand>? = null,
    ): RemoteUiState {
        val provider = if (state.backend == Backend.COWORK.id) {
            normalizeCoworkProvider(state.coworkProvider)
        } else {
            state.backend
        }
        return when (provider) {
            Backend.OPENCODE2_APP.id -> state.copy(
                opencode = state.opencode.copy(
                    availableModels = availableModels ?: state.opencode.availableModels,
                    permissionMode = acceptMode(permissionMode, state.opencode.permissionModes, OPENCODE_FALLBACK_MODES)
                        ?: state.opencode.permissionMode,
                    permissionModes = permissionModes ?: state.opencode.permissionModes,
                ),
            )
            Backend.OPENCODE2_APP.id -> state.copy(
                opencode = state.opencode.copy(
                    availableModels = availableModels ?: state.opencode.availableModels,
                    permissionMode = acceptMode(permissionMode, state.opencode.permissionModes, OPENCODE_FALLBACK_MODES)
                        ?: state.opencode.permissionMode,
                    permissionModes = permissionModes ?: state.opencode.permissionModes,
                ),
            )
            Backend.OMP.id -> state.copy(
                omp = state.omp.copy(
                    availableModels = availableModels ?: state.omp.availableModels,
                    permissionMode = acceptMode(permissionMode, state.omp.permissionModes, OMP_FALLBACK_MODES)
                        ?: state.omp.permissionMode,
                    permissionModes = permissionModes ?: state.omp.permissionModes,
                ),
            )
            else -> state
        }
    }

    fun reduceStreamSnapshot(state: RemoteUiState, snapshot: BackendStreamSnapshot): RemoteUiState {
        // A1 savunma: bridge snapshot'ın içine kendi sessionId'sini gömer. Aktif backend
        // claude-app iken bu id state'tekinden farklıysa sunucu oturumu re-key etmiştir
        // (ör. başka bir istemcinin /clear'ı). Yeni id'yi benimse ve boş mesaj listesini
        // KABUL et (normalde boş liste, geçici boş snapshot'lara karşı yok sayılır).
        val rekeyedId = snapshot.sessionId
            ?.takeIf { claudeAppActive(state) && it != state.claudeAppSessionId && state.claudeAppSessionId.isNotBlank() }
        val base = if (rekeyedId != null) {
            state.copy(
                claude = state.claude.copy(sessionId = rekeyedId),
                messagesList = snapshot.messages ?: emptyList(),
                transcript = snapshot.transcript ?: "",
            )
        } else state
        val reduced = base.copy(
            transcript = if (rekeyedId != null) base.transcript else (snapshot.transcript?.takeIf { it.isNotBlank() } ?: base.transcript),
            // WS penceresi de yüklü geçmişi kırpmaz (bkz. ConversationPaging.mergeTailWindow);
            // re-key durumunda base zaten sunucunun yeni listesini almış durumda.
            messagesList = if (rekeyedId != null) base.messagesList
                else snapshot.messages?.let { ConversationPaging.mergeTailWindow(base.messagesList, it) } ?: base.messagesList,
            // Soketten mesaj listesi geldiyse prefill artık bayat değil, gerçek görüntü.
            staleConversation = if (snapshot.messages != null) false else state.staleConversation,
            running = snapshot.running ?: state.running,
            awaitingApproval = snapshot.awaitingApproval ?: state.awaitingApproval,
            approval = when (snapshot.awaitingApproval) {
                true -> snapshot.approval ?: state.approval
                false -> null
                null -> state.approval
            },
            awaitingFirstOutput = snapshot.awaitingFirstOutput ?: state.awaitingFirstOutput,
            interruptStuck = snapshot.interruptStuck ?: state.interruptStuck,
            choices = snapshot.choices ?: state.choices,
            contextTokens = snapshot.contextTokens ?: state.contextTokens,
            contextWindow = snapshot.contextWindow ?: state.contextWindow,
            cost = snapshot.cost ?: state.cost,
            claude = base.claude.copy(
                permissionMode = snapshot.permissionMode?.takeIf { it.isNotBlank() } ?: base.claude.permissionMode,
                effort = if (snapshot.effort != null && (state.backend == Backend.CLAUDE_APP.id ||
                        (state.backend == Backend.COWORK.id && normalizeCoworkProvider(state.coworkProvider) == Backend.CLAUDE_APP.id))) snapshot.effort else base.claude.effort,
            ),
            // Effort claude-app/cowork(claude) ve codex-app'te ayrı alana yansır; snapshot.effort
            // "" da taşıyabilir (varsayılan) — bu yüzden null-check ile ayır, boşu da kabul et.
            codex = state.codex.copy(
                effort = if (snapshot.effort != null && (state.backend == Backend.CODEX_APP.id ||
                        (state.backend == Backend.COWORK.id && normalizeCoworkProvider(state.coworkProvider) == Backend.CODEX_APP.id))) {
                    snapshot.effort
                } else state.codex.effort,
                plan = snapshot.plan ?: state.codex.plan,
                planDraft = snapshot.planDraft ?: state.codex.planDraft,
                // Hedef yalniz codex aktifken (codex-app ya da cowork+codex) ve snapshot'ta
                // "goal" alani GELDIYSE yazilir; effort'la ayni backend kapisi. goalPresent
                // false iken (eski kopru) mevcut hedef korunur, null gelirse temizlenir.
                goal = if (snapshot.goalPresent && (state.backend == Backend.CODEX_APP.id ||
                        (state.backend == Backend.COWORK.id && normalizeCoworkProvider(state.coworkProvider) == Backend.CODEX_APP.id))) {
                    snapshot.goal
                } else state.codex.goal,
                contextPercent = if ((snapshot.contextTokens ?: 0) > 0 && (snapshot.contextWindow ?: 0) > 0) {
                    ((snapshot.contextTokens!!.toDouble() / snapshot.contextWindow!!.toDouble()) * 100).toInt()
                } else state.codex.contextPercent,
            ),
            omp = state.omp.copy(
                variant = if (snapshot.effort != null && state.backend == Backend.OMP.id) snapshot.effort else state.omp.variant,
            ),
            cowork = state.cowork.copy(outputs = snapshot.outputs ?: state.cowork.outputs),
        )
        // Ajan seçimi / pano / geri sarma / bağlam: AKTİF OpenCode AİLESİNE
        // yazılır. Eski kapı (`opencodeAktif`) yalnız v1'i tanıyordu; v2
        // sekmesinde bu alanlar sessizce düşüyor, geri sarma şeridi ve ajan
        // çipi hiç güncellenmiyordu. Hiçbir OpenCode ailesi aktif değilse
        // HİÇBİR ŞEY yazılmaz — başka sağlayıcının geç gelen karesi opencode
        // kutularını süpürmesin (bu blokun asıl varlık sebebi).
        val ocId = state.aktifOpencodeBackendId()
        val ocReduced = if (ocId == null) reduced else reduced.withOpencodeFamily(ocId) { f ->
            f.copy(
                // "" geçerli bir değer (otomatik), o yüzden null kontrolü.
                agent = snapshot.agent ?: f.agent,
                resolvedAgent = snapshot.resolvedAgent ?: f.resolvedAgent,
                // todos null = karede alan yok -> dokunma. Boş liste gerçek bir
                // temizlik. contextPct'te üç-durumu `contextPctPresent` taşıyor:
                // null tek başına "ölçülemiyor" da demek olabiliyor.
                todos = snapshot.todos ?: f.todos,
                contextPct = if (snapshot.contextPctPresent) snapshot.contextPct else f.contextPct,
                // Geri sarma şeridi: alan hiç yoksa (eski köprü) dokunma, alan
                // var null ise şeridi indir.
                reverted = if (snapshot.revertedPresent) snapshot.reverted else f.reverted,
                subagents = snapshot.subagents ?: f.subagents,
                // Paylaşım linki: "" yayın kalkmış demek.
                share = snapshot.share ?: f.share,
            )
        }
        return reduceBackendOptions(
            state = ocReduced,
            availableModels = snapshot.availableModels,
            permissionMode = snapshot.permissionMode,
            permissionModes = snapshot.permissionModes,
            commands = snapshot.commands,
        )
    }
}
