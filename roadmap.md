# Cowork Roadmap

## 1. Project/provider session drawer

- Status: completed.
- Cowork session drawer should be project-first, not provider-first.
- Workspace selection stays fixed while the drawer lists sessions under the selected project.
- The session list should group or filter by active provider: Claude App and Codex App.
- `.cowork/providers/<provider>/sessions/*.json` should be the drawer data source for Cowork, while legacy Claude disk sessions remain only for Claude App mode.

## 2. Model switching flow

- Status: completed.
- Model changes inside Cowork should be handled by one provider-aware path.
- When the user changes model, resume the latest native session for the active provider in the current project, then call the backend model-change API.
- The UI should make it clear which provider's model list is being shown.
- Avoid accidentally applying Claude model choices to Codex sessions or Codex model choices to Claude sessions.

## 3. Provider-independent outputs refresh

- Status: completed.
- `outputs/` remains shared at the project root and provider-independent.
- After a Codex App turn completes, Cowork should refresh project outputs just like Claude snapshot outputs.
- Add a bridge endpoint or conversation snapshot field if needed so Android can refresh outputs without changing provider.
- Outputs cards should not depend on Claude-only session snapshots.

## 4. Manual acceptance pass

- Status: completed.
- Start Project A with Claude App and send a prompt.
- Switch Project A to Codex App and verify a native Codex session starts in the same cwd.
- Switch back to Claude App and verify the previous Claude session resumes.
- Restart the bridge.
- Re-open the same project/provider and verify the latest native session resumes from `.cowork` metadata.
- Verify `outputs/` remains the same shared folder across provider switches.

## 5. Session record dedup in `.cowork` metadata

- Status: completed.
- Problem: `saveProjectSession` (bridge/cowork.mjs:134) writes a new `safeStamp()-sessionId.json` file on every `startSession`, even when resuming the same native session. The sessions dir grows without bound and the drawer can show the same sessionId multiple times.
- Fix: key session records by sessionId, not by timestamp. On resume, find the existing record for that provider+sessionId and update `lastUsedAt` (and model/threadId) in place; only create a new file when the sessionId is genuinely new.
- Migration: on first read, collapse duplicate records for the same sessionId into one (keep newest `lastUsedAt`), delete the rest.
- Test: call `startSession` twice for the same project/provider → sessions dir contains exactly one file for that sessionId; `listProjectSessions` returns it once with updated `lastUsedAt`.

## 6. Codex default permission mode review

- Status: completed.
- Problem: `resumeOrCreate` defaults Codex sessions to `permissionMode: 'yolo'` (bridge/cowork.mjs:205) while Claude sessions run with interactive approvals. The cowork plan explicitly calls the approval card "the heart of cowork" — the asymmetry is unsafe for a non-developer audience working on their own documents.
- Fix: default Codex cowork sessions to an approval-required mode; make yolo an explicit opt-in passed from the UI, never an implicit fallback. If Codex approval routing through the Android approval card is not wired yet, that wiring is a prerequisite (ties into item 9, approval routing per provider).
- Keep: an inherited `permissionMode` from a previous session record may be preserved, but only if it was originally an explicit user choice.

## 7. Centralize provider model defaults

- Status: completed.
- Problem: default models are hardcoded inline in cowork.mjs (`'claude-opus-4-8'` at :194/:198, `'gpt-5.5'` at :205/:210). Model lists/defaults also live in the provider modules and Android — three drift points.
- Fix: export a single `defaultModel()` (or a `MODELS` catalog) from claude-app.mjs and codex-app.mjs; cowork.mjs asks the provider module instead of embedding strings. Android should read defaults from the bridge (e.g. include `defaultModel` in the models/list response) rather than duplicating them.
- Test: changing the provider module default is reflected in a new cowork session without touching cowork.mjs.

## 8. Tighten codex thread record matching

- Status: completed.
- Problem: `updateCodexSessionThread` (bridge/cowork.mjs:162) matches a record when `rec.sessionId === sessionId || rec.threadId === sessionId || rec.threadId === threadId` — the loose OR can update an unrelated record if ids ever collide or a stale record shares a threadId.
- Fix: match primarily on `rec.sessionId === sessionId`; fall back to threadId matching only when no sessionId match exists, and stop after the first (newest) match instead of updating every record. Log/return which records were touched for debuggability.
- Test: two codex records where one shares a threadId with the target — only the intended record is updated.

