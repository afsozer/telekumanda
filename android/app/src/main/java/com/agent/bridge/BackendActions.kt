package com.agent.bridge

// Faz 1 (ui2 hazirlik): jenerik aksiyon kapisi. Yeni UI (ui2) backend'e ozel
// fonksiyonlari ASLA dogrudan cagirmaz; yalniz bu kapiyi kullanir. Kapi bu
// fazda hicbir mevcut ekrandan CAGRILMAZ — sadece tanimlanir (davranis notr).
// Capability disi cagrilar bilincli no-op'tur: kontrol zaten capability'ye
// gore cizilmez (backendCapabilities), kapi savunma amacli sessiz kalir.

fun RemoteViewModel.enterBackend(id: String) {
    switchBackend(id)
}

fun RemoteViewModel.exitBackend(id: String) {
    when (id) {
        "agy" -> exitAgyMode()
        "claude-app" -> exitClaudeAppMode()
        "codex-app" -> exitCodexAppMode()
        "opencode2-app" -> exitOpencode2AppMode()
        "omp" -> exitOmpMode()
        "cowork" -> exitClaudeAppMode()
    }
}

fun RemoteViewModel.setBackendModel(id: String, model: String) {
    when (id) {
        "agy" -> setAgyModel(model)
        "claude-app" -> setClaudeAppModel(model)
        "codex-app" -> setCodexAppModel(model)
        "opencode2-app" -> setOpencodeAppModel(model)
        "omp" -> setOmpModel(model)
        "cowork" -> setCoworkModel(model)
    }
}

fun RemoteViewModel.setBackendPermissionMode(id: String, mode: String) {
    when (id) {
        "claude-app" -> setClaudeAppPermissionMode(mode)
        "codex-app" -> codexAppSetPermissionMode(mode)
        "opencode2-app" -> setOpencodeAppPermissionMode(mode)
        "omp" -> setOmpPermissionMode(mode)
        "cowork" -> setCoworkYoloMode(mode == "yolo")
    }
}

fun RemoteViewModel.loadBackendPermissionModes(id: String) {
    when (id) {
        "codex-app" -> loadCodexAppPermissionModes()
        "opencode2-app" -> loadOpencodeAppPermissionModes()
        "omp" -> loadOmpPermissionModes()
        "cowork" -> when (normalizeCoworkProvider(uiState.value.coworkProvider)) {
            "codex-app" -> loadCodexAppPermissionModes()
            "opencode2-app" -> loadOpencodeAppPermissionModes()
        }
    }
}

fun RemoteViewModel.setBackendEffort(id: String, effort: String) {
    when (id) {
        "claude-app" -> setClaudeAppEffort(effort)
        "codex-app" -> codexAppSetEffort(effort)
        "opencode2-app" -> setOpencodeAppVariant(effort)
        "omp" -> setOmpEffort(effort)
        "cowork" -> when (normalizeCoworkProvider(uiState.value.coworkProvider)) {
            "claude-app" -> setClaudeAppEffort(effort)
            "codex-app" -> codexAppSetEffort(effort)
            "opencode2-app" -> setOpencodeAppVariant(effort)
        }
    }
}

fun RemoteViewModel.loadBackendEfforts(id: String) {
    when (id) {
        "claude-app" -> loadClaudeAppEfforts()
        "codex-app" -> loadCodexAppEfforts()
        // opencode'da efor listesi model katalogundan gelir, ayri bir uc yok:
        // katalogu tazelemek yeter.
        "opencode2-app" -> loadOpencode2AppModels()
        "omp" -> { loadOmpInfo(); loadOmpModels() }
        "cowork" -> when (normalizeCoworkProvider(uiState.value.coworkProvider)) {
            "claude-app" -> loadClaudeAppEfforts()
            "codex-app" -> loadCodexAppEfforts()
            "opencode2-app" -> loadOpencode2AppModels()
        }
    }
}

fun RemoteViewModel.loadBackendInfo(id: String) {
    // Model çipi bu kapıyı çağırır: oturum info'sunun yanında model kataloğu da
    // burada garantilenir. Modeller yalnız enter*Mode'da yükleniyordu; sekme geri
    // yükleme / hub hızlı-başlat gibi enter'sız akışlarda sheet boş kalıyordu
    // (cowork'teki 7c3ab04 ile aynı desen, üç sağlayıcıya genellendi).
    when (id) {
        "claude-app" -> { loadClaudeAppInfo(); loadClaudeAppModels() }
        "codex-app" -> { loadCodexAppInfo(); loadCodexAppModels() }
        "opencode2-app" -> { loadOpencodeAppInfo(); loadOpencode2AppModels() }
        "omp" -> { loadOmpInfo(); loadOmpModels() }
        // Cowork model listesi sağlayıcı kataloğundan gelir (session'a özel info değil).
        // Hızlı-başlat akışında enterCoworkMode çalışmadığından burada garantilenir.
        "cowork" -> loadCoworkProviderCatalog()
    }
}

