# UI Envanteri ve Taşı/Birleştir/At Kararları — Faz 0.1

> ui-reboot-plani.md Faz 0.1 çıktısı. Her satır: mevcut yüzey → karar → yeni yeri.
> "ÖNERİ" işaretli kararlar denetçi (Claude) önerisidir; "ÜRÜN KARARI" işaretliler
> kullanıcı onayı bekler. Onaylanan hali ui-anayasasi.md'ye girer.
> Yeni navigasyon modeli referansı: 3 kök — **Sohbet / Merkez / Ayarlar** (plan 0.2).

## 1. Kök ekranlar

| Mevcut | Satır | Karar | Yeni yeri / not |
|---|---|---|---|
| LandingScreen (agent kartları, HubCard, CoworkCard, ToolCard) | 696 | **AT** (ÜRÜN KARARI #1) | Rolleri dağılır: agent seçimi → "Yeni oturum" akışı; hub kısayolları → Merkez kökü. Landing diye ayrı bir kavram kalmaz; uygulama Sohbet köküne açılır. |
| ChatScreen | 1.531 | **YENİDEN DOĞAR** | Sohbet kökü (2b). İçindeki sheet ormanı aşağıda tek tek kararlaştırıldı. |
| SettingsScreen (+2 AlertDialog + UpdateDialog) | 455 | **TAŞI** | Ayarlar kökü; tek uzun sayfa yerine alt sayfalar: Bağlantı/Eşleme, Görünüm, Sağlayıcılar (görünürlük+sıra), Bildirimler, Güncelleme. |

## 2. Landing üstündeki sheet'ler

| Mevcut | Karar | Yeni yeri / not |
|---|---|---|
| Operasyonlar sheet (OperationsUi) | **TAŞI** | Merkez'de tam EKRAN (back-stack'li). Sheet olmaz. |
| Projeler sheet (ProjectsUi) | **TAŞI** | Merkez'de tam EKRAN. Cowork ile ilişkisi → ÜRÜN KARARI #2. |
| Kullanım sheet (UsageCards) | **TAŞI** | Merkez'de tam ekran; Sohbet üst barındaki kopyası hafif özet sheet olarak kalır (tek veri kaynağı, iki sunum). |
| Filo sağlığı sheet (processes / kill-all) | **TAŞI** (ÜRÜN KARARI #3) | Öneri: Ayarlar altında "Gelişmiş" sayfası. Günlük akışta değil, arıza anında lazım. |

## 3. ChatScreen sheet'leri (13 adet `sheet = "..."`)

| Sheet id | İşlev | Karar | Not |
|---|---|---|---|
| `sessions` | oturum arama/listeleme | **BİRLEŞTİR** | SessionsDrawer ile AYNI işin iki yüzeyi. Tek oturum çekmecesi kalır (arama + pin + arşiv + adopt hepsi orada). |
| `agents` | backend değiştirme listesi | **AT** | Sekme sistemi + yeni-oturum akışı karşılıyor. Tur ortasında backend değiştirme diye bir kavram zaten yok. |
| `models` | model seçici (backend başına 5 branch) | **TAŞI** | Tek JENERİK seçici sheet; veri `sessionsByBackend`+capability'den. String-switch ölür. |
| `claudeAppMode` / `zcodeMode` / `codexAppPermMode` / `opencodeAppPermMode` | izin modu seçici ×4 | **BİRLEŞTİR** | Tek jenerik "İzin modu" seçici sheet (permissionModes capability olan her backend). |
| `claudeAppInfo` / `codexAppInfo` / `opencodeAppInfo` | oturum bilgisi ×3 | **BİRLEŞTİR** | Tek jenerik "Oturum bilgisi" sheet'i; satırlar capability'ye göre (model, mod, effort, cwd, MCP, komutlar...). |
| `slash` | komut paleti | **TAŞI** | Composer'da `/` yazınca inline öneri + sheet fallback (anayasa 0.3'te netleşir). |
| `usage` | kullanım limitleri | **TAŞI** | Hafif özet sheet (bkz. bölüm 2). |
| — ChatScreen:1490 AlertDialog | silme/kesme onayı | **TAŞI** | Standart yıkıcı-işlem DIALOG şablonuna. |

## 4. Başlatma ve dialog'lar