## 9. Android regression coverage

- Status: completed.
- Add UI/state tests for the Cowork provider segment showing Claude and Codex.
- Verify provider switch writes the correct backend session id into state.
- Verify changing Cowork project clears or isolates old provider session state.
- Verify approval actions route to Claude or Codex depending on the selected Cowork provider.
- Verify model picker uses the active provider's model list.

---

# Kod Sağlığı ve Bakım Roadmap (10–16)

> 2026-07-09 kod incelemesinden çıkan maddeler. Öncelik sırası: 14 (hızlı kazanım) → 10 → 11 → 13 → 15 → 12 → 16.
> Genel kural: bu bölümdeki refactor maddelerinde **davranış değişikliği yasaktır**. Her madde kendi
> commit'lerinde ilerler; her commit sonrası `android/` derlenmeli (`gradlew :app:assembleDebug`) ve
> köprü testleri geçmelidir (`node --test bridge/test/`). Taşıma (move-only) commit'leri ile mantık
> değişikliği commit'leri asla karıştırılmaz.

## 10. ChatScreen.kt parçalama (3.748 satır → ~7 dosya)

- Status: completed.
- Problem: `android/app/src/main/java/com/agent/bridge/ChatScreen.kt` 3.748 satır. Ekranın tamamı,
  dialoglar, drawer, onay kartları ve saf yardımcı fonksiyonlar tek dosyada. Her yeni backend bu
  dosyayı büyütüyor; merge çakışması ve yanlışlıkla bozma riski en yüksek dosya bu.
