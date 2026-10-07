# Vizyon Roadmap — "Daha az sihir, daha çok cam"

> **Okuyucu:** Bu belge, agtest (köprü + Android uzaktan kumanda) üzerinde çalışacak
> SONRAKİ ajan oturumları için yazıldı (Opus, Sonnet, kim gelirse). Fable 5'in
> 2026-07-06 oturumunda (release 10.12) atılan temelin devamıdır. Her faz kendi
> başına uygulanabilir; sırayla gitmek şart değil ama Faz 0 → 1 önerilir.
> Kullanıcıyla Türkçe konuşulur; commit mesajları küçük harf Türkçe'dir.

## 0. Değişmez ilkeler (her fazda geçerli)

1. **Disk tek gerçek kaynaktır.** Oturum gerçeği `<CLAUDE_CONFIG_DIR>/projects/*.jsonl`
   dosyalarındadır; köprü ve telefon bunu *yansıtır*, kendi paralel muhasebesini
   büyütmez. Yeni özellik eklerken "bunu ayrı bir json'da mı tutsam" dürtüsüne
   direnin — önce diskteki gerçeğe bakın.
2. **Havuz ortak (projects junction), configDir/kimlik ayrı — 10.12 ayrımı 10.39'da geri alındı.**
   yapılandırılmış tüm Claude hesaplarının projects klasörleri varsayılan hesap altında birleştirilmiştir.
   Açık oturumlar rebind edilebilir, kimlik geçişi konuşmayı kapatmaz.
