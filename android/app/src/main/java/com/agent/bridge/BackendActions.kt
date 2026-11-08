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
        "claude-app" -> exitClaudeAppMode()
        "codex-app" -> exitCodexAppMode()
        "omp" -> exitOmpMode()
        "agy" -> exitAgyMode()
        "cowork" -> exitClaudeAppMode()
    }
}

fun RemoteViewModel.setBackendModel(id: String, model: String) {
    when (id) {
        "claude-app" -> setClaudeAppModel(model)
        "codex-app" -> setCodexAppModel(model)
        "omp" -> setOmpModel(model)
        "agy" -> setAgyModel(model)
        "cowork" -> setCoworkModel(model)
    }
}

fun RemoteViewModel.setBackendPermissionMode(id: String, mode: String) {
    when (id) {
        "claude-app" -> setClaudeAppPermissionMode(mode)
        "codex-app" -> codexAppSetPermissionMode(mode)
        "omp" -> setOmpPermissionMode(mode)
        "cowork" -> setCoworkYoloMode(mode == "yolo")
    }
}

fun RemoteViewModel.loadBackendPermissionModes(id: String) {
    when (id) {
        "codex-app" -> loadCodexAppPermissionModes()
        "omp" -> loadOmpPermissionModes()
        "cowork" -> when (normalizeCoworkProvider(uiState.value.coworkProvider)) {
            "codex-app" -> loadCodexAppPermissionModes()
        }
    }
}

fun RemoteViewModel.setBackendEffort(id: String, effort: String) {
    when (id) {
        "claude-app" -> setClaudeAppEffort(effort)
        "codex-app" -> codexAppSetEffort(effort)
        "omp" -> setOmpEffort(effort)
        "cowork" -> when (normalizeCoworkProvider(uiState.value.coworkProvider)) {
            "claude-app" -> setClaudeAppEffort(effort)
            "codex-app" -> codexAppSetEffort(effort)
        }
    }
}

fun RemoteViewModel.loadBackendEfforts(id: String) {
    when (id) {
        "claude-app" -> loadClaudeAppEfforts()
        "codex-app" -> loadCodexAppEfforts()
        "omp" -> { loadOmpInfo(); loadOmpModels() }
        "cowork" -> when (normalizeCoworkProvider(uiState.value.coworkProvider)) {
            "claude-app" -> loadClaudeAppEfforts()
            "codex-app" -> loadCodexAppEfforts()
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
        "omp" -> loadOmpInfo()
        "cowork" -> when (normalizeCoworkProvider(uiState.value.coworkProvider)) {
            "claude-app" -> loadClaudeAppInfo()
            "codex-app" -> loadCodexAppInfo()
        }
    }
}

// "Değişiklikler" görünümü: oturumun dokunduğu dosyalar. Bugün yalnız
// codex-app'te var. Başka sağlayıcıda kapı sessiz kalır ve menüde satır zaten
// çizilmez (bkz. backendDiffSupported).
//
// ÜÇ YER AYNI KİMLİK KÜMESİNE BAKMAK ZORUNDA: satırın görünürlüğü
// (`backendDiffSupported`), buradaki yükleme dalı ve kökün okuduğu kutu
// (`backendDiff`). Ayrışırlarsa satır görünür, sheet açılır ve boş kalır — bu
// depoda tekrarlayan hata sınıfı tam olarak bu (delege yazıldı, yönlendirme
// dalı unutuldu; codex'in `loadChanges`ı aylarca öyle durdu). Ui3MenuYonlendirme
// testi üçünü karşılaştırıyor.
fun RemoteViewModel.loadBackendDiff(id: String) {
    when (id) {
        "codex-app" -> loadCodexAppDiff()
        "cowork" -> when (normalizeCoworkProvider(uiState.value.coworkProvider)) {
            "codex-app" -> loadCodexAppDiff()
        }
    }
}

fun RemoteViewModel.loadBackendDiskSessions(id: String) {
    when (id) {
        "claude-app" -> loadClaudeAppDiskSessions()
        "codex-app" -> loadCodexAppDiskSessions()
        "omp" -> loadOmpDiskSessions()
        "agy" -> loadAgyDiskSessions()
        // Cowork'te "aktif repo" = AÇIK OTURUMUN workspace'i. Parametresiz
        // `loadCoworkSessions()` ise Cowork EKRANININ seçimine bakar; oturumu
        // sekmeden ya da birleşik listeden açan kullanıcıda o seçim boştur ve
        // delegate boş yolda listeyi SİLER — çekmece "Kayıtlı oturum yok" derdi,
        // ekranda o an açık olan oturum bile görünmüyordu. Aktif oturum önce.
        //
        // AKTİF YOL `activeCoworkProjectPath`'TEN OKUNUR, `backendSession("cowork").cwd`
        // DEĞİL: o türetilmiş alan aktif cowork yolu boşken ALTTAKİ sağlayıcının
        // (claude/codex/omp) kendi cwd'sine düşüyor. O bir repo yolu ve
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