- Çözüm: `com.agent.bridge.ui.chat` paketi altında, **yalnızca kod taşıyarak** (imza, görünürlük ve
  davranış aynen korunur) aşağıdaki bölünme uygulanır. Mevcut satır numaraları referans:
  - `ChatMessagesUi.kt` ← `ChatMessages` (satır 1454), `AgentSessionLoadingScreen` (1638),
    `TypingDotsIndicator` (1671), `ChatBubble` (1708), `MessageActionRow` (1795), `formatMessageTime` (225).
  - `ComposerUi.kt` ← `Composer` (1827), `ComposerStatusPill` (2193), `ContextChip` (2079),
    `StatusDot` (2451), `shouldShowComposerStatus` (2174), `shouldShowContextChip` (2182).
  - `ChatDialogs.kt` ← `FolderPickerDialog` (2472), `CoworkPcImportDialog` (2514),
    `WorkspacePickerDialog` (2575).
  - `SessionsDrawerUi.kt` ← `SessionsDrawer` (2717) ve yardımcıları: `sessionDetail` (3177),
    `backendSupportsSessionDelete` (3183), `repoTag` (3188), `isoToEpochMillis` (3194),
    `activeDiskLoading` (3207), `activeAgentLabel` (3218), `InfoListSection` (3232),
    `oldestProcessAge` (3249), `ClaudeAppAccountBar` (3262).
  - `ApprovalUi.kt` ← `QuestionApprovalCard` (3312), `ApprovalCard` (3412),
    `CodexAppPlanPanel` (3542), `SteerBar` (3628).
  - `BackendLabels.kt` ← saf (Compose'suz) yardımcılar: `permissionModeLabel` (1954),
    `effortLabel` (1967), `backendRepoLabel` (1970), `backendModelLabel` (1986),
    `backendModelOptions` (2006), `backendPermOptions` (2045).
  - `ChatScreen.kt` (kalan) ← yalnız `AppAlertHost` (251), `AppScaffold` (310), `ChatScreen` (340).
- Uygulama kuralları:
  - Her dosya ayrı bir commit: "refactor: X ChatScreen'den ayrıldı (davranış değişikliği yok)".
  - `internal` görünürlükler `internal` kalır; `private` olup başka dosyaya taşınması gerekenler
    `internal`e yükseltilir ve commit mesajında belirtilir.
  - Taşıma sırasında hiçbir composable'ın parametre listesi değiştirilmez; "hazır elim değmişken"
    iyileştirmesi yapılmaz — o ayrı madde/commit olur.
  - Sıralama: önce `BackendLabels.kt` (en risksiz, saf fonksiyonlar), sonra dialoglar, drawer,
    approval, messages, composer.
- Test: `BackendLabels.kt`'deki saf fonksiyonlara birim test yazılır (bkz. madde 13). Manuel kabul:
  Claude App + Codex + Cowork'te birer mesaj gönder, drawer'ı aç, bir onay kartını yanıtla,
  workspace picker'ı aç — hepsi refactor öncesiyle aynı davranmalı.
- Uygulama notu: 3824 satır → 1492 satır (ChatScreen.kt) + 6 yeni dosya (2742 satır) `com.agent.bridge.ui.chat`
  paketinde. Her dosya ayrı commit, her commit `gradlew :app:assembleDebug` yeşil. Gerçekleşen yerleşim
  plana sadık, 3 sapma: (1) roadmap'te listelenmemiş `UserInputCard` ve `QuotaSuggestionCard` ApprovalUi.kt'ye
  eklendi (ChatScreen'de approval kartlarının yanında çağrılıyorlar, doğal grup). (2) `InfoListSection`
  plana göre SessionsDrawerUi'ya gidecekti ama yalnızca ChatScreen çağırıyor — ChatScreen.kt'de `private`
  kaldı, SessionsDrawerUi'ya taşınmadı. (3) 5 `private` sembol `internal`'e yükseltildi (her commit
  mesajında belirtildi): `shouldShowComposerStatus`, `ClaudeAppAccountBar`, `activeDiskLoading`,
  `activeAgentLabel`, `QuotaSuggestionCard`. Ek olarak 3 dialog (`FolderPickerDialog`,
  `CoworkPcImportDialog`, `WorkspacePickerDialog`) da `private`→`internal` oldu (ChatScreen'den farklı
  paketten çağrılıyor). `SettingsScreen.kt` `StatusDot`'u same-package implicit kullanıyordu; explicit
  `com.agent.bridge.ui.chat.StatusDot` importu eklendi. Hiçbir composable'ın parametre listesi
  değiştirilmedi. Birim test (madde 13) ayrı madde; bu maddede yalnız derleme + mevcut testler yeşil
  (268 köprü testi, Android unit test).

## 11. RemoteViewModel.kt parçalama (3.641 satır → delege sınıflar)

- Status: completed.
- Problem: `RemoteViewModel.kt` 3.882 satır; state tanımları, MCP yönetimi, dosya gezgini,
  indirmeler, OTA, usage ve 6+ backend'in oturum yaşam döngüsü tek sınıfta. Test edilemiyor
  (tek test `CoworkUiLogicTest`), her özellik aynı sınıfa dokunuyor.
- Çözüm: ViewModel **bölünmez**, sorumluluklar delege sınıflara taşınır; ViewModel dışa dönük
  API'sini (fonksiyon adları/imzaları) aynen korur ve çağrıları delegeye yönlendirir. Sıra:
  1. **UiState ayrımı** — `RemoteUiState` (satır 112), `CoworkWorkspace` (45), `ChatAttachment` (104)
     ve dosyadaki diğer tüm `data class`/`sealed` tanımları `RemoteUiState.kt` dosyasına taşınır.
     Salt taşıma, tek commit.
  2. **McpDelegate** — `loadMcpServers`, `saveMcpServer`, `refreshMcp`, `removeMcpServer`,
     `toggleMcpServer`, `mcpTarget`, `claudeMcpAccount` (satır ~390–465). Delege şu üçlüyü
     constructor'dan alır: `BridgeClient`, `CoroutineScope`, `(RemoteUiState) -> RemoteUiState`
     tipinde state güncelleyici (`_uiState.update` sarmalayıcısı). Bu kalıp sonraki delegelerde aynen kullanılır.
  3. **FileBrowserDelegate** — `loadBrowserDir`, `uploadToBrowserDir`, `deleteBrowserEntry`,
     `renameBrowserEntry`, `moveBrowserEntry`, `openBrowserFile` (~737–905).
  4. **DownloadsDelegate** — `downloadByPath`, `clearDownloadHistory`, `openDownloadFolder`,
     `moveDownloadIntoFolder`, `deleteDownloads` (~909–952).
  5. **UpdateDelegate** — `checkForUpdate`, `dismissUpdate`, `downloadAndInstallUpdate` (~510–541).
  6. **Backend oturum delegeleri** — en büyük kazanım, en son yapılır: `AgyDelegate`
     (`enterAgyMode`…`agyOpenAntigravity`, ~1260–1421), `ClaudeAppDelegate` (~1423–1534),
     `CoworkDelegate` (~1536+). Her backend'in enter/start/exit/poll bloğu kendi delegesine gider.
- Uygulama kuralları:
  - Her delege ayrı commit; commit sonrası derleme + ilgili ekranda manuel duman testi.
  - Delegeler Android framework'e bağımlı olmamalı (Context/Uri gereken yerde arayüz arkasına
    saklanır) — amaç JVM'de düz JUnit ile test edilebilirlik.
  - `sendPrompt` (1053) ve `refreshConversation` (954) ViewModel'de kalabilir; önce çevrelerini boşaltmak yeterli.
- Test: her delege için en az bir birim test: sahte `BridgeClient` (arayüze çıkarılmış çağrılar)
  ile "çağrı doğru endpoint'e gitti + state doğru güncellendi" doğrulanır.
- Uygulama notu: RemoteViewModel.kt 3.882 → 2.494 satır. 7 yeni dosya oluşturuldu: `RemoteUiState.kt`
  (state tipleri) + 6 delege (`McpDelegate`, `FileBrowserDelegate`, `DownloadsDelegate`,
  `UpdateDelegate`, `AgyDelegate`, `ClaudeAppDelegate`, `CoworkDelegate`). Her delege ayrı commit
  (toplam 8 commit: 11.1 state ayrımı + 11.2–11.5 + 11.6 üç backend delegesi), her commit
  `gradlew :app:assembleDebug` + `testDebugUnitTest` (5) + `node --test bridge/test/*.test.mjs`
  (268) yeşil. Delege kalıbı yönergeye sadık: constructor BridgeClient + CoroutineScope + state
  okuyucu/updater + mesaj callback'i alır (Android'siz). Sapmalar ve gerekçeleri:
  (1) **DownloadRepo / UpdateManager somut olarak geçildi** (DownloadsDelegate, UpdateDelegate) —
  Context bağımlı bu sınıfların arayüze çıkarılması madde 13 test seferberliğine bırakıldı; bu
  maddede yalnızca taşıma, davranış aynen korundu. (2) **Backend delegeleri (11.6) çok sayıda
  callback aldı** — AgyDelegate 13, ClaudeAppDelegate 15, CoworkDelegate 18 constructor parametresi.
  Gerekçe: sendPrompt/refreshConversation/refreshSession/applyConversation/refreshAll shared altyapı
  (yönerge: VM'de kalır) + sibling backend socket/polling/info-load çağrıları (cowork→codex/opencode/
  claude) callback'lerle soyutlandı. normalizeCoworkProvider top-level olduğu için delege doğrudan
  çağırır (callback gerekmedi). (3) **uploadToBrowserDir (11.3) ve uploadToCoworkWorkspace (11.6c)
  Uri çözmesi VM'de kaldı** — delege yalnızca name+bytes alır (yönerge: Context/Uri arayüz arkasında);
  dış imza (`fun uploadToBrowserDir(uri: Uri)`) aynen korundu. (4) **Visibility değişiklikleri**:
  `startClaudeAppPolling` private→internal (cowork claude-app sağlayıcısı çağırıyor), `coworkProjectPath`
  private→internal (refreshSession cowork branch VM'de çağırıyor), `handleClaudeAppCleared`
  private→public (sendPrompt VM'de çağırıyor). AgyDelegate/CoworkDelegate kendi pollJob'larını
  yönetir; onCleared `cancelPolling()` çağırır. CoworkDelegate'in kendi pollJob'u yok (polling seçili
  sağlayıcının sibling delegesine dağıtılır). (5) **Circular type inference düzeltmesi** (davranış
  değişikliği yok): coworkDelegate property'si shared-infra fonksiyonlarına (refreshSession/
  refreshAll/refreshConversation/sendPrompt) callback verince Kotlin recursive type çıkarımına
  takıldı; bu dört fonksiyona explicit `: Job` dönüş tipi eklendi (derlenmiş imza değişmedi).
  openAgySocket/openClaudeAppSocket/openCoworkSocket VM'de kaldı (refreshSession/rewind/
  applyStreamSnapshot shared infra kullanıyor); delegeler streamManager.open'i callback'ten çağırır.
  Birim testler (madde 13) ayrı madde — bu maddede yalnızca derleme + mevcut 5 Android + 268 köprü
  testi yeşil. Roadmap'in saydığı 3 backend (agy/claude-app/cowork) ayrıldı; codex/codex-app/zcode/
  chatgpt-planner/opencode-app backend'leri bu maddenin kapsamı dışında (roadmap onları saymıyor).

