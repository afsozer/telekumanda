package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Dosya gezgini işlemleri — RemoteViewModel'den ayrılan delege (madde 11.3).
//
// Kapsam: loadBrowserDir, uploadToBrowserDir (Uri çözme ViewModel'de, yükleme
// burada), deleteBrowserEntry, renameBrowserEntry, moveBrowserEntry, openBrowserFile.
// setBrowserColumns/loadWorkerDirs ViewModel'de kaldı (UI state +
// prefs). Cowork import/upload ayrı sorumluluk — bu maddenin kapsamı dışında.
//
// Davranış değişikliği yok. Delege Android framework'e bağımlı değil (Uri çözme
// ViewModel'de yapılır, delege yalnızca name+bytes alır) — düz JUnit ile test
// edilebilir. Kalıp McpDelegate ile aynı: BridgeClient + CoroutineScope + state
// okuyucu/updater + mesaj callback'i. openBrowserFile, openFile'ı callback'ten çağırır
// (openFile genel dosya görüntüleme — ViewModel'de kaldı).
class FileBrowserDelegate(
    private val client: BridgeClient,
    private val scope: CoroutineScope,
    private val state: () -> RemoteUiState,
    private val update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    private val emit: suspend (String) -> Unit,
    private val openFile: (String) -> Unit,
    // Gezginin "gizli dosyaları göster" anahtarı. Köprüye sorulurken gerekli:
    // eleme orada yapılırsa istemci hiç göremez.
    private val showHidden: () -> Boolean = { false },
) {
    fun loadBrowserDir(root: String, coworkOnly: Boolean = false) = scope.launch {
        update { it.copy(files = it.files.copy(browserLoading = true)) }
        runCatching {
            client.workerDirs(
                state().settings, root,
                includeFiles = true, coworkOnly = coworkOnly, includeHidden = showHidden(),
            )
        }
            .onSuccess { dirs ->
                update { it.copy(files = it.files.copy(browserEntries = dirs.dirs, browserBase = dirs.base, browserLoading = false)) }
            }
            .onFailure {
                update { it.copy(files = it.files.copy(browserLoading = false)) }
                reportError("Klasör yüklenemedi", it)
            }
    }

    /**
     * Sürücü köklerini bir kez çeker. Sessiz başarısızlık bilinçli: liste bir
     * kolaylık, gelmezse şerit çıkmaz ama gezgin aynen çalışır.
     */
    fun loadDriveRoots() = scope.launch {
        if (state().files.driveRoots.isNotEmpty()) return@launch
        runCatching { client.driveRoots(state().settings) }
            .onSuccess { roots -> update { it.copy(files = it.files.copy(driveRoots = roots)) } }
    }

    // uploadToBrowserDir(uri) ViewModel'de Uri çözülüp (name, bytes) buraya gelir.
    // Ayrık suspend imza: Uri çözme ViewModel'in Android'li işi, delege yalnızca
    // yükleme mantığını yürütür.
    suspend fun uploadFileToBrowserDir(name: String, bytes: ByteArray, coworkOnly: Boolean = false) {
        if (bytes.isEmpty()) { emit("Dosya okunamadı"); return }
        val dir = state().fileBrowserBase
        val result = client.uploadFile(state().settings, dir, name, bytes, coworkOnly)
        if (result.ok) {
            emit("Yüklendi: ${result.name}")
            loadBrowserDir(dir, coworkOnly)
        } else {
            emit("Yükleme başarısız: ${result.error}")
        }
    }

    fun deleteBrowserEntry(path: String, coworkOnly: Boolean = false) = scope.launch {
        val dir = state().fileBrowserBase
        runCatching { client.deleteEntry(state().settings, path, coworkOnly) }
            .onSuccess { ok ->
                if (ok) { emit("Silindi"); loadBrowserDir(dir, coworkOnly) }
                else emit("Silme başarısız")
            }
            .onFailure { reportError("Silme başarısız", it) }
    }
    fun renameBrowserEntry(path: String, newName: String, coworkOnly: Boolean = false) = scope.launch {
        val dir = state().fileBrowserBase
        runCatching { client.renameEntry(state().settings, path, newName, coworkOnly) }
            .onSuccess { r ->
                if (r.ok) { emit("Yeniden adlandırıldı: ${r.name}"); loadBrowserDir(dir, coworkOnly) }
                else emit("Yeniden adlandırma başarısız: ${r.error}")
            }
            .onFailure { reportError("Yeniden adlandırma başarısız", it) }
    }
    fun moveBrowserEntry(path: String, destDir: String, coworkOnly: Boolean = false) = scope.launch {
        val dir = state().fileBrowserBase
        runCatching { client.moveEntry(state().settings, path, destDir, coworkOnly) }
            .onSuccess { r ->
                if (r.ok) { emit("Taşındı: ${r.name}"); loadBrowserDir(dir, coworkOnly) }
                else emit("Taşıma başarısız: ${r.error}")
            }
            .onFailure { reportError("Taşıma başarısız", it) }
    }
    // ---------- toplu işlemler ----------
    // Çoklu seçimde N dosya için tek tek çağırıp SONUNDA bir kez listeyi
    // tazeliyoruz; her dosyada bir yenileme listeyi zıplatır ve N istek eder.

    /**
     * Seçili dosyaları hedefe yapıştırır. [move] true ise taşır (kes-yapıştır).
     * Tek tek yürür ki bir dosya takılınca gerisi yine de geçsin; sonunda kaç
     * tanesinin düştüğünü söyler — sessizce yarım iş bırakmaz.
     */
    fun pasteBrowserEntries(
        paths: List<String>,
        destDir: String,
        move: Boolean,
        coworkOnly: Boolean = false,
    ) = scope.launch {
        var basarili = 0
        for (p in paths) {
            val r = runCatching {
                if (move) client.moveEntry(state().settings, p, destDir, coworkOnly)
                else client.copyEntry(state().settings, p, destDir, coworkOnly)
            }.getOrNull()
            if (r?.ok == true) basarili++
        }
        val dusen = paths.size - basarili
        emit(
            when {
                dusen == 0 && move -> "$basarili öğe taşındı"
                dusen == 0 -> "$basarili öğe kopyalandı"
                else -> "$basarili öğe aktarıldı, $dusen tanesi başarısız"
            },
        )
        loadBrowserDir(state().fileBrowserBase, coworkOnly)
    }

    fun deleteBrowserEntries(paths: List<String>, coworkOnly: Boolean = false) = scope.launch {
        var basarili = 0
        for (p in paths) {
            val ok = runCatching { client.deleteEntry(state().settings, p, coworkOnly) }.getOrDefault(false)
            if (ok) basarili++
        }
        val dusen = paths.size - basarili
        emit(if (dusen == 0) "$basarili öğe silindi" else "$basarili öğe silindi, $dusen tanesi başarısız")
        loadBrowserDir(state().fileBrowserBase, coworkOnly)
    }

    fun pastePhoneEntries(paths: List<String>, destDir: String, move: Boolean) = scope.launch {
        var basarili = 0
        for (p in paths) {
            val ok = if (move) PhoneFiles.move(p, destDir) else PhoneFiles.copy(p, destDir)
            if (ok) basarili++
        }
        val dusen = paths.size - basarili
        emit(
            when {
                dusen == 0 && move -> "$basarili öğe taşındı"
                dusen == 0 -> "$basarili öğe kopyalandı"
                else -> "$basarili öğe aktarıldı, $dusen tanesi başarısız"
            },
        )
        loadPhoneDir(state().phoneBase)
    }

    fun deletePhoneEntries(paths: List<String>) = scope.launch {
        var basarili = 0
        for (p in paths) if (PhoneFiles.delete(p)) basarili++
        val dusen = paths.size - basarili
        emit(if (dusen == 0) "$basarili öğe silindi" else "$basarili öğe silindi, $dusen tanesi başarısız")
        loadPhoneDir(state().phoneBase)
    }

    fun openBrowserFile(path: String) = scope.launch {
        update { it.copy(files = it.files.copy(lastOpenedPath = path)) }
        openFile(path)
    }

    // ---------- telefon tarafı ----------
    // Aynı gezgin UI'ı, farklı kaynak. Köprüye hiç gitmez; PhoneFiles saf JVM
    // olduğu için burada durabiliyor. Kök çözümü (/sdcard/AgentBridge) ve izin
    // Android'e bağlı, onlar çağıran tarafta (PhoneStorage).

    fun loadPhoneDir(path: String) = scope.launch {
        update { it.copy(files = it.files.copy(phoneLoading = true)) }
        val out = PhoneFiles.listDir(path)
        update {
            it.copy(
                files = it.files.copy(
                    phoneEntries = out.dirs,
                    phoneBase = out.base,
                    phoneLoading = false,
                    // ok=false hem "yok" hem "okunamadı" demek; ikisini de aynı
                    // mesajla geçiştirmiyoruz, izin ayrı sorulur.
                    phoneError = if (out.ok) "" else "Klasör okunamadı",
                ),
            )
        }
    }

    fun deletePhoneEntry(path: String) = scope.launch {
        val dir = state().phoneBase
        if (PhoneFiles.delete(path)) emit("Silindi") else emit("Silme başarısız")
        loadPhoneDir(dir)
    }

    fun renamePhoneEntry(path: String, newName: String) = scope.launch {
        val dir = state().phoneBase
        if (PhoneFiles.rename(path, newName)) {
            emit("Yeniden adlandırıldı: ${PhoneFiles.sanitizeName(newName)}")
        } else {
            emit("Yeniden adlandırma başarısız (ad kullanımda olabilir)")
        }
        loadPhoneDir(dir)
    }

    fun movePhoneEntry(path: String, destDir: String) = scope.launch {
        val dir = state().phoneBase
        if (PhoneFiles.move(path, destDir)) emit("Taşındı") else emit("Taşıma başarısız")
        loadPhoneDir(dir)
    }

    /**
     * Telefondaki dosyayı PC'ye kopyalar. Telefon tarafında "indir" anlamsız
     * olduğu için gezginde bunun yerini alır.
     */
    fun copyPhoneEntryToPc(path: String, bytes: ByteArray, destDir: String, coworkOnly: Boolean = false) = scope.launch {
        val name = PhoneFiles.sanitizeName(path)
        if (bytes.isEmpty()) { emit("Dosya okunamadı"); return@launch }
        if (destDir.isBlank()) { emit("Hedef klasör belli değil"); return@launch }
        runCatching { client.uploadFile(state().settings, destDir, name, bytes, coworkOnly) }
            .onSuccess { r -> if (r.ok) emit("PC'ye kopyalandı: ${r.name}") else emit("Kopyalama başarısız: ${r.error}") }
            .onFailure { reportError("Kopyalama başarısız", it) }
    }

    private suspend fun reportError(prefix: String, throwable: Throwable) {
        emit("$prefix: ${throwable.message ?: "unknown error"}")
    }
}
