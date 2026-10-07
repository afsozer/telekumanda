package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** OpenCode App'in kurulum/polling dışındaki oturum aksiyonları. */
class OpencodeAppActionsDelegate(
    private val client: BridgeClient,
    private val scope: CoroutineScope,
    private val state: () -> RemoteUiState,
    private val update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    private val emit: suspend (String) -> Unit,
    private val reportError: suspend (String, Throwable) -> Unit,
    private val clearThoughts: () -> Unit,
    private val openSocket: (String) -> Unit,
    private val startPolling: () -> Unit,
    private val refreshConversation: (Boolean) -> Unit,
    private val syncActiveTab: (String, String, String) -> Unit,
    private val onSessionRenamed: (String, String) -> Unit = { _, _ -> },
    // Backend kimligi. 24-26 Eyl 2026'da v1/v2 yan yana dururken bu parametre iki
    // ornegi ayiriyordu; v1 30.09.2026'da sokuldu ve tek kimlik kaldi. Parametre
    // duruyor: yol oneki ve durum ailesi buradan tureiliyor, gelecek bir surum
    // icin ayrim noktasi hazir.
    private val backendId: String = Backend.OPENCODE2_APP.id,
) {
    /** Bu delegenin yazdigi/okudugu OpenCode durum ailesi. */
    private fun fam(): OpencodeUiState = state().opencodeFamily(backendId)
    private fun aile(st: RemoteUiState, block: (OpencodeUiState) -> OpencodeUiState): RemoteUiState =
        st.withOpencodeFamily(backendId, block)
    private val etiket: String get() = "OpenCode"
    private val kisaEtiket: String get() = "OpenCode"
    fun cancelSetup() = update { aile(it) { f -> f.copy(setupPending = false) } }

    // İkinci savunma hattı. Reducer artık yabancı kipi state'e sokmuyor ama
    // bir yolla girerse ("auto" canlıda girmişti) köprü 400 döndürüp oturumu
    // açtırmıyor — kullanıcı boş sohbete bakıyor. Geçersiz kipi göndermek
    // yerine varsayılana düşüyoruz.
    private fun safeMode(): String {
        val current = fam().permissionMode.trim()
        val allowed = fam().permissionModes.map { it.id }.filter { it.isNotBlank() }
            .toSet().ifEmpty { setOf("yolo", "ask", "plan") }
        return if (current in allowed) current else "yolo"
    }

    fun startSession(cwd: String, model: String) = scope.launch {
        val cleanCwd = cwd.trim()
        val cleanModel = model.ifBlank { DEFAULT_MODEL }
        if (cleanCwd.isBlank()) {
            emit("Klasor secin")
            return@launch
        }
        val permissionMode = safeMode()
        runCatching { client.opencodeAppNew(state().settings, cleanCwd, cleanModel, permissionMode, backendId) }
            .onSuccess { sessionId ->
                clearThoughts()
                update {
                    aile(it) { f -> f.panoSifirla().copy(sessionId = sessionId, cwd = cleanCwd, model = cleanModel, setupPending = false) }.copy(
                        backend = backendId,
                        model = "$kisaEtiket · ${cleanModel.substringAfterLast('/')}",
                        currentSession = etiket, messagesList = emptyList(), transcript = "",
                        contextTokens = 0, contextWindow = 0,
                        running = false, awaitingApproval = false,
                    )
                }
                openSocket(sessionId)
                startPolling()
                refreshConversation(false)
                syncActiveTab(sessionId, cleanCwd, cleanModel)
            }
            .onFailure { reportError("OpenCode baslatilamadi", it) }
    }

    fun setModel(id: String) = scope.launch {
        val clean = id.ifBlank { DEFAULT_MODEL }
        update { aile(it) { f -> f.copy(model = clean) }.copy(model = "$kisaEtiket · ${clean.substringAfterLast('/')}") }
        val sid = fam().sessionId
        if (sid.isNotBlank()) {
            runCatching { client.opencodeAppSetModel(state().settings, sid, clean, backendId) }
                .onFailure { reportError("OpenCode modeli değiştirilemedi", it) }
        }
    }

    fun loadDiskSessions() = scope.launch {
        update { aile(it) { f -> f.copy(diskLoading = true) } }
        runCatching { client.opencodeAppDiskSessions(state().settings, backendId) }
            .onSuccess { list -> update { aile(it) { f -> f.copy(diskSessions = list, diskLoading = false) } } }
            .onFailure {
                update { aile(it) { f -> f.copy(diskLoading = false) } }
                reportError("PC oturumlari yuklenemedi", it)
            }
    }

    fun resumeDiskSession(session: AppDiskSession) = scope.launch {
        val permissionMode = safeMode()
        runCatching {
            val sessionId = client.opencodeAppAdopt(state().settings, session.id, session.cwd, backendId)
            if (permissionMode.isNotBlank()) {
                // İZİN KİPİ OTURUMA GİRMEYİ ENGELLEMEZ (22.08.2026, kullanıcı
                // bildirdi). Köprü turu SÜRERKEN bu isteği reddediyor
                // ("cannot change permission mode while running"); istek aynı
                // runCatching'in içinde olduğu için tüm resume çöküyor ve
                // "Oturum devam ettirilemedi" basılıyordu — yani başka cihazda
                // açık, turu devam eden bir oturum uzaktan HİÇ açılamıyordu.
                // Tam da izlemek istediğin oturum.
                //
                // Yutmanın bedeli yok: kip her prompt'un gövdesinde ayrıca
                // gidiyor (ConversationDelegate.send → opencodeAppPrompt), yani
                // sonraki turda zaten uygulanıyor. Hata mesajı da kullanıcıya
                // bir iş yaptırmıyordu.
                runCatching { client.opencodeAppSetPermissionMode(state().settings, sessionId, permissionMode, backendId) }
            }
            sessionId
        }
            .onSuccess { sessionId ->
                clearThoughts()
                val model = fam().model.ifBlank { DEFAULT_MODEL }
                update {
                    aile(it) { f -> f.panoSifirla().copy(sessionId = sessionId, cwd = session.cwd, model = model, setupPending = false) }.copy(
                        backend = backendId,
                        model = "$kisaEtiket · ${model.substringAfterLast('/')}",
                        currentSession = etiket, messagesList = emptyList(), transcript = "",
                        contextTokens = 0, contextWindow = 0,
                        running = false, awaitingApproval = false, wsConnected = false,
                    )
                }
                openSocket(sessionId)
                startPolling()
                refreshConversation(false)
                syncActiveTab(sessionId, session.cwd, model)
            }
            .onFailure { reportError("Oturum devam ettirilemedi", it) }
    }

    // `requestId` null ise o an ekrandaki onay kartının kimliği kullanılır.
    fun approve(allow: Boolean, requestId: String? = null) = scope.launch {
        val sid = fam().sessionId
        if (sid.isBlank()) return@launch
        val kimlik = requestId ?: state().bekleyenOnayKimligi
        runCatching { client.opencodeAppApprove(state().settings, sid, allow, backend = backendId, requestId = kimlik) }
            .onSuccess { refreshConversation(false) }
            .onFailure { onayHatasiniIsle(it, emit, { refreshConversation(false) }) { e -> reportError("Onay gonderilemedi", e) } }
    }

    fun answerQuestions(answers: List<ApprovalAnswer>, requestId: String? = null) = scope.launch {
        val sid = fam().sessionId
        if (sid.isBlank() || answers.isEmpty()) return@launch
        val kimlik = requestId ?: state().bekleyenOnayKimligi
        runCatching { client.opencodeAppApprove(state().settings, sid, true, answers, backendId, kimlik) }
            .onSuccess { refreshConversation(false) }
            .onFailure { onayHatasiniIsle(it, emit, { refreshConversation(false) }) { e -> reportError("Cevap gonderilemedi", e) } }
    }

    /**
     * Bağlamı AI ile özetle — görev panosundaki "Sıkıştır" aksiyonunun ucu.
     *
     * Sohbete "/compact" yazma yolu ConversationDelegate'te zaten var; bu onun
     * KOPYASI değil, aynı köprü ucuna ikinci bir kapı: doluluk %80'i geçtiğinde
     * kullanıcının composer'a komut yazması gerekmesin. Köprü fire-and-forget
     * çalışıyor (ok hemen döner), "özetleniyor/özetlendi" satırları snapshot'la
     * geliyor — bu yüzden burada ayrıca bir ilerleme durumu tutulmuyor.
     *
     * Tur sürerken köprü reddediyor; hatayı yutmak yerine kullanıcıya söylüyoruz,
     * aksi halde tuş hiçbir şey yapmamış gibi görünürdü.
     */
    fun compact() = scope.launch {
        val sid = fam().sessionId
        if (sid.isBlank()) return@launch
        if (state().running) { emit("Tur sürerken özetlenemez — önce bitmesini bekle."); return@launch }
        val r = runCatching { client.opencodeAppCompact(state().settings, sid, backendId) }
        if (r.getOrNull() == true) {
            refreshConversation(false)
        } else {
            val detay = r.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
            emit(if (detay != null) "Özetleme başlatılamadı: $detay" else "Özetleme başlatılamadı — tur sürüyor olabilir.")
        }
    }

    /**
     * Tur sürerken mesaj gönderir. OpenCode'da YALNIZ kuyruk var: mesaj süren
     * turu kesmez, tur bitince kendi turu olarak çalışır. [interrupt] parametresi
     * omp/hermes ile aynı imzayı korumak için duruyor ama true gelemez —
     * capability userInputSteer=false, composer yönlendir tuşunu hiç çizmiyor.
     *
     * Kullanıcı satırını KÖPRÜ ekliyor; burada state'e elle mesaj yazma, yoksa
     * satır iki kez görünür (omp/hermes'teki uyarının aynısı).
     */
    fun sendDuringTurn(text: String, interrupt: Boolean) = scope.launch {
        val clean = text.trim()
        val sid = fam().sessionId
        if (clean.isBlank() || sid.isBlank()) return@launch
        // v2'de gerçek steer var (delivery:"steer"): süren tura ANINDA enjekte
        // edilir, tur kesilmez — ajan mesajı okur ve yön değiştirebilir.
        // v1'de bu yol hiç yok; eskiden "yönlendiremiyor" deyip kuyruğa atıyordu.
        if (interrupt && backendId == Backend.OPENCODE2_APP.id) {
            runCatching { client.opencodeAppSteer(state().settings, sid, clean, backendId) }
                .onSuccess { emit("Tura yönlendirildi — ajan mesajı görecek") }
                .onFailure { reportError("Yönlendirilemedi", it) }
            return@launch
        }
        if (interrupt) { emit("OpenCode süren turu yönlendiremiyor — mesaj sıraya bırakıldı"); }
        runCatching { client.opencodeAppFollowUp(state().settings, sid, clean, backendId) }
            .onSuccess { emit("Ajana bırakıldı: tur bitince işleyecek") }
            .onFailure { reportError("Sıraya alınamadı", it) }
    }

    /**
     * "Değişiklikler" listesini çeker — uzun otonom koşu bitince telefondan
     * "bu oturum neyi değiştirdi" incelemesi.
     *
     * YOKLAMAYA BİNMİYOR, bilerek: liste her kare tazelense uzun koşuda köprü
     * her seferinde oturumun bütün mesaj listesini serve'den okurdu (görünüm
     * kapalıyken de). Kullanıcı açınca çekiliyor, tekrar açınca yeniden.
     *
     * Hata BİLDİRİLİR ama önceki liste KORUNUR: köprü bir an cevap veremediği
     * için ekranı boşaltmak, "bu oturum dosya değiştirmedi" yalanına dönüşürdü.
     */
    fun loadDiff() = scope.launch {
        val sid = fam().sessionId
        if (sid.isBlank()) return@launch
        update { aile(it) { f -> f.copy(diffLoading = true) } }
        runCatching { client.backendSessionDiff(state().settings, backendId, sid) }
            .onSuccess { diff ->
                update { aile(it) { f -> f.copy(diff = diff, diffLoading = false) } }
            }
            .onFailure {
                update { aile(it) { f -> f.copy(diffLoading = false) } }
                reportError("Değişiklikler alınamadı", it)
            }
    }

    /**
     * "Geri sar" listesi — oturumun kullanıcı mesajları, kimlikleriyle.
     *
     * loadDiff ile aynı kural: yoklamaya binmez, kullanıcı listeyi açınca
     * çekilir (her istek köprüye oturumun bütün mesaj listesini okutuyor).
     * Hata bildirilir ama önceki liste KORUNUR — boşaltmak "geri sarılacak
     * nokta yok" yalanına dönüşürdü.
     */
    fun loadCheckpoints() = scope.launch {
        val sid = fam().sessionId
        if (sid.isBlank()) return@launch
        update { aile(it) { f -> f.copy(checkpointsLoading = true) } }
        runCatching { client.opencodeAppCheckpoints(state().settings, sid, backendId) }
            .onSuccess { list ->
                update { aile(it) { f -> f.copy(checkpoints = list, checkpointsLoading = false) } }
            }
            .onFailure {
                update { aile(it) { f -> f.copy(checkpointsLoading = false) } }
                reportError("Geri sarma noktaları alınamadı", it)
            }
    }

    /**
     * Bir alt-ajanın konuşması. loadDiff/loadCheckpoints ile aynı kural:
     * yoklamaya binmez, kullanıcı KARTA DOKUNUNCA çekilir.
     *
     * Önceki transkript, yükleme başlarken TEMİZLENİR (diff/checkpoint'ten
     * ayrıldığı tek nokta): orada aynı oturumun aynı listesi tazeleniyor, burada
     * BAŞKA bir ajanın konuşması açılıyor — eskisini bırakmak, dokunulan kartın
     * altında yanlış ajanın metnini göstermek olurdu.
     */
    fun loadSubagentTranscript(childId: String) = scope.launch {
        val sid = fam().sessionId
        if (sid.isBlank() || childId.isBlank()) return@launch
        update { aile(it) { f -> f.copy(subagentTranscript = null, subagentTranscriptLoading = true) } }
        runCatching { client.opencodeAppSubagentConversation(state().settings, sid, childId) }
            .onSuccess { t ->
                update { aile(it) { f -> f.copy(subagentTranscript = t, subagentTranscriptLoading = false) } }
                if (t == null) emit("Alt ajanın konuşması okunamadı.")
            }
            .onFailure {
                update { aile(it) { f -> f.copy(subagentTranscriptLoading = false) } }
                reportError("Alt ajan konuşması alınamadı", it)
            }
    }

    /**
     * Seçilen noktaya geri sarar. YIKICI — çağıran ONAY DİYALOĞUNDAN sonra
     * çağırmalı (dosyalar da geri sarılabiliyor).
     *
     * Durum ("geri sarıldı" şeridi) buradan elle yazılmıyor: köprü snapshot'ta
     * `reverted` alanını taşıyor ve refreshConversation onu getiriyor — iki
     * kaynak olsa biri bayatlar. Bildirim metni ÖLÇÜLEN sonuca göre kuruluyor:
     * dosyalar sarılmadıysa "yalnız konuşma" denir, "dosyalar da geri alındı"
     * diye uydurulmaz (opencode git dışında anlık görüntü tutmuyor).
     */
    fun revertTo(messageID: String) = scope.launch {
        val sid = fam().sessionId
        val id = messageID.trim()
        if (sid.isBlank() || id.isBlank()) return@launch
        if (state().running) { emit("Tur sürerken geri sarılamaz — önce bitmesini bekle."); return@launch }
        runCatching { client.opencodeAppRevert(state().settings, sid, id, backendId) }
            .onSuccess { sonuc ->
                refreshConversation(false)
                emit(
                    if (sonuc.filesReverted && sonuc.files > 0) "Geri sarıldı · ${sonuc.files} dosya eski hâline döndü"
                    else if (sonuc.filesReverted) "Geri sarıldı · dosyalarda değişiklik yoktu"
                    else "Geri sarıldı · yalnız konuşma (dosyalar için git deposu gerekiyor)"
                )
            }
            .onFailure { reportError("Geri sarılamadı", it) }
    }

    /** Şeritteki "Geri Al" — son geri sarmayı iptal eder (dosyalar dahil). */
    fun unrevert() = scope.launch {
        val sid = fam().sessionId
        if (sid.isBlank()) return@launch
        runCatching { client.opencodeAppUnrevert(state().settings, sid, backendId) }
            .onSuccess {
                refreshConversation(false)
                emit("Geri sarma iptal edildi")
            }
            .onFailure { reportError("Geri sarma iptal edilemedi", it) }
    }

    /**
     * Oturumu İNTERNETE açık bir linkte yayınlar.
     *
     * ÇAĞIRAN ONAY ALMIŞ OLMALI (bkz. Ui3OturumPaylas): bu, uygulamada dış
     * dünyaya veri açan tek eylem. Onay burada değil arayüzde, çünkü kullanıcı
     * uyarıyı eylemi seçtiği ANDA görmeli.
     *
     * Durum ("paylaşıldı" satırı) buradan elle yazılmıyor: köprü snapshot'ta
     * `share` alanını taşıyor — iki kaynak olsa biri bayatlardı. Dönen linki
     * yine de çağırana veriyoruz, çünkü paylaşım sayfası (share intent) linki
     * ANINDA istiyor ve snapshot'ın gelmesini beklemek tuşu ölü gösterirdi.
     */
    fun share(onLink: (String) -> Unit = {}) = scope.launch {
        val sid = fam().sessionId
        if (sid.isBlank()) return@launch
        runCatching { client.opencodeAppShare(state().settings, sid) }
            .onSuccess { url ->
                if (url.isBlank()) { emit("Paylaşım linki alınamadı"); return@onSuccess }
                update { aile(it) { f -> f.copy(share = url) } }
                refreshConversation(false)
                onLink(url)
            }
            .onFailure { reportError("Paylaşılamadı", it) }
    }

    /** Yayını kaldırır — link ölür. */
    fun unshare() = scope.launch {
        val sid = fam().sessionId
        if (sid.isBlank()) return@launch
        runCatching { client.opencodeAppUnshare(state().settings, sid) }
            .onSuccess {
                update { aile(it) { f -> f.copy(share = "") } }
                refreshConversation(false)
                emit("Paylaşım kaldırıldı — link artık açılmıyor")
            }
            .onFailure { reportError("Paylaşım kaldırılamadı", it) }
    }

    /**
     * Özel komut kataloğu. Oturuma değil KURULUMA ait (köprü serve'ün global
     * listesini veriyor, 60 sn önbellekli), bu yüzden oturum değişince
     * sıfırlanmıyor ve "/" yazıldıkça sorulması ucuz.
     *
     * Hata sessiz: liste boş kalır ve öneri şeridi hiç çizilmez. Kullanıcının
     * yapabileceği bir şey yok, "/" her yazışta hata basmak gürültü olurdu.
     */
    fun loadCommands() = scope.launch {
        runCatching { client.opencodeAppCommands(state().settings, backendId) }
            .onSuccess { list -> update { aile(it) { f -> f.copy(commands = list) } } }
    }

    /**
     * Seçilen komutu çalıştırır ve composer'ı boşaltır.
     *
     * Kullanıcı satırını KÖPRÜ ekliyor (prompt'la aynı kural, bkz.
     * sendDuringTurn): buradan state'e elle mesaj yazma, yoksa satır iki kez
     * görünür.
     */
    fun runCommand(command: String, arguments: String) = scope.launch {
        val sid = fam().sessionId
        val ad = command.trim().trimStart('/')
        if (sid.isBlank() || ad.isBlank()) return@launch
        if (state().running) { emit("Tur sürerken komut çalıştırılamaz — önce bitmesini bekle."); return@launch }
        update { it.copy(input = "") }
        runCatching { client.opencodeAppRunCommand(state().settings, sid, ad, arguments.trim(), backendId) }
            .onSuccess { refreshConversation(false) }
            .onFailure { reportError("Komut çalıştırılamadı", it) }
    }

    /**
     * AGENTS.md init — bir tur koşuyor (proje analizi + dosya yazımı).
     * ÇAĞIRAN ONAY ALMIŞ OLMALI: bu, dokunuşla model harcayan bir eylem.
     */
    fun initAgents() = scope.launch {
        val sid = fam().sessionId
        if (sid.isBlank()) return@launch
        if (state().running) { emit("Tur sürerken başlatılamaz — önce bitmesini bekle."); return@launch }
        runCatching { client.opencodeAppInitAgents(state().settings, sid, backendId) }
            .onSuccess { refreshConversation(false) }
            .onFailure { reportError("AGENTS.md turu başlatılamadı", it) }
    }

    fun loadInfo() = scope.launch {
        runCatching { client.opencodeAppInfo(state().settings, backendId) }
            .onSuccess { info -> update { aile(it) { f -> f.copy(info = info) } } }
            .onFailure { reportError("OpenCode info alınamadı", it) }
    }

    fun setPermissionMode(mode: String) = scope.launch {
        val clean = mode.trim()
        val sid = fam().sessionId
        if (sid.isNotBlank()) {
            runCatching { client.opencodeAppSetPermissionMode(state().settings, sid, clean, backendId) }
                .onFailure { reportError("OpenCode mod değiştirilemedi", it) }
        }
        update { aile(it) { f -> f.copy(permissionMode = clean) } }
    }

    fun loadAgents() = scope.launch {
        runCatching {
            var agents = client.opencodeAppAgents(state().settings, backendId)
            // v2 serve ilk HTTP isteğinde henüz ajan kataloğunu doldurmamış
            // olabiliyor: canlıda ilk istek 0, hemen sonraki 4 döndürdü.
            // Açılan seçicide boş listeyi kalıcı göstermemek için kısa bekle.
            if (backendId == Backend.OPENCODE2_APP.id) {
                repeat(3) {
                    if (agents.isNotEmpty()) return@runCatching agents
                    delay(750)
                    agents = client.opencodeAppAgents(state().settings, backendId)
                }
            }
            agents
        }
            .onSuccess { list -> update { aile(it) { f -> f.copy(agents = list) } } }
            .onFailure { reportError("Ajan listesi yüklenemedi", it) }
    }

    // Bos ad = otomatik. Ajan kendi modelini bildiriyorsa kopru oturum modelini
    // de ona ceker; donen degeri yazarak model cipini de senkron tutuyoruz.
    fun setAgent(name: String) = scope.launch {
        val clean = name.trim()
        val sid = fam().sessionId
        if (sid.isBlank()) {
            update { aile(it) { f -> f.copy(agent = clean) } }
            return@launch
        }
        runCatching { client.opencodeAppSetAgent(state().settings, sid, clean, backendId) }
            .onSuccess { (agent, model) ->
                update {
                    val nextModel = model.ifBlank { it.opencodeFamily(backendId).model }
                    aile(it) { f ->
                        f.copy(
                            agent = agent,
                            model = nextModel,
                            // Açık seçimde çözümlenen ajan seçimin kendisidir.
                            // Otomatiğe DÖNERKEN ise bilinmeze düşülür: önceki
                            // seçimi taşımak, köprünün kararı gelene kadar çipin
                            // yanlış ajanı söylemesi olurdu.
                            resolvedAgent = agent,
                        )
                    }.copy(
                        model = "$kisaEtiket · ${nextModel.substringAfterLast('/')}",
                    )
                }
            }
            .onFailure { reportError("Ajan değiştirilemedi", it) }
    }

    fun loadPermissionModes() = scope.launch {
        runCatching { client.opencodeAppPermissionModes(state().settings, backendId) }
            .onSuccess { modes -> update { aile(it) { f -> f.copy(permissionModes = modes) } } }
            .onFailure { reportError("İzin modlari yuklenemedi", it) }
    }

    fun pin(id: String) = scope.launch {
        runCatching { client.opencodeAppPin(state().settings, id, backendId) }
            .onSuccess { loadDiskSessions() }
            .onFailure { reportError("Oturum sabitlenemedi", it) }
    }

    // ── Oturum talimatları + kayıtlı izin kuralları (yalnız v2) ─────────────
    // Aç-çek kuralı: görünüm açılınca bir kez istenir, yoklamaya binmez.
    // v1'de uç yok — yanlışlıkla çağrılırsa sessiz değil "v2 özelliği" denir.

    fun loadInstructions() = scope.launch {
        val sid = fam().sessionId
        if (sid.isBlank()) return@launch
        if (backendId != Backend.OPENCODE2_APP.id) return@launch
        update { aile(it) { f -> f.copy(instructionsLoading = true) } }
        runCatching { client.opencode2Instructions(state().settings, sid) }
            .onSuccess { list -> update { aile(it) { f -> f.copy(instructions = list, instructionsLoading = false) } } }
            .onFailure {
                update { aile(it) { f -> f.copy(instructionsLoading = false) } }
                reportError("Talimatlar okunamadı", it)
            }
    }

    fun putInstruction(key: String, value: String) = scope.launch {
        val sid = fam().sessionId
        if (sid.isBlank() || backendId != Backend.OPENCODE2_APP.id) return@launch
        runCatching { client.opencode2PutInstruction(state().settings, sid, key, value) }
            .onSuccess { loadInstructions() }
            .onFailure { reportError("Talimat kaydedilemedi", it) }
    }

    fun deleteInstruction(key: String) = scope.launch {
        val sid = fam().sessionId
        if (sid.isBlank() || backendId != Backend.OPENCODE2_APP.id) return@launch
        runCatching { client.opencode2DeleteInstruction(state().settings, sid, key) }
            .onSuccess { loadInstructions() }
            .onFailure { reportError("Talimat silinemedi", it) }
    }

    fun loadSavedPermissions() = scope.launch {
        if (backendId != Backend.OPENCODE2_APP.id) return@launch
        update { aile(it) { f -> f.copy(savedPermissionsLoading = true) } }
        runCatching { client.opencode2SavedPermissions(state().settings) }
            .onSuccess { list -> update { aile(it) { f -> f.copy(savedPermissions = list, savedPermissionsLoading = false) } } }
            .onFailure {
                update { aile(it) { f -> f.copy(savedPermissionsLoading = false) } }
                reportError("İzin kuralları okunamadı", it)
            }
    }

    fun deleteSavedPermission(id: String) = scope.launch {
        if (backendId != Backend.OPENCODE2_APP.id) return@launch
        runCatching { client.opencode2DeleteSavedPermission(state().settings, id) }
            .onSuccess { loadSavedPermissions() }
            .onFailure { reportError("İzin kuralı silinemedi", it) }
    }

    fun unpin(id: String) = scope.launch {
        runCatching { client.opencodeAppUnpin(state().settings, id, backendId) }
            .onSuccess { loadDiskSessions() }
            .onFailure { reportError("Oturum sabitlemesi kaldırılamadı", it) }
    }

    fun rename(id: String, title: String) = scope.launch {
        runCatching { client.opencodeAppRename(state().settings, id, title, backendId) }
            .onSuccess {
                onSessionRenamed(id, title)
                loadDiskSessions()
            }
            .onFailure { reportError("Oturum yeniden adlandırılamadı", it) }
    }

    private companion object {
        const val DEFAULT_MODEL = "deepseek/deepseek-flash"
    }
}