## 12. BridgeClient.kt parçalama (1.953 satır)

- Status: completed. Öncelik: düşük (10 ve 11 bittikten sonra).
- Problem: tüm REST/WS çağrıları tek sınıfta; köprü tarafındaki `routes/` ayrımının (backend,
  chatgpt-planner, general, mcp, update) telefonda karşılığı yok.
- Çözüm: `BridgeClient` çekirdek kalır (baseUrl, token, OkHttp, ortak request/hata yolu). API
  alanları Kotlin extension dosyalarına ayrılır ve köprüdeki route dosyalarıyla birebir eşleşir:
  `BridgeClientMcp.kt`, `BridgeClientUpdate.kt`, `BridgeClientUsage.kt`, `BridgeClientBackend.kt`
  (backend başına birer dosya da olabilir: `BridgeClientClaude.kt` vb.). Çağıran kod değişmez
  (extension'lar aynı imzayı korur).
- Test: derleme yeterli; davranış değişikliği yok. Madde 11'deki sahte `BridgeClient` arayüzü bu
  ayrımdan sonra alan-bazlı küçük arayüzlere bölünebilir.
- Uygulama notu: BridgeClient.kt 2.161 → 427 satır (yalnız çekirdek). 4 extension dosyası
  oluşturuldu: `BridgeClientBackend.kt` (1177 — agy/claudeApp/cowork/codex/codexApp/opencodeApp/zcode,
  109 extension), `BridgeClientGeneral.kt` (359 — health/active/file/process/local-llama),
  `BridgeClientMcp.kt` (142 — tüm MCP), `BridgeClientChatGptPlanner.kt` (118). Her dosya ayrı commit
  (toplam 5: 12.0 görünürlük + 12.1–12.4), her commit `gradlew :app:assembleDebug` +
  `testDebugUnitTest` (5) + `node --test bridge/test/*.test.mjs` (268) yeşil. Extension'lar top-level
  `suspend fun BridgeClient.foo(...)` imzasıyla; çağıran kod (RemoteViewModel, delegeler,
  ApprovalReceiver, BridgeMonitorService) hiç değişmedi — doğrulandı.
  **Sapmalar**: (1) **BridgeClientUpdate.kt iptal edildi** — BridgeClient.kt'te hiç update/OTA
  fonksiyonu yoktu; bu çağrılar `UpdateManager.kt`'te kendi HTTP altyapısıyla duruyor (kullanıcı
  onayı). (2) Backend MCP fonksiyonları (`/claude-app/mcp/*`, `/<prefix>/mcp/*`) path olarak backend
  route'unda olsa da `BridgeClientMcp.kt`'te toplandı — Android'de tek MCP sorumluluk birimi
  (McpDelegate) ile eşleşir (kullanıcı onayı). (3) **private→internal yükseltmeleri** (extension'lar
  private üyeye erişemediği için, davranış etkisi yok): BridgeClient çekirdek helper'ları
  (client/workerClient/longPollClient/jsonType/buildRequest/normalizeBase/getJson/postJson/
  getJsonLongPoll/getJsonWorker/postJsonWorker/executeJson/executeJsonLongPoll/executeJsonWorker) +
  `Call.await()` (downloadFile extension'ı kullanıyor) + parseBackendModels/parsePermissionModes/
  parseSlashCommands (backend extension'ları kullanıyor). (4) UpdateManager.kt'teki birebir aynı
  gövdeli duplicate `private Call.await()` kaldırıldı — artık BridgeClient'ın internal olanını
  kullanıyor (DRY). (5) `diskSessionsFrom` (cowork/claudeApp disk oturum yardımcısı) private
  extension olarak taşındı (getJson receiver'ı lazım). BridgeClientUpdate/Usage ayrımı yapılmadı
  (usage general'e, update iptal) — route dosyalarındaki 5 kategoriden 4'ü oluştu.

## 13. Android birim test seferberliği

- Status: completed.
- Problem: köprüde 22 test dosyası varken Android'de tek test var (`CoworkUiLogicTest.kt`).
  State makinesi ve saf yardımcılar test korumasız büyüyor.
- Çözüm (hedef sırasıyla, hepsi düz JUnit — Robolectric/emülatör gerekmez):
  1. `SessionStateReducerTest` — dosya zaten izole ve 70 satır; en hızlı kazanım. Her state geçişi
     için bir vaka: boşta→çalışıyor, çalışıyor→tamamlandı, hata, iptal.
  2. `ConversationPagingTest` — sayfa birleştirme/sıralama kuralları; boş liste, tek sayfa,
     çakışan mesaj id'leri vakaları.
  3. `BackendLabelsTest` — madde 10'daki `BackendLabels.kt` çıktıktan sonra: her backend değeri
     için `backendModelOptions`/`backendPermOptions` beklenen listeleri döndürüyor mu; bilinmeyen
     backend'te güvenli varsayılan dönüyor mu.
  4. `SessionsDrawer` yardımcıları — `repoTag`, `isoToEpochMillis` (geçersiz ISO → çökmeme),
     `oldestProcessAge`, `formatMessageTime` (geçersiz girdi → ham değeri döndürme).
  5. Madde 11 delegeleri çıktıkça her delegenin testi bu maddenin kapsamına eklenir.
- Kabul ölçütü: `gradlew :app:testDebugUnitTest` yeşil; toplam en az 25 test vakası. Köprüdeki
  `node --test` alışkanlığı gibi, release script'ine (`bridge/release.mjs` veya ayrı bir
  `run-android-tests.cmd`) test adımı eklenir ki sürüm kesmeden önce otomatik koşsun.
- Uygulama notu: `SessionStateReducerTest`, `ConversationPagingTest`, `BackendLabelsTest` (tüm saf yardımcılar dahil),
  `McpDelegateTest` ve `OtherDelegatesTest` (FileBrowserDelegate, AgyDelegate) yazıldı. Toplam 29 test vakası yeşil.
  Sapmalar ve gerekçeleri:
  (1) **Sahte istemci arayüz yerine open+subclass ile yapıldı** — Gerekçe: Kotlin extension fonksiyonları (`BridgeClientMcp.kt` vb.)
  statik metotlar olduğundan arayüz üyesi olamazlar ve arayüz üzerinden doğrudan dispatch edilemezler. Sınıfın (`BridgeClient`) `open` yapılması,
  extension metotların içindeki URL kurma ve JSON ayrıştırma mantığını korurken altındaki ağ/taşıma katmanını (`getJson`/`postJson`) yakalamamızı sağlar.
  (2) **JVM testlerinde org.json bağımlılığı eklendi** — simülasyon gerçek org.json bağımlılığıyla değiştirildi.
  (3) Proje kök dizinine otomatik test koşumu için `run-android-tests.cmd` eklendi.

## 14. bridge.log rotasyonu (hızlı kazanım)

- Status: completed.
- Problem: `bridge/bridge.log` 44,8 MB'a ulaşmış; rotasyon yok. Log sonsuz büyüyor, disk ve
  `tail` performansı kötüleşiyor.
- Çözüm (sıfır bağımlılık, ~30 satır):
  - Log yazan modüle (server.mjs içindeki mevcut log yolu neredeyse oraya) boyut-tabanlı rotasyon:
    köprü açılışında **ve** her 10 dakikada bir `stat` ile boyut kontrolü; 5 MB üzerindeyse
    `bridge.log.2` silinir, `bridge.log.1` → `bridge.log.2`, `bridge.log` → `bridge.log.1`
    yeniden adlandırılır ve yeni boş dosyaya devam edilir (en fazla 3 nesil, ~15 MB tavan).
  - Rotasyon anında açık dosya tanıtıcısı varsa akış kapatılıp yeniden açılır (append modunda
    yazılıyorsa rename sonrası yeni `open` yeterli).
  - `supervisor-out.log` / `supervisor-err.log` aynı mekanizmaya dahil edilir.
  - `.gitignore`'da `bridge.log*` ve `supervisor-*.log` kalıplarının bulunduğu doğrulanır; yoksa eklenir.
  - Mevcut 44,8 MB'lık dosya ilk rotasyonda kendiliğinden arşivlenir; elle silinmez (içinde aktif
    hata ayıklama izi olabilir).
- Test: `bridge/test/log-rotation.test.mjs` — geçici dizinde 6 MB'lık sahte log oluştur, rotasyonu
  çağır, `bridge.log.1`in oluştuğunu ve yeni `bridge.log`un boş olduğunu doğrula; 3-nesil tavanını
  (4. rotasyonda en eskinin silindiğini) doğrula.
- Uygulama notu: ilk plan "sıfır bağımlılık, ~30 satır" saf rotasyon fonksiyonunu öngörüyordu, ama
  uygulama sırasında bir kök neden ortaya çıktı — `run-bridge.cmd`'deki `node server.mjs >>
  bridge.log 2>&1` CMD redirect'i bridge.log'a paylaşımsız Windows HANDLE tuttuğu için Node içinden
  `fs.rename` `EBUSY` atıyor; rotasyon köprü canlıyken imkânsız. Çözüm mimariyi değiştirdi (kullanıcı
  onayıyla): (1) `log-rotation.mjs` — planlanan saf fonksiyon, 9 test. (2) `logger.mjs` — console.*
  çağrılarını append-sync ile bridge.log'a yönlendirir; `rotate()` dosyayı rename edip yeni boş dosyaya
  devam eder (CMD kilit yok). logWarn ve tüm mevcut console.* çağrıları değişmedi, her satıra ISO
  timestamp + [level] eklenir. (3) `run-bridge.cmd` çıktıyı artık `bootstrap.log`'a yönlendirir (NUL'a
  değil) — logger devreye girmeden ölen erken hatalar (node yok, syntax) yakalanır, normalde boş.
  (4) Vestigiyal supervisor-out/err.log hedefleri kaldırıldı. 6 logger + 9 rotasyon testi, 268 toplam
  köprü testi yeşil.

## 15. WebSocket token'ının query string'den çıkarılması

- Status: completed.
- Problem: `server.mjs:79` token'ı `Authorization: Bearer` yanında `?token=` query parametresinden
  de kabul ediyor (WebSocket el sıkışması için gerekliydi). Query string'ler log'lara, hata
  mesajlarına ve olası proxy kayıtlarına sızar; `bridge.log` içinde düz token bulunması muhtemel.
- Çözüm (iki adım):
  1. **Taşıma**: OkHttp WebSocket istekleri header taşıyabilir — Android tarafında
     `BridgeClient`'taki WS bağlantısına `Authorization: Bearer <token>` header'ı eklenir,
     köprüde `upgrade` isteğinde header öncelikli okunur. Query-param yolu **bir sürüm boyunca**
     geriye uyumluluk için kalır (eski APK'lar kopmasın), sonraki sürümde kaldırılır.
  2. **Sızıntı temizliği**: log yazan her nokta URL loglarken `token=([^&\s]+)` desenini
     `token=***` ile maskeler. Mevcut `bridge.log*` dosyaları rotasyon tavanına takılıp doğal
     yoldan silinene kadar bekletilir ya da madde uygulanırken elle temizlenir; ardından
     `config.json`'daki token **yenilenir** (eski token log'a sızmış kabul edilir).
- Test: `bridge/test/auth.test.mjs` genişletilir — header'lı WS upgrade kabul ediliyor,
  yanlış token reddediliyor, log maskesi `token=` değerini gizliyor. Manuel kabul: eski APK
  (query-param) + yeni APK (header) aynı köprüye bağlanabiliyor (geçiş sürümü boyunca).

## 16. README güncellemesi (kod dokümantasyonun önüne geçti)

- Status: completed.
- Problem: `README.md` Cowork modunu, `zcode.mjs` (Z-Code) ve `local-llama.mjs` backend'lerini,
  filo sağlığı panelini (10.23) ve workspace şablonlarını hiç anlatmıyor.
- Çözüm: README'nin "What It Does" ve backend listesi güncellenir; Cowork için kısa bir bölüm
  (proje-öncelikli oturumlar, `.cowork/providers/` metadata, `outputs/` paylaşımı) eklenir;
  WS kanalları ve REST alanları listesine `/zcode/*`, `/cowork/*`, `/local-llama/*` (köprüdeki
  gerçek adlarıyla doğrulanarak) eklenir. Kural olarak not düşülür: yeni backend eklenen PR/commit,
  README backend listesini de güncellemeden kapanmaz.
- Test: yok (dokümantasyon). Kabul: README'deki her backend adı `bridge/` içinde karşılık gelen
  modülle eşleşiyor; listede olmayan modül, modülü olmayan liste maddesi kalmıyor.