// Skill pill'i: envanter info yanıtının içinde geldiği için ayrı uç yok — her
// sağlayıcının kendi info çağrısı yeter. Sheet açılırken çağrılır ki disk
// üzerinde yeni kurulan skill uygulamayı yeniden başlatmadan görünsün.
fun RemoteViewModel.loadBackendSkills(id: String) {
    when (id) {
        "claude-app" -> loadClaudeAppInfo()
        "codex-app" -> loadCodexAppInfo()
        "opencode2-app" -> loadOpencodeAppInfo()
        "omp" -> loadOmpInfo()
        "cowork" -> when (normalizeCoworkProvider(uiState.value.coworkProvider)) {
            "claude-app" -> loadClaudeAppInfo()
            "codex-app" -> loadCodexAppInfo()
            "opencode2-app" -> loadOpencodeAppInfo()
        }
    }
}

// Ajan pill'i: turu kim kosacak (build/plan/yerel/…). Bugun yalniz opencode'da
// var — digerlerinde ajan secimi diye bir kavram yok, kapi sessiz kalir.
fun RemoteViewModel.loadBackendAgents(id: String) {
    when (id) {
        "opencode2-app" -> loadOpencodeAppAgents()
        "cowork" -> if (isOpencodeFamily(normalizeCoworkProvider(uiState.value.coworkProvider))) loadOpencodeAppAgents()
    }
}

// "Değişiklikler" görünümü: oturumun dokunduğu dosyalar. İki backend'de var —
// opencode-app ve codex-app; köprü ikisini de aynı şemaya çevirdiği için
// telefonda tek bir gövde çiziyor. Başka sağlayıcıda kapı sessiz kalır ve
// menüde satır zaten çizilmez (bkz. backendDiffSupported).
//
// ÜÇ YER AYNI KİMLİK KÜMESİNE BAKMAK ZORUNDA: satırın görünürlüğü
// (`backendDiffSupported`), buradaki yükleme dalı ve kökün okuduğu kutu
// (`backendDiff`). Ayrışırlarsa satır görünür, sheet açılır ve boş kalır — bu
// depoda tekrarlayan hata sınıfı tam olarak bu (delege yazıldı, yönlendirme
// dalı unutuldu; codex'in `loadChanges`ı aylarca öyle durdu). Ui3MenuYonlendirme
// testi üçünü karşılaştırıyor.
fun RemoteViewModel.loadBackendDiff(id: String) {
    when (id) {
        "opencode2-app" -> loadOpencodeAppDiff()
        "codex-app" -> loadCodexAppDiff()
        "cowork" -> when (normalizeCoworkProvider(uiState.value.coworkProvider)) {
            "opencode2-app" -> loadOpencodeAppDiff()
            "codex-app" -> loadCodexAppDiff()
        }
    }
}

// Checkpoint geri sarma: liste / geri sar / geri al. Üç kapı da diff ile AYNI
// dallara sahip olmak zorunda — `backendRevertSupported` cowork+opencode'da da
// true diyor, yani menüde satır çiziliyor; buradaki cowork dalı olmasaydı
// satır açılıyor ama liste hiç gelmiyordu (yukarıdaki not: delege yazıldı,
// yönlendirme dalı unutuldu).
fun RemoteViewModel.loadBackendCheckpoints(id: String) {
    when (id) {
        "opencode2-app" -> loadOpencodeAppCheckpoints()
        "cowork" -> if (isOpencodeFamily(normalizeCoworkProvider(uiState.value.coworkProvider))) loadOpencodeAppCheckpoints()
    }
}

// Alt-ajan transkripti. Kart yığını `backendSubagentsSupported` ile çiziliyor
// ve o cowork+opencode'da da true diyor — cowork dalı olmasaydı kartlar
// görünüp dokunulunca hiçbir şey gelmezdi (yukarıdaki tekrarlayan hata sınıfı).
fun RemoteViewModel.loadBackendSubagentTranscript(id: String, childId: String) {
    when (id) {
        "cowork" -> if (normalizeCoworkProvider(uiState.value.coworkProvider) == "opencode2-app") loadOpencodeAppSubagentTranscript(childId)
    }
}

fun RemoteViewModel.revertBackendToMessage(id: String, messageID: String) {
    when (id) {
        "opencode2-app" -> opencodeAppRevertTo(messageID)
        "cowork" -> if (isOpencodeFamily(normalizeCoworkProvider(uiState.value.coworkProvider))) opencodeAppRevertTo(messageID)
    }
}

fun RemoteViewModel.unrevertBackend(id: String) {
    when (id) {
        // v2'de kopru bunu REDDEDIYOR (commit'li geri sarma geri acilmiyor);
        // dal yine de var ki kullanici sessizlik yerine gerekceyi gorsun.
        "opencode2-app" -> opencodeAppUnrevert()
        "cowork" -> if (isOpencodeFamily(normalizeCoworkProvider(uiState.value.coworkProvider))) opencodeAppUnrevert()
    }
}