3. **Otomatik sihir yerine şeffaf teklif.** Sistem kullanıcı adına sessizce karar
   vermez (kaldırılan kota failover'ı gibi); durumu gösterir, tek dokunuşluk öneri
   sunar, kararı kullanıcıya bırakır.
4. **Cowork'ün sözleşmesi `outputs/` klasörüdür.** Teslimat = workspace kökünde
   `outputs/` altına düşen dosya. Cowork oturum kayıtları `.cowork/providers/...`
   metadata'sındadır; claude-app'in oturum sistemine sızdırmayın.
5. **Geriye uyumluluk:** telefon eski APK ile köprüye bağlanabilir; yeni uçlar
   eklenirken eskiler kırılmaz, parametreler opsiyonel eklenir.

## 0.5 Çalışma kuralları (ihlal etme)

- **Node 22:** köprü `C:\Users\<you>\node22\node.exe` ile koşar/test edilir
  (PATH'teki v25 DEĞİL). Test: `cd bridge && C:\Users\<you>\node22\node.exe --test test/*.test.mjs`
- **Bridge'i restart ETME:** telefonun açık kanalını koparır, işi yarım bırakır.
  Kod değişikliği yaptıysan kullanıcıya "masaüstünden `bridge-restart.bat`" de.
- **Repo local-only:** commit et, asla push etme/deneme.
- **Release süreci:** `cd bridge && node release.mjs <surum> "<notlar>"` — APK'yı
  derler, `update/latest.json` + `update/app-latest.apk` yayınlar; telefon kendini
  günceller. **Sürüm numarası: yayınlamadan HEMEN ÖNCE CHANGELOG.md'nin en üst
  başlığına bak ve BİR ARTIR.** Kendi oturumunda daha önce kullandığın numarayı
  ezberden tekrar kullanma — başka bir ajan arada sürüm yayınlamış olabilir
  (bu hata 3 kez yaşandı: 10.07-10.09, 10.15, 10.16 çarpışmaları).
- **CHANGELOG.md** her anlamlı değişiklikte güncellenir (mevcut format örnektir).
- Ucuz ajana iş devrediliyorsa: izin modu yolo, sed/satır-numaralı patch yasak.

## 1. Mevcut durum (10.12 sonrası mimari özeti — *10.39'da ortak havuz olarak güncellendi*)

> [!NOTE]
> 10.39 sürümüyle oturum havuzları yeniden ortak yapılmıştır (rebind aktif edilmiştir).
- `bridge/claude-app.mjs` — kalıcı çift-yönlü Claude backend'i (stream-json).
  Kritik parçalar: `ensureProc` (hesabın `CLAUDE_CONFIG_DIR`'ı ile spawn),
  `listDiskSessions({account})` (ortak havuz, tek `_diskCache`),
  `adoptSession` (tek havuzda arar, in-memory oturum başka hesaptaysa rebind eder),
  `refreshViewerFromDisk` + `VIEWER_POLL_MS` interval'i (izleyici canlı sync, 2 sn poll),
  `setActiveAccount` (aktif hesabı değiştirir, açık konuşma varsa rebind eder).
- `bridge/claude-app-adapter.mjs` — stream-json event reducer'ı (rebind uyumlu quota önerisi).
- `bridge/routes/backend.mjs` — ortak backend uçları; `/disk-sessions?account=`.
- `bridge/cowork.mjs` — workspace/oturum orkestrasyonu; `.cowork` metadata.
- Android: `RemoteViewModel.kt` (`setClaudeAppAccount`: claude-app'te konuşmayı kapatmadan rebind eder),
  `BridgeClient.kt` (`claudeAppDiskSessions(settings, account)`),
  `SessionStateReducer.kt` (snapshot.account → UI toggle).
- Testler: `bridge/test/*.test.mjs` (277 test, node --test).

## [TAMAM] Faz 0 — Temizlik ve doğrulama

**Neden:** 10.12 geçişinin artıklarını kapatmak.

1. **[TAMAM — 53ab18b] Android ölü "claude" backend'i kaldırıldı.**
2. **[TAMAM — 07.07.2026] İkincil hesap havuzu yedeği silindi** (kullanıcı onayıyla,
   ayrık havuzlar sorunsuz çalıştığı için erken kapatıldı).
3. **[TAMAM — 07.07.2026] Doğrulama turu:** kullanıcı telefonu gün içinde aktif
   kullandı; tek bulgu kozmetikti (landing başlık kırılması, 10.24'te düzeltildi).

**Kabul:** Testler yeşil, telefonda "Claude Code" modu görünmez, madde 2-3 kapanmış. ✓

> Not (sonraki ajana): Tamamlanan fazın gövdesini SİLME — başlığına `[TAMAM — sürüm]`
> yaz, bekleyen maddeleri koru. Belge ilerleme takipçisidir, sadece yapılacaklar
> listesi değil.

## [TAMAM — 10.13] Faz 1 — Poll'dan push'a: fs.watch tabanlı canlı sync

**Neden:** 2 sn poll çalışıyor ama gecikmeli ve oturum başına stat maliyeti var.

**Nasıl:**
1. `claude-app.mjs`'te `VIEWER_POLL_MS` interval'inin yanına (yerine değil!)
   `fs.watch(path.dirname(s._diskFile))` tabanlı izleme ekle: abone gelince watch
   kur (`subscribe`), son abone gidince kapat (subscriber sayısını `sessionCore`
   üzerinden izle). Event gelince mevcut `refreshViewerFromDisk(s)` + `throttledPush(s)`.
2. **Windows tuzakları:** `fs.watch` Windows'ta dosya değil DİZİN izlemede
   güvenilirdir; rename/çift-event gelir → debounce (100-200 ms). Watch objesi
   `error` event'inde sessizce ölmesin, poll fallback'i devrede kalsın (poll'u
   10 sn'ye seyreltip sigorta olarak bırakmak makul).
3. Aynı deseni isterse codex-app/opencode-app izleyicilerine genelle (ayrı faz olabilir).

**Kabul:** Masaüstünde yazılan cevap telefonda <500 ms'de akar; watch ölürse
sistem poll ile çalışmaya devam eder; testlere watch'suz ortamda çalışan birim
test eklenir (watch kurulamazsa poll'a düştüğünü doğrula).

## [TAMAM — 10.14] Faz 2 — Kilit ekranından onay (bildirim + approval)

**Neden:** Onay kanalı (`--permission-prompt-tool stdio` → `pendingApproval` →
`/claude-app/approve`) hazır; eksik olan kullanıcının uygulamayı açmadan onay verebilmesi.

**Nasıl:**
1. Android'de kalıcı bir foreground-service/WS dinleyicisi zaten konuşma akışını
   alıyor; `awaitingApproval` snapshot alanı geldiğinde yüksek öncelikli bildirim
   üret: başlıkta `approval.summary`, iki action: "İzin ver" / "Reddet" →
   `POST /claude-app/approve {sessionId, allow}`.
2. Uygulama kapalıyken de çalışsın istiyorsak: köprüde `GET /notifications/poll`
   benzeri uzun-poll ucu veya mevcut WS'i service'te tutmak yeterli — FCM'e
   BULAŞMA (yerel ağ ürünü, sunucu bağımlılığı ekleme).
3. Cowork onayları aynı kanaldan geldiği için bedavaya gelir; codex-app'in
   `approve` ucuna aynı bildirimi bağla.

**Kabul:** Ekran kilitliyken gelen izin isteği bildirimden onaylanır ve tur devam
eder; reddedince tur düzgün biter; bildirim, onay UI'dan verilirse kendini temizler.

## [TAMAM — 10.16] Faz 3 — Kota bittiğinde "nazik öneri" (failover'ın doğru hali)

**Neden:** Otomatik failover kaldırıldı (doğruydu); ama kota dolunca kullanıcıyı
yalnız bırakmayalım.

**Nasıl:**
1. `claude-app-adapter.mjs`'te `result` event'inde kota kalıbını tespit et
   (eski `isQuotaError` regex'i git geçmişinde: `usage limit reached|rate.?limit|429|...`).
   OTURUMA DOKUNMA; snapshot'a `quotaSuggestion: { otherAccount, label }` alanı koy.
2. Telefonda bu alan görünce kart göster: "Personal kotası doldu. Work ile aynı
   klasörde YENİ oturum açıp son mesajını oraya taşıyayım mı?" Onaylarsa:
   `setActiveAccount(work)` → `newSession({cwd: aynı})` → son user mesajını prompt et.
   *(Not: 10.39'da geçersizleşti — havuzlar birleştirildiğinden artık yeni oturum açılmak yerine mevcudu rebind ediyoruz, transcript taşımaya gerek kalmadı).*
3. Usage kartlarıyla bağla: öneri kartında diğer hesabın kalan kotası gösterilebilir
   (`bridge/usage.mjs` `claudeUsage(account)` zaten hesap-parametreli).

**Kabul:** Kota hatasında tur normal hata ile biter + öneri kartı çıkar; otomatik
hiçbir geçiş olmaz; öneri reddedilirse bir daha aynı turda gösterilmez.

> Uygulama notları (10.16 devir teslimi): (1) Kota kontrolü YALNIZ hatalı result'larda
> koşar (`is_error`/`subtype !== 'success'`) — başarılı metinde "429" geçmesi kart
> çıkarmaz; bu koşulu gevşetme. (2) Bilinen kenar durum: cowork modunda öneri kabulü
> yeni oturumu `claudeAppNew` ile açar, `.cowork` metadata'sına yazmaz — cowork
> çekmecesinde görünmeyebilir. Şikayet gelirse cowork `startSession` üzerinden aç.

## [TAMAM — 10.17] Faz 4 — Hukuk şeridi (uygulamayı ayrıştıran katman)

**Neden:** Genel ajan uygulaması çok; "hukukçunun cebindeki ajan filosu" tek.
Kullanıcının Yargı MCP, UDF araçları ve emsal iş akışları zaten var.

**Nasıl:**
1. Cowork workspace şablonu: `createWorkspace`'e opsiyonel `template: 'dava-dosyasi'`
   parametresi — `belgeler/`, `outputs/`, `notlar.md` iskeleti + workspace CLAUDE.md'sine
   hukuk talimatı (UDF çıktısı outputs/'a, emsal aramada Yargı MCP kullan vb.).
2. Skill paketleme: udf/docx/pdf skill'leri cowork hesabının `<configDir>/skills`
   altına kuruluyor (bkz. `listInstalledSkills`); "dilekçe-taslağı" gibi birleşik
   bir skill yaz (girdi: belgeler/, çıktı: outputs/taslak.udf).
3. Telefonda "Dava dosyası aç" kısayolu: workspace şablonu + varsayılan prompt
   ("belgeleri oku, özet + usul takvimi + taslak çıkar") tek düğme.

**Kabul:** Telefondan tek akışla: klasör aç → belge at → "işle" → outputs/'ta
UDF/DOCX taslak kartları. Cowork'ün genel akışı bozulmaz.

## [TAMAM — 10.21] Faz 5 — Claude oturum listesinde arama/pin/arşiv

**Neden:** codex-app'te var (`listDiskSessionsWithQuery` — arama + arşiv + pin);
claude tarafı listede eşitlensin.

**Nasıl:** `codex-app.mjs`'teki deseni claude-app'e taşı: `?q=` başlık/lastText
filtresi `computeDiskSessions` çıktısı üzerinde; pin/arşiv bilgisi jsonl'e YAZILMAZ
(disk gerçeği kirletme) — köprüde `~/.claude/bridge-claude-app-meta.json` gibi tek
küçük yan dosyada `{id: {pinned, archived}}` tut (ilke 1'in istisnası: bu veri
CLI'da yok, saf UI metadata'sı).
*(Not: 10.39 sürümünde havuzlar birleştirildiğinden iki profil ayrımı veya id -> hesap bağımsızlığı karmaşıklığı kalkmış, tek havuz üzerinde meta dosyası sürdürülmeye başlanmıştır).*

**Kabul:** Drawer'da arama kutusu; pin üstte, arşivli gizli; masaüstü listesi
etkilenmez (yan dosya CLI'a görünmez).

> Uygulama notları (10.22 devir teslimi): (1) 10.22'de eklendi: arama debounce'u
> (300 ms) ve "Arşivlenenleri göster" çipi — çip olmadan arşiv tek yönlü kapıydı.
> (2) Bilinen sınırlar: arama yalnız en yeni 100 oturum içinde çalışır
> (`listDiskSessions` slice'ı aramadan ÖNCE uygulanıyor); `/disk-sessions-search`'ün
> `pinned` parametresi parse edilip kullanılmıyor (ölü); meta yazımı atomik değil
> (çökmede pin/arşiv verisi sıfırlanabilir — jsonl'lere dokunmaz, düşük risk).

## [TAMAM — 10.23] Faz 6 — Filo sağlığı paneli

**Neden:** Backend sayısı arttı (claude/codex/opencode/lokal);
"hangisi ayakta, kim ne yakıyor" tek ekranda değil.

**Nasıl:** Köprüde `GET /fleet/health`: her backend için `listLiveProcesses()`,
son hata, aktif oturum sayısı, usage özeti (varsa). Telefonda ayarlar üstünde tek
kart grid'i. Watchdog (scripts/run-watchdog-hidden.vbs) durumunu da göster.

**Kabul:** Tek ekranda tüm backend'lerin canlılık + kota görünümü; ölü backend
kartından log kuyruğu (`bridge.log` son N satır) açılabilir.

## Uzun vade (yıldız haritası — talimat değil yön)

- **Ajan işletim sistemi katmanları:** oturum (bitti: 10.12) → izin/onay (Faz 2)
  → kota/kimlik (Faz 3) → teslimat (Faz 4). Her katman backend-bağımsız tek arayüz.
- **Sesli async kullanım:** telefondan sesle iş bırakma; bildirimle sonuç.
- **Çok-cihaz:** aynı köprüye ikinci telefon/tablet; abonelik modeli zaten WS-bazlı,
  engel yok.

## Her fazın kapanış ritüeli

1. `C:\Users\<you>\node22\node.exe --test test/*.test.mjs` → hepsi yeşil.
2. Android değiştiyse `node release.mjs <surum> "<notlar>"` (sürümü artır).
3. CHANGELOG.md'ye bölüm ekle; bu belgede fazın başına `[TAMAM — <sürüm>]` yaz.
4. Commit (push yok). Kullanıcıya bridge restart gerekiyorsa söyle — kendin yapma.
