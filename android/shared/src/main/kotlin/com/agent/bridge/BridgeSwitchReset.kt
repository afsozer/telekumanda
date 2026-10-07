package com.agent.bridge

// Köprü profili değişince ESKİ köprüden çekilmiş ekran verisini boşaltır.
//
// İstekler zaten çağrı anındaki `settings`'i okuyor, yani yanlış köprüye giden
// istek yok. Sorun bellekteki veriydi: yükleme fonksiyonlarının hata yolu eski
// listeyi yerinde bırakıyor, "bir kez çek" korumaları (driveRoots) dolu listeyi
// hiç yenilemiyor. Yeni köprü cevap veremeyince dosya gezgini ve oturum
// geçmişi eski makineninkini göstermeye devam ediyordu (19.09.2026,
// Mac köprüsü seçiliyken Windows köprüsünün verisi). Kural: köprü değişince önce boşalt,
// sonra yükle — yanlış makinenin verisindense boş ekran + hata.
//
// DOKUNULMAYANLAR bilinçli: oturum kimlikleri/mod durumu (goToLanding onları
// okuyup kapatıyor), sekmeler ve kuyruk (zaten bridgeProfileId ile ayrık),
// telefona ait veriler (telefon gezgini, indirme kayıtları, taslaklar ve
// kullanıcı tercihleri).
fun RemoteUiState.clearedForBridgeSwitch(): RemoteUiState = copy(
    files = files.copy(
        browserEntries = emptyList(),
        browserBase = "",
        browserLoading = false,
        driveRoots = emptyList(),
        lastOpenedPath = "",
    ),
    claude = claude.copy(diskSessions = emptyList(), diskLoading = false, info = null),
    codex = codex.copy(diskSessions = emptyList(), diskLoading = false, info = null),
    opencode = opencode.copy(diskSessions = emptyList(), diskLoading = false, info = null),
    omp = omp.copy(diskSessions = emptyList(), diskLoading = false, info = null),
    agy = agy.copy(diskSessions = emptyList(), diskLoading = false),
    cowork = cowork.copy(
        outputs = emptyList(),
        workspaces = emptyList(),
        workspacesLoading = false,
        sessions = emptyList(),
        sessionsLoading = false,
        importBase = "",
        importEntries = emptyList(),
        importLoading = false,
    ),
    backendCatalog = null,
    operations = OperationsResult(),
    operationsLoading = false,
    operationsLoaded = false,
    projectSummaries = emptyList(),
    projectsLoading = false,
    selectedProjectDetail = null,
    projectDetailLoading = false,
    projectMcpServers = emptyList(),
    projectMcpLoading = false,
    mcpServers = emptyList(),
    mcpLoading = false,
    usage = UsageResult(emptyList(), ""),
    usageLoading = false,
    harnessProcesses = emptyList(),
    processCount = 0,
    activeSessionCount = 0,
    backendSessionCounts = emptyMap(),
    backendProcessCounts = emptyMap(),
    activeState = null,
    activeStateLoading = false,
    workerDirs = null,
    folderSearchResults = emptyList(),
    slashCommands = emptyList(),
)