| Mevcut | Karar | Not |
|---|---|---|
| StartAgySheet, StartCodexSheet (StartSheets.kt) | **BİRLEŞTİR** | Tek "Yeni oturum" akışı: sağlayıcı seç → klasör seç → model/izin modu → başlat. Backend-özel alanlar capability ile açılır. |
| SimpleSheet / SheetRow / InfoRow / SessionRow | **TAŞI** | Yeni bileşen kütüphanesinin çekirdeğine (Ds genişlemesi, 2a). |
| FolderPickerDialog | **TAŞI** | Jenerik klasör seçici; FileBrowser altyapısını paylaşır. |
| WorkspacePickerDialog, CoworkPcImportDialog | **TAŞI** | Cowork yeni-oturum akışının adımları olur; bağımsız dialog olmaktan çıkar. |
| ChatDialogs.kt:262/280 onay dialog'ları | **TAŞI** | Standart yıkıcı-işlem şablonuna. |

## 5. Sohbet bileşenleri (işlev korunur, görsel yeniden tasarlanır)

| Mevcut | Karar | Not |
|---|---|---|
| SessionsDrawer (716) + ClaudeAppAccountBar | **TAŞI + BİRLEŞTİR** | Tek oturum çekmecesi (bkz. `sessions`). Hesap barı jenerik "hesap seçici" olur (accounts capability). |
| AppTabBar (425) | **TAŞI** | Sekmeler ürünün güçlü yanı; durum rozetleri (çalışıyor/onay/bitti-görülmedi) korunur, görsel dil yenilenir. |
| ApprovalCard, QuestionApprovalCard, UserInputCard, QuotaSuggestionCard | **TAŞI** | İşlev birebir; uygulamanın kalbi olduğu için mockup onayından geçen yeni tasarımla. |
| Composer, ContextChip, ComposerStatusPill, StatusDot, AttachmentChip | **TAŞI** | Yeniden tasarım; ekler/bağlam/durum tek tutarlı composer'da. |
| ChatMessages, ChatBubble, MessageActionRow, TypingDots, AgentSessionLoadingScreen | **TAŞI** | Yeni mesaj görsel dili (mockup'ta netleşir). |
| Markdown.kt (597) | **KORU (motor)** | Render motoru sayılır; görsel stiller tema token'larına bağlanır. |

## 6. Araç ekranları

| Mevcut | Karar | Not |
|---|---|---|
| FileBrowserScreen (504) + DownloadsListScreen (255) | **BİRLEŞTİR** (ÜRÜN KARARI #5) | Öneri: Merkez'de tek "Dosyalar" ekranı, iki sekme: PC (gezgin) / Telefon (indirilenler). |
| FileViewerSheet | **TAŞI** | Dosya önizleme; tam EKRAN olur (uzun dosyada sheet kötü). |
| McpScreen | **BİRLEŞTİR** | Ayarlar > MCP tek yönetim yüzeyi; ProjectsUi içindeki proje-MCP profili aynı bileşenleri kullanır. |

## 7. Motor yüzeyleri (bu reboot'un DIŞINDA — dokunulmaz)

BridgeMonitorService (bildirim long-poll), ApprovalReceiver/ApprovalActionRouter
(bildirim aksiyonları), UpdateManager, DownloadRepo, BridgeClient*,
SessionStreamManager, SessionStateReducer, ConversationPaging, FileUtils,
BackendCatalog, tüm delegatelar. Bildirim METİNLERİ terminoloji sözlüğüne uyar
ama akış değişmez.

## 8. Ürün kararları — 12.07.2026'da KARARA BAĞLANDI

1. **Landing tamamen kalkar.** Uygulama Sohbet köküne açılır; agent kartlarının
   işlevi Yeni-oturum akışına ve Merkez'e dağılır.
2. **Cowork × Proje Merkezi birleşir.** Merkez'de tek "Projeler" listesi; cowork
   çalışma alanları orada bir proje türü olarak görünür (ayrı "Cowork dünyası" kalkar).
3. **Filo sağlığı / processes / kill-all** → Ayarlar > Gelişmiş.
4. **chatgpt-planner ve claude-desktop-cowork KOMPLE KALDIRILIR** — hem bridge
   hem Android tarafından (backend kaldırma emsali, 07.07.2026). Ayrıntı:
   ui-reboot-plani.md "Faz 0.9 — Nuke paketi".
5. **Dosya gezgini + İndirilenler** tek "Dosyalar" ekranında birleşir (PC / Telefon sekmeleri).

## 9. Sayısal özet

- Mevcut: 3 kök ekran + 13 chat sheet + 4 landing sheet + 6 dialog + 2 start
  sheet + çekmece ≈ **29 ayrı yüzey**, 3'ü backend-başına kopyalı aile.
- Hedef: 3 kök + ~8 ekran + ~6 jenerik sheet + 2 dialog şablonu ≈ **19 yüzey**,
  kopyalı aile SIFIR.