// Oturum paylaşımı: yayınla / kaldır. Cowork dalı diff/revert dallarıyla AYNI
// olmak ZORUNDA — `backendShareSupported` cowork+opencode'da true diyor, yani
// menüde satır çiziliyor. Dal eksik olsaydı kullanıcı onay diyaloğunu okuyup
// "Paylaş"a basar, hiçbir şey olmazdı (bu depoda tekrarlayan hata sınıfı:
// delege yazıldı, yönlendirme dalı unutuldu).
fun RemoteViewModel.shareBackendSession(id: String, onLink: (String) -> Unit) {
    when (id) {
        "cowork" -> if (normalizeCoworkProvider(uiState.value.coworkProvider) == "opencode2-app") opencodeAppShare(onLink)
    }
}

fun RemoteViewModel.unshareBackendSession(id: String) {
    when (id) {
        "cowork" -> if (normalizeCoworkProvider(uiState.value.coworkProvider) == "opencode2-app") opencodeAppUnshare()
    }
}

// Özel komutlar: katalog + çalıştırma. Aynı dal kuralı — öneri şeridi
// `backendCommandsSupported` ile çiziliyor ve o cowork+opencode'da true.
fun RemoteViewModel.loadBackendCommands(id: String) {
    when (id) {
        "opencode2-app" -> loadOpencodeAppCommands()
        "cowork" -> if (isOpencodeFamily(normalizeCoworkProvider(uiState.value.coworkProvider))) loadOpencodeAppCommands()
    }
}

fun RemoteViewModel.runBackendCommand(id: String, command: String, arguments: String) {
    when (id) {
        "opencode2-app" -> opencodeAppRunCommand(command, arguments)
        "cowork" -> if (isOpencodeFamily(normalizeCoworkProvider(uiState.value.coworkProvider))) opencodeAppRunCommand(command, arguments)
    }
}

// AGENTS.md init. Aynı dal kuralı (`backendAgentsInitSupported`).
fun RemoteViewModel.initBackendAgentsFile(id: String) {
    when (id) {
        "opencode2-app" -> opencodeAppInitAgents()
        "cowork" -> if (isOpencodeFamily(normalizeCoworkProvider(uiState.value.coworkProvider))) opencodeAppInitAgents()
    }
}

fun RemoteViewModel.setBackendAgent(id: String, agent: String) {
    when (id) {
        "opencode2-app" -> setOpencodeAppAgent(agent)
        "cowork" -> if (isOpencodeFamily(normalizeCoworkProvider(uiState.value.coworkProvider))) setOpencodeAppAgent(agent)
    }
}

fun RemoteViewModel.loadBackendDiskSessions(id: String) {
    when (id) {
        "agy" -> loadAgyDiskSessions()
        "claude-app" -> loadClaudeAppDiskSessions()
        "codex-app" -> loadCodexAppDiskSessions()
        "opencode2-app" -> loadOpencode2AppDiskSessions()
        "omp" -> loadOmpDiskSessions()
        // Cowork'te "aktif repo" = AÇIK OTURUMUN workspace'i. Parametresiz
        // `loadCoworkSessions()` ise Cowork EKRANININ seçimine bakar; oturumu
        // sekmeden ya da birleşik listeden açan kullanıcıda o seçim boştur ve
        // delegate boş yolda listeyi SİLER — çekmece "Kayıtlı oturum yok" derdi,
        // ekranda o an açık olan oturum bile görünmüyordu. Aktif oturum önce.
        //
        // AKTİF YOL `activeCoworkProjectPath`'TEN OKUNUR, `backendSession("cowork").cwd`
        // DEĞİL: o türetilmiş alan aktif cowork yolu boşken ALTTAKİ sağlayıcının
        // (claude/codex/opencode/omp) kendi cwd'sine düşüyor. O bir repo yolu ve
        // Cowork root'unun (~/CoworkSpaces) dışında; köprü haklı olarak 400
        // "projectPath Cowork root disinda" veriyor ve liste "Cowork oturumları
        // yüklenemedi" ile açılıyordu. Cowork'e her girişte activeProjectPath
        // sıfırlandığı için (enterCoworkMode) bu neredeyse her açılışta oluyordu.
        // Aktif cowork oturumu yoksa doğru davranış: TÜM workspace'leri listele.
        "cowork" -> {
            val target = coworkOturumListesiHedefi(uiState.value)
            if (target.isBlank()) loadAllCoworkSessions() else loadCoworkSessions(target)
        }
    }
}

// Yukarıdaki kararın saf hali — testten geçirilebilsin diye ayrı. Boş dönmesi
// "tek workspace yok, hepsini listele" demektir; asla alttaki sağlayıcının
// cwd'sine düşmez.
internal fun coworkOturumListesiHedefi(state: RemoteUiState): String =
    state.activeCoworkProjectPath.ifBlank { state.selectedCoworkWorkspace }
