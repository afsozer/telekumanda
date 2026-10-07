# claude-app / codex-app Yol Haritası — Kalan İşler

> Kapsam: `bridge/claude-app.mjs`, `bridge/codex-app.mjs` (app-server modları) + route
> katmanı ve Android istemcisi.
> Kural: biten maddeler bu dosyadan ÇIKARILIR — geçmiş
> `git log docs/app-server-review-roadmap.md` üzerinden okunur.
> Son doğrulama (2026-07-05, beşinci kontrol): 260/260 bridge testi (Node 22;
> önceki 305 sayımı, 9.95'te silinen tek-atımlık CLI backend'lerinin diskte
> kalmış eski test dosyalarını içeriyordu — 257'ye temizlendi, +3 backend-mcp),
> `:app:compileDebugKotlin` yeşil. gptdegisiklikler.md ve
> docs/deepseek-degisiklikler.md'deki tüm maddeler kodda yerinde doğrulandı.

Tamamlananın tek paragraf özeti (referans için, ayrıntı git log'da):
Faz 1–3 (bug/dayanıklılık/bellek), Faz 4 (reducer'lar adapter'lara, snapshot/delta/
subscribe/throttle/finalize/watchdog/persist çekirdeğe: `createTurnLifecycle`,
`createSessionWatchdog`, `createCollectionWatchdog`, `createShellPersistence`),
Faz 5 (seq, kalıcı rowId, delta ring, `?since` replay, snapshot baseline ring +
sentetik delta fallback, `before/limit` pagination), Faz 6 (BackendCapabilities,
ApprovalActionRouter, SessionStateReducer, SettingsViewModel, ConversationPaging,
mesaj katlama, interruptStuck, planDraft), A1–A4 ikinci kontrol bulguları ve eski
C2/C3 (disk mesaj arşivi: RAM'den düşen mesajlar `bridge-message-archive` jsonl'ine
yazılıp `paginateSessionMessages` ile sayfalanıyor) bitti.

---

## Bölüm B — Saha doğrulama borçları (kod değil, canlı gözlem)

Unit testler mantığı kanıtlıyor; aşağıdakiler ancak gerçek süreçlere karşı görülür.
Her biri için "nasıl doğrulanır" ve "sıkıntı çıkarsa düzeltme talimatı" yazıldı.

### B1. Permissions allow'un ajana fiilen yetki verdiği görülmedi

Durum: `codex app-server generate-json-schema` çıktısında
`PermissionsRequestApprovalParams.permissions` ve response'taki zorunlu
`permissions` alanı doğrulandı. Kod allow'da `rawParams?.permissions || {}`
gönderiyor (`codex-app.mjs`, `approvalResultFor`). Eksik olan tek şey canlı olay.

Nasıl doğrulanır: Codex App'te permission isteyen bir akış tetikle (örn. read-only
sandbox'ta dosya yazdıran bir prompt). Onay kartında "Onayla"ya bas; ajanın işlemi
gerçekten yaptığını ve bridge log'unda approval response'un şemaya uygun gittiğini
gör.

Sıkıntı çıkarsa: allow'a rağmen ajan yetkisiz kalıyorsa alan adı/şekli yanlıştır
(bug sessizce "boş izin ver" olarak geri gelir). Düzeltme: güncel şemayı tekrar üret,
`approvalResultFor` içindeki permissions dalını şemadaki alan adına birebir uydur,
`__testApprovalResultFor` üzerinden `backend-codex-app.test.mjs`e şema-şekilli bir
vaka ekle.

> B2 (kota failover canlı gözlemi) ÇIKARILDI: otomatik failover 10.12'de kaldırıldı,
> Faz 3'te (10.16) "nazik öneri" (`quotaSuggestion`) modeliyle değiştirildi — kota
> hatasında otomatik hesap geçişi artık YOK, kullanıcıya kart gösterilir. Detay için
> `docs/vizyon-roadmap-talimat.md` Faz 3 ve CHANGELOG 10.12/10.16.

### B4. Backend-özel MCP yönetimi: ajanların yeni sunucuyu gerçekten gördüğü doğrulanmadı

Durum: MCP ekranı artık backend'e duyarlı (claude→Claude config, agy→Antigravity,
codex-app→config.toml `codex mcp` CLI'ı ile, opencode-app→opencode.json,
kaldirilmis backend'lerde buton yok).
Config CRUD'ları canlı doğrulandı (codex/opencode gerçek listeler okundu; kaldirilmis backend'de
save/toggle/remove round-trip yapıldı). Görülmeyen tek şey: eklenen sunucunun
ajanın SONRAKİ oturumunda tool olarak gerçekten belirmesi.

Nasıl doğrulanır: Telefondan (örn.) Codex App'e bir MCP ekle → yeni oturum aç →
ajana "hangi mcp araçların var" diye sor; aynısını diğer ACP süreçleri (
gerektirir) ve opencode (serve süreci yeniden başlayınca) için tekrarla.

Sıkıntı çıkarsa: config dosyasında kayıt duruyorsa sorun "ne zaman okunuyor"
tarafındadır — codex: yeni oturum yetmiyorsa app-server süreci yeniden başlamalı;
ACP çocuğu bridge tarafından öldürülüp yeniden doğmalı (bridge'in kendisi
DEĞİL); opencode: serve çocuğu aynı şekilde. Gerekirse ilgili backend'e "MCP
değişikliği sonrası boşta çocuk süreci yeniden başlat" mantığı eklenir.

### B3. `since=1` (ilk snapshot seq'i) reconnect'te saf delta canlı kanıtlanamadı

Durum: Core'da snapshot baseline ring + sentetik delta fallback var; unit testte
ilk snapshot seq'i sonrası delta replay kapalı (`agent-session-core.test.mjs`).
Canlı Codex App smoke testinde ise `since=1` hâlâ full `conversation` fallback
döndü. Veri kaybı yok — bu bir doğruluk değil, protokol verimliliği köşesi.

Nasıl doğrulanır: Canlı bir Codex App oturumunda WS'i ilk full snapshot'tan sonra
kopar, `?since=1` ile bağlan; dönen ilk mesajın `type:"delta"` olduğunu gör
(`type:"conversation"` değil).

Sıkıntı çıkarsa: `agent-session-core.mjs` `subscribe` içindeki replay karar
zincirini (`_snapshotRing` araması → `canReplay` → sentetik delta üretimi) canlı
akışta hangi koşulun full'a düşürdüğünü loglayarak bul; muhtemel fark, canlıda
`_deltaRingTrimmed`/`_deltaReplaySince` durumunun unit kurgusundan farklı
evrilmesi. Düzeltme sonrası aynı canlı senaryoyu tekrar koş. Düşük öncelik.

---

## Bölüm C — Kalan kod borcu

### C1. codex-app persist'inin `createShellPersistence`'a taşınması

Faz 4 birleştirmesinin son parçası. claude-app persist'i core helper'ı kullanıyor
(`claude-app.mjs`, `claudeShellPersistence`); codex-app ise hâlâ kendi el yazması
`persistSessions` + `__restoreSessionsFromData` kopyasını taşıyor
(`codex-app.mjs`, `BRIDGE_SESSIONS_FILE` civarı, ~1834+).

Düzeltme talimatı:

1. `codex-app.mjs`te `createShellPersistence({ file: BRIDGE_SESSIONS_FILE,
   sessions, serializeSession, restoreSession, log })` kur.
   - `serializeSession`: mevcut map gövdesi birebir (`id, threadId, cwd, model,
     permissionMode, effort, lastActivity`); spike filtresi için
     `isSpikeCwd(s.cwd)` olan oturumlarda `null` döndür (core `.filter(Boolean)`
     ile eler).
   - `restoreSession`: mevcut `__restoreSessionsFromData` içindeki shell kurulumu
     birebir taşınır (`policyForMode`, `_epoch: -1`, boş Map/dizi alanları dahil).
     Yeni internal alan eklenirse tek yer burası olur.
2. `persistSessions()` gövdesi `codexShellPersistence.persist()` delegasyonuna
   iner; 9 çağrı noktası değişmez. `__restoreSessionsFromData` export'u
   `restoreFromData` delegasyonu olur — imza ve dönüş (restored sayısı) korunur.
3. `PERSIST_MAX`/`PERSIST_DEBOUNCE_MS`/`PERSIST_DISABLED` sabitleri silinir
   (core default'ları zaten 100/500ms/NODE_TEST_CONTEXT).
4. Test: `backend-codex-app.test.mjs`teki restore vakaları yeşil kalmalı; davranış
   değişikliği sıfır olmalı (salt taşıma).

Not: alias haritasının yalnız claude-app'te olması borç DEĞİL — codex-app'te
oturumu re-key eden bir `/clear` akışı yok; alias mekanizmasına ihtiyaç duyan tek
backend claude-app. Codex'e ileride re-key akışı gelirse alias o zaman core'a alınır.

### C2. Kapsam dışı / opsiyonel notlar

- opencode-app'in aynı çekirdeğe taşınması ayrı iş, bu roadmap'in
  kapsamı dışında.
- `RemoteViewModel` hâlâ büyük; backend-spesifik start/poll metodlarının daha
  fazla mekanik bölünmesi mimari temizlik olarak yapılabilir, roadmap borcu değil.

---

## Sıralama ve efor

| Sıra | İş | Boyut | Not |
|---|---|---|---|
| 1 | C1 codex-app persist birleştirme | S | Salt taşıma; talimat yukarıda |
| 2 | B1 permissions canlı gözlem | XS | İlk gerçek permissions olayında |
| 3 | B4 MCP yönetimi canlı gözlem | XS | İlk MCP ekleyişte yeni oturumda tool kontrolü |
| 4 | B3 since=1 saf-delta köşesi | S | Düşük öncelik; veri kaybı yok |

Genel disiplin: her madde kendi commit'i + test vakası; dışa açık export sözleşmesi
(`registerBackend` imzaları, `__test*` yardımcıları) kırılmaz; satır numaraları
working tree'ye göredir, sembol adıyla ara.
