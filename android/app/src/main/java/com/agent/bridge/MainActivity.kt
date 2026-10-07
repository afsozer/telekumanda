package com.agent.bridge

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.agent.bridge.ui3.nav.Ui3Root
import com.agent.bridge.ui3.shell.LiteEslestirmeEkrani

class MainActivity : ComponentActivity() {
    private val viewModel: RemoteViewModel by viewModels()

    // Pencere modunda aktivite arka planda da RESUMED kalabildiğinden ON_START
    // güvenilir "öne geldi" sinyali değil; çoklu-pencerede üst odak değişimi
    // (API 29+) her odaklanışta ateşlenir — healer için ek tetik.
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        // Bildirim susturması için en doğru sinyal bu: bölünmüş ekranda alttaki
        // pencere RESUMED olsa da "üstte" değildir, bildirimi hak eder.
        if (!BuildConfig.IS_LITE) {
            AppForeground.setForeground(isTopResumedActivity)
            if (isTopResumedActivity) WirelessDebugHealer.heal(this)
        }
    }

    // API 29 altında onTopResumedActivityChanged yok; kaba ama yeterli yedek.
    override fun onResume() {
        super.onResume()
        if (!BuildConfig.IS_LITE && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) AppForeground.setForeground(true)
    }

    override fun onPause() {
        super.onPause()
        if (!BuildConfig.IS_LITE && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) AppForeground.setForeground(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Telefon deposu: izin verilmişse klasör iskeletini kur ve eski
        // indirilenleri bir kez taşı. İzin yoksa ikisi de sessizce atlanır ve
        // bayrak yanmaz — kullanıcı izni sonradan verince bir sonraki açılışta
        // kendiliğinden yapılır.
        runCatching {
            PhoneStorage.ensureSkeleton()
            PhoneStorageMigration.migrateIfNeeded(this)
        }
        handleShareIntent(intent)
        if (!BuildConfig.IS_LITE) {
            handleApprovalIntent(intent)
            handleReminderIntent(intent)
        }
        handleViewFileIntent(intent)
        // Arka plandan dönüşte (5 dk Instagram sonrası) akış soketi çoğu kez yarı-açık
        // kalıyor; ON_START'ta soketi anında tazele + konuşmayı bir kez çek ki sohbet
        // ping/backoff süresi (15-20 sn) beklemeden aksın. İlk açılışta no-op.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                viewModel.onAppForeground()
                // Öne her gelişte dene: ağ değişimi uygulama arka plandayken
                // olduysa onCreate/callback kaçmış olabilir.
                if (!BuildConfig.IS_LITE) WirelessDebugHealer.heal(this)
            }
        })
        // Tablette ağ değişince kapanan Kablosuz hata ayıklamayı kendi kendine
        // geri açar (WRITE_SECURE_SETTINGS verilmişse; yoksa sessiz no-op).
        if (!BuildConfig.IS_LITE) WirelessDebugHealer.start(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        setContent {
            run {
                val uiState by viewModel.uiState.collectAsState()
                val alertHostState = remember { AlertHostState() }
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

                LaunchedEffect(Unit) {
                    viewModel.messages.collect { alertHostState.show(it) }
                }
                LaunchedEffect(Unit) {
                    viewModel.alerts.collect { alertHostState.show(it) }
                }
                LaunchedEffect(Unit) {
                    viewModel.checkForUpdate(showNoUpdateMessage = false)
                    if (!BuildConfig.IS_LITE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                // TEK KÖK: ui3 (18.08.2026). Buradaki ön izleme anahtarı
                // (`Ui3Tercih`) ve ui2 köküne düşen dal silindi — ui3 parite
                // taramasını (bkz. docs/ui3-liquid-glass-plani.md) geçtikten
                // sonra iki kabuğu birden ayakta tutmanın karşılığı kalmadı.
                // ui2'nin GÖVDELERİ duruyor ve ui3 onları ödünç kullanmaya
                // devam ediyor; giden şey yalnız ikinci kabuk.
                //
                // GEZİNME ÇUBUĞUNUN ARDINDAKİ BEYAZ ŞERİT (kullanıcı 18.08.2026:
                // "sağının solunun arka planı şeffaf olabilir mi").
                //
                // Renk zaten TRANSPARENT (yukarıdaki enableEdgeToEdge). Beyazı
                // biz çizmiyoruz: `SystemBarStyle.auto` gece kipini otomatik
                // izlediği için androidx `isNavigationBarContrastEnforced`'ı
                // AÇIK bırakıyor ve sistem, açık temada okunabilirlik adına
                // çubuğun altına yarı saydam bir örtü koyuyor. ui3'te bunun
                // gerekçesi yok: dock zaten yüzen bir cam levha, altındaki mesh
                // düşük kontrastlı ve jest çubuğu onun üstünde okunuyor.
                val pencere = window
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        pencere.isNavigationBarContrastEnforced = false
                    }
                }
                // Lite köprü kimliğini APK'da taşımaz: kimlik yoksa önce eşleştirme.
                if (uiState.liteEslestirmeGerekli) {
                    LiteEslestirmeEkrani(uiState, viewModel, alertHostState)
                } else {
                    Ui3Root(uiState, alertHostState, viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
        if (!BuildConfig.IS_LITE) {
            handleApprovalIntent(intent)
            handleReminderIntent(intent)
        }
        handleViewFileIntent(intent)
    }

    /**
     * Dışarıdan gelen dosya açma isteği (dosya yöneticisi, WhatsApp, e-posta).
     * .md kendi metin editörümüze, .udf/.udfx/.docx blok düzenleyiciye gider;
     * gerisi bize hiç gelmez (manifestteki VIEW filtreleri).
     *
     * Telefondaki gerçek yolu çözebilirsek düzenlenebilir açarız; çözemezsek
     * (uygulamaya özel content sağlayıcısı — WhatsApp böyle) çalışma kopyası
     * çıkarıp onu açarız ve KOPYA olduğunu söyleriz. Özgün dosyaya
     * yazamayacağımızı sessizce gizlemek yerine söylemek daha dürüst;
     * kullanıcı kaydettiğini sanıp kaybetmesin.
     *
     * Tür kararı intent'in MIME'ına DEĞİL ada bakar: aynı .udf kaynağa göre
     * application/zip, application/octet-stream ya da application/udf olarak
     * geliyor (telefonda ölçüldü) — MIME'a güvenilmiyor, bkz. FileMime.
     */
    private fun handleViewFileIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        intent.action = Intent.ACTION_MAIN
        lifecycleScope.launch {
            // Önce süzgeç: dışarıdan gelen URI bizim özel dosyamıza işaret
            // edebilir (bkz. GelenUriDenetimi.kt). Sağlayıcının bildirdiği
            // gerçek yol bir kez çözülür; hem süzgeç hem düzenlenebilir açma onu
            // kullanır.
            val (karar, saglayiciYolu) = withContext(Dispatchers.IO) {
                val ilk = disUriKarari(uri)
                if (ilk != GelenUriKarari.KABUL) ilk to null
                else {
                    val yol = saglayiciYolu(uri)
                    (if (yol != null && ozelDizinde(yol)) GelenUriKarari.RED_OZEL_DIZIN else GelenUriKarari.KABUL) to yol
                }
            }
            if (karar != GelenUriKarari.KABUL) {
                viewModel.notifyUser(gelenUriRetMesaji(karar))
                return@launch
            }
            val gorunenAd = resolveDisplayName(this@MainActivity, uri)
            val ad = gorunenAd.ifBlank { uri.lastPathSegment.orEmpty().substringAfterLast('/') }
            val blokEditoru = isBlockEditorFile(ad)
            val ac: (String) -> Unit = { yol ->
                if (blokEditoru) viewModel.openPhoneDocx(yol) else viewModel.openPhoneMarkdown(yol)
                viewModel.requestOpenFileViewer()
            }
            val cozulen = saglayiciYolu?.let { yol ->
                withContext(Dispatchers.IO) { java.io.File(yol).takeIf { it.isFile && it.canWrite() }?.absolutePath }
            }
            if (cozulen != null) {
                ac(cozulen)
                return@launch
            }
            val kopyaAdi = ad.ifBlank { if (blokEditoru) "belge.udf" else "belge.md" }
            val kopya = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = PhoneStorage.agentBridgeRoot()
                    if (!PhoneFiles.ensureDir(dir)) return@runCatching null
                    val hedef = PhoneFiles.uniqueChild(dir, kopyaAdi)
                    contentResolver.openInputStream(uri)?.use { input ->
                        hedef.outputStream().use { input.copyTo(it) }
                    } ?: return@runCatching null
                    hedef.absolutePath
                }.getOrNull()
            }
            if (kopya != null) {
                ac(kopya)
                viewModel.notifyUser("Özgün dosyaya yazılamıyor; AgentBridge klasörüne kopya açıldı")
            } else {
                viewModel.notifyUser("Dosya açılamadı")
            }
        }
    }

    /** Şema ve sağlayıcıya göre ilk karar (dosya sistemine dokunmaz). */
    private fun disUriKarari(uri: Uri): GelenUriKarari =
        gelenUriKarari(uri.scheme, uri.authority, "$packageName.fileprovider")

    /**
     * Sağlayıcının `_data` sütunundaki gerçek yol; yoksa null. Dosya
     * yöneticilerinin çoğu MediaStore ya da kendi sağlayıcısını kullanıyor;
     * ikisinde de bu sütun gerçek yolu taşıyabiliyor (kullanımdan kalkmış ama
     * hâlâ dolu). Kötü niyetli bir sağlayıcı da buraya BİZİM özel dosyamızın
     * yolunu yazabilir — [ozelDizinde] o yüzden her çözülen yola uygulanır.
     */
    private fun saglayiciYolu(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
            if (c.moveToFirst() && c.columnCount > 0) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Yol (sembolik bağlar çözülerek) uygulamanın özel dizinlerinden birinin altında mı? */
    private fun ozelDizinde(yol: String): Boolean {
        val gercek = runCatching { java.io.File(yol).canonicalPath }.getOrNull() ?: return true
        val kokler = listOfNotNull(
            dataDir, filesDir, cacheDir, noBackupFilesDir, codeCacheDir,
            runCatching { createDeviceProtectedStorageContext().dataDir }.getOrNull(),
        ).mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
        return yolOzelDizinAltinda(gercek, kokler)
    }

    // Paylaş menüsünden gelen dosyaları ÖNBELLEĞE yazar ve hedefi ViewModel'e
    // sordurur (sohbet eki mi, çalışma alanı mı). İşlenen intent'in action'ı
    // MAIN'e çekilir ki konfigürasyon değişiminde aynı dosya ikinci kez gelmesin.
    //
    // Baytlar burada belleğe ALINMAZ, akışla dosyaya yazılır: paylaşılan bir
    // duruşma videosu ya da 100 MB'lık tarama uygulamayı düşürmemeli.
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        @Suppress("DEPRECATION")
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            Intent.ACTION_SEND_MULTIPLE ->
                (intent.getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) ?: arrayListOf())
                    .mapNotNull { it as? Uri }
            else -> emptyList()
        }
        // Düz metin / bağlantı paylaşımı: `EXTRA_STREAM` yok, `EXTRA_TEXT` var.
        // Bugüne kadar sessizce düşüyordu — tarayıcıdan link paylaşmak hiçbir
        // şey yapmıyordu. "Not ekle" hedefiyle birlikte bu en sık kullanılacak
        // paylaşım biçimi; metin geçici bir .txt'ye yazılıp aynı yoldan geçer.
        val duzMetin = if (uris.isEmpty() && intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
        } else ""
        if (uris.isEmpty() && duzMetin.isBlank()) return
        val metinBasligi = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().trim()
        intent.action = Intent.ACTION_MAIN
        lifecycleScope.launch {
            var ret: GelenUriKarari? = null
            val shared = withContext(Dispatchers.IO) {
                val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
                if (duzMetin.isNotBlank()) {
                    val target = java.io.File(dir, "${System.nanoTime()}_paylasilan-metin.txt")
                    // Konu başlığı varsa metnin üstüne yazılır: modelin nota
                    // çevirirken bağlamı olsun (paylaşılan haberin başlığı,
                    // e-postanın konusu).
                    val govde = listOfNotNull(metinBasligi.ifBlank { null }, duzMetin)
                        .joinToString("\n\n")
                    return@withContext runCatching {
                        target.writeText(govde)
                        listOf(SharedFile(target.absolutePath, "paylasilan-metin.txt", "text/plain", target.length()))
                    }.getOrDefault(emptyList())
                }
                // Dışarıdan gelen her URI süzülür (bkz. GelenUriDenetimi.kt):
                // reddedilen sessizce atılmaz, kullanıcıya nedeni söylenir.
                val kabul = uris.filter { uri ->
                    val ilk = disUriKarari(uri)
                    val karar = if (ilk != GelenUriKarari.KABUL) ilk
                    else if (saglayiciYolu(uri)?.let(::ozelDizinde) == true) GelenUriKarari.RED_OZEL_DIZIN
                    else GelenUriKarari.KABUL
                    if (karar != GelenUriKarari.KABUL) ret = karar
                    karar == GelenUriKarari.KABUL
                }
                kabul.mapNotNull { uri ->
                    val name = resolveDisplayName(this@MainActivity, uri).ifBlank { "paylasilan" }
                    val mime = contentResolver.getType(uri) ?: ""
                    runCatching {
                        // Ad çakışması: aynı adlı iki ek birbirini ezmesin.
                        val target = java.io.File(dir, "${System.nanoTime()}_$name")
                        contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { input.copyTo(it) }
                        } ?: return@runCatching null
                        SharedFile(target.absolutePath, name, mime, target.length())
                    }.getOrNull()
                }
            }
            ret?.let { viewModel.notifyUser(gelenUriRetMesaji(it)) }
            if (shared.isEmpty()) {
                if (ret == null) viewModel.notifyUser("Paylaşılan dosya okunamadı")
            } else viewModel.offerSharedFiles(shared)
        }
    }

    private fun handleReminderIntent(intent: Intent?) {
        val noteId = intent?.getStringExtra("reminderNoteId").orEmpty()
        if (noteId.isBlank()) return
        // Extra tüketilir: yapılandırma değişiminde (döndürme) aynı not tekrar
        // açılmasın — onay akışıyla aynı desen.
        intent?.removeExtra("reminderNoteId")
        viewModel.openCoworkNoteById(noteId)
    }

    private fun handleApprovalIntent(intent: Intent?) {
        val backend = intent?.getStringExtra("approvalBackend").orEmpty()
        val sessionId = intent?.getStringExtra("approvalSessionId").orEmpty()
        if (backend.isBlank() || sessionId.isBlank()) return
        intent?.removeExtra("approvalBackend")
        intent?.removeExtra("approvalSessionId")
        viewModel.openApprovalSession(backend, sessionId)
    }
}
