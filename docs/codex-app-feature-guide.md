# Codex App Feature Guide

Bu dokuman Agent Bridge icindeki `codex-app` backend'inin bugunku durumunu,
Codex `app-server` yuzeyinden hangi ozellikleri alabilecegimizi ve bunlari
hangi sirayla urunlestirmemiz gerektigini tarif eder.

Kaynak zemin:

- OpenAI Codex App Server dokumani: https://developers.openai.com/codex/app-server
- OpenAI Codex app features dokumani: https://developers.openai.com/codex/app/features
- Lokal implementasyon:
  - `bridge/codex-app.mjs`
  - `bridge/routes/backend.mjs`
  - `bridge/server.mjs`
  - `android/app/src/main/java/com/agent/bridge/BridgeClient.kt`
  - `android/app/src/main/java/com/agent/bridge/RemoteViewModel.kt`
  - `android/app/src/main/java/com/agent/bridge/SessionStreamManager.kt`
  - `android/app/src/main/java/com/agent/bridge/ChatScreen.kt`

## 1. Mimari Zemin

`codex app-server`, Codex'in zengin istemciler icin kullandigi JSON-RPC 2.0
tabanli arayuzdur. CLI wrapper'dan farkli olarak bir komutu calistirip sonucu
beklemek yerine, uzun yasayan bir app-server process'i uzerinden thread, turn,
item, approval ve stream event'leri yonetilir.

Temel kavramlar:

- **Thread**: Bir kullanici ile Codex agent arasindaki kalici konusma.
- **Turn**: Kullanici istegi ve agent'in bu istek icin yaptigi tek calisma.
- **Item**: User message, agent message, command execution, file change,
  reasoning, plan, MCP tool call gibi turn icindeki birim.

Agent Bridge tarafinda `codex-app` backend'i su modeli izler:

1. Bridge tek bir `codex app-server --listen stdio://` process'i baslatir.
2. Bridge app-server ile newline-delimited JSON-RPC mesajlari uzerinden konusur.
3. Android tarafinda her mobil session bir bridge session id'si ile tutulur.
4. Bridge session'i Codex `threadId` ile esler.
5. App-server notification'lari bridge snapshot'ina indirgenir.
6. Android WebSocket snapshot'larini dinleyip UI state'e uygular.

Bu model ZCode'daki `app-server` desenine benzer, ancak Codex tarafinda thread,
turn, item, approval ve schema kavramlari daha zengindir.

## 2. Bugun Var Olan Ozellikler

### 2.1 Backend Ayrimi

`codex` ve `codex-app` Android'de ayri backend olarak gorunur.

Kullanicinin klasik Codex CLI wrapper ile app-server entegrasyonunu ayri
denemesi mumkundur. Bu dogru bir urun karari, cunku app-server davranisi farkli
ve daha genis bir lifecycle'a sahiptir.

Ilgili yuzeyler:

- `BridgeClient.codexAppModels`
- `BridgeClient.codexAppNew`
- `BridgeClient.codexAppPrompt`
- `BridgeClient.openCodexAppStream`
- `RemoteViewModel.enterCodexAppMode`
- `ChatScreen` backend secimi
- `LandingScreen` karti
- `SettingsScreen` gorunurluk toggle'i

### 2.2 Tek App-server Process'i

`bridge/codex-app.mjs` tek uzun yasayan app-server process'i yonetir.

Bu sayede:

- Her prompt icin yeni Codex CLI process'i acilmaz.
- Event stream kesintisiz takip edilir.
- Birden cok bridge session ayni app-server process'i uzerinden thread bazli
  multiplekslenebilir.

### 2.3 Session Baslatma ve Resume

Mevcut endpoint'ler:

- `GET /codex-app/models`
- `POST /codex-app/new`
- `POST /codex-app/prompt`
- `GET /codex-app/conversation`
- `GET /codex-app/disk-sessions`
- `POST /codex-app/adopt`
- `POST /codex-app/stop`
- `GET /codex-app/thought`
- `POST /codex-app/approve`
- `POST /codex-app/respond-user-input`
- `POST /codex-app/compact`
- `POST /codex-app/model`
- `WS /codex-app/stream`

`listDiskSessions()` once app-server `thread/list` kullanir, basarisiz olursa
disk fallback path'ine duser. `adoptSession()` once `thread/read` ile transcript
kurmaya calisir, sonra rollout JSONL fallback'ini korur.

### 2.4 Canli Stream

Bridge su app-server notification'larini isler:

- `turn/started`
- `turn/completed`
- `thread/started`
- `thread/status/changed`
- `thread/tokenUsage/updated`
- `thread/name/updated`
- `item/started`
- `item/completed`
- `item/agentMessage/delta`
- `item/plan/delta`
- `item/reasoning/textDelta`
- `item/reasoning/summaryTextDelta`
- `item/commandExecution/outputDelta`
- `item/fileChange/outputDelta`
- `turn/plan/updated`
- `thread/compacted`
- `serverRequest/resolved`
- `thread/closed`

Android tarafinda `SessionStreamManager` snapshot parse eder ve
`RemoteViewModel.applyStreamSnapshot()` UI state'e uygular.

### 2.5 Canli Plan Paneli

Plan chat transcript'ine mesaj olarak eklenmez. Ayrica `plan` alani olarak
tasinir.

Backend:

- `snapshot(s).plan`
- `onPlanDelta`
- `onTurnPlanUpdated`

Android:

- `PlanItem`
- `BackendStreamSnapshot.plan`
- `RemoteUiState.codexAppPlan`
- `CodexAppPlanPanel`

Bu ayrim onemli: plan bir conversation message degil, agent status panelidir.

### 2.6 Approval ve User Input

Backend su server request tiplerini normalize eder:

- `item/commandExecution/requestApproval`
- `item/fileChange/requestApproval`
- `item/permissions/requestApproval`
- `item/tool/requestUserInput`
- `mcpServer/elicitation/request`
- legacy/fallback approval tipleri

Android tarafinda:

- `ApprovalCard`
- `QuestionApprovalCard`
- `UserInputCard`
- `codexAppApprove`
- `codexAppAnswerQuestions`
- `codexAppRespondUserInput`

Bu sayede Codex'in tool calistirma, dosya degisikligi, ek izin ve kullanici
sorusu akislari mobilde cevaplanabilir.

### 2.7 Compact

`POST /codex-app/compact`, app-server tarafinda `thread/compact/start` cagirir.

Mevcut UX:

- ChatScreen uc nokta menusunde `Baglami sikistir`.
- Basarida toast/mesaj.

Eksik taraf:

- Context doluluk esigine gore onerme yok.
- Compact gecmisi veya sonucu UI'da ayrica gorunmuyor.

### 2.8 Interrupt / Stop

`stop(sessionId)` aktif turn varsa `turn/interrupt` kullanir. Bu dogru modeldir:
app-server process'i oldurulmez, sadece aktif turn iptal edilir.

### 2.9 Token ve Context Usage

`thread/tokenUsage/updated` notification'i:

- `contextTokens`
- `contextWindow`

alanlarina islenir. Android ust chip bunu gosterebilir.

### 2.10 Son Duzeltmeler

Yapilan kritik duzeltmeler:

- Prompt app-server'a ulastigi halde HTTP 400 donuyordu. Route seviyesinde
  `codex-app` icin delivered/running guard eklendi.
- Cikis-giris/adopt sonrasi user mesajlari kayboluyordu. `thread/read`
  tarafinda `userMessage` restore edilmeye baslandi.
- Regression test eklendi: `thread/read userMessage items are restored as user rows`.

## 3. Eksik Ama Degerli Ozellikler

Bu bolumdeki ozellikler `codex-app` backend'ini "calisan entegrasyon"dan
"gercek mobil Codex App deneyimi"ne tasir.

## 4. Feature 1: Thread Fork

### Amac

Ayni conversation noktasindan alternatif bir calisma dali acmak.

Ornek UX:

- Mesaj menusu: `Buradan dal ac`
- Session drawer: `Fork`
- Agent cevabindan sonra: `Alternatif dene`

### App-server Zemini

Codex app-server lifecycle dokumaninda `thread/fork` thread yonetim primitive'i
olarak geciyor.

### Backend Tasarimi

Yeni endpoint:

```http
POST /codex-app/fork
```

Body:

```json
{
  "sessionId": "bridge-session-id",
  "cwd": "optional override"
}
```

Davranis:

1. Bridge session bulunur.
2. `threadId` yoksa hata: `thread not started`.
3. `thread/fork` cagrilir.
4. Donen yeni thread id ile yeni bridge session yaratilir.
5. `thread/read` ile yeni transcript hydrate edilir.
6. Android'e yeni `sessionId` donulur.

Response:

```json
{
  "ok": true,
  "sessionId": "new-bridge-session-id",
  "threadId": "new-codex-thread-id"
}
```

### Android Tasarimi

`BridgeClient`:

- `codexAppFork(settings, sessionId): String`

`RemoteViewModel`:

- `codexAppForkCurrent()`

`ChatScreen`:

- Uc nokta menusune `Bu oturumu dallandir`.
- Basarida yeni session'a gec.

### Acceptance Criteria

- Aktif `codex-app` session fork edilebilir.
- Eski session bozulmaz.
- Yeni session transcript ile acilir.
- Yeni session'a prompt gonderilebilir.
- Test: unknown session, no thread, success response mock/pure path.

## 5. Feature 2: Turn Steer

### Amac

Agent aktif olarak calisirken yeni bir kullanici girdisini ayni turn'e eklemek.

Bugun prompt yeni turn mantigindadir. Agent calisirken kullanici "sunlari da
dikkate al" demek istediginde bunu `turn/steer` ile yapmak daha dogrudur.

### Backend Tasarimi

Yeni endpoint:

```http
POST /codex-app/steer
```

Body:

```json
{
  "sessionId": "bridge-session-id",
  "text": "Bunu da dikkate al"
}
```

Davranis:

1. Session bulunur.
2. `threadId` ve aktif `turnId` kontrol edilir.
3. Session running degilse hata veya normal prompt'a yonlendirme karari verilir.
4. `turn/steer` cagrilir.
5. User mesaj UI'da "steer" olarak veya normal user mesaj olarak gosterilir.

### Android Tasarimi

Composer davranisi:

- Backend `codex-app` ve `running=true` ise send butonu stop yerine steer modu
  sunabilir.
- Alternatif: composer altinda `Aktif ture ekle` toggle'i.

### Acceptance Criteria

- Agent calisirken ek prompt HTTP 200 doner.
- Agent turn kesilmeden ek bilgi alir.
- UI'da kullanici girdisi gorunur.
- Running degilken steer hata verir veya prompt'a fallback yapar; davranis net
  testlenir.

## 6. Feature 3: Approval Scope Secimi

### Amac

Approval kartini sadece "Onayla/Reddet" olmaktan cikarmak.

Olası aksiyonlar:

- Bir kez onayla
- Bu oturum icin onayla
- Reddet
- Iptal et

### Backend Durumu

`approvalResultFor()` zaten method bazli decision enum'larini uretir. Ancak UI
butonlari henuz scope/decision ayrimini tam yansitmiyor.

### Backend Tasarimi

`POST /codex-app/approve` body genisletilir:

```json
{
  "sessionId": "id",
  "allow": true,
  "decision": "acceptForSession",
  "scope": "session"
}
```

Method bazli mapping:

- command execution: `accept`, `decline`, `cancel`, `acceptForSession`
- file change: `accept`, `decline`, `cancel`, `acceptForSession`
- permissions: `permissions`, `scope`, `strictAutoReview`
- user input: `answers`
- elicitation: `action`, `content`

### Android Tasarimi

`ApprovalCard`:

- Primary: `Bir kez onayla`
- Secondary: `Bu oturumda onayla`
- Destructive: `Reddet`
- Optional overflow: `Iptal`

### Acceptance Criteria

- Her approval tipi icin dogru response shape uretilir.
- Eski basit approve/deny akisi bozulmaz.
- Unknown approval method safe reject eder.
- Android karti uzun metinlerde tasma yapmaz.

## 7. Feature 4: Diff ve File Change Paneli

### Amac

Agent'in dosya degisikliklerini chat icinde belirsiz thought olarak gostermek
yerine, dosya bazli incelenebilir diff paneline tasimak.

### Backend Tasarimi

`fileChange` item'lari normalize edilir:

```json
{
  "type": "fileChange",
  "id": "item-id",
  "files": [
    {
      "path": "src/App.kt",
      "status": "modified",
      "diff": "...",
      "summary": "..."
    }
  ],
  "approved": false,
  "completed": true
}
```

Yeni endpoint opsiyonlari:

- `GET /codex-app/changes?sessionId=...`
- `POST /codex-app/revert-file`

Ilk surumde sadece read-only diff yeterlidir.

### Android Tasarimi

Yeni panel:

- Degisen dosyalar listesi.
- Dosya basina eklenen/silinen satir sayisi.
- Tiklayinca diff gorunumu.
- Approval bekliyorsa ayni panelden onay.

### Acceptance Criteria

- File change item'lari kaybolmaz.
- Diff paneli refresh/adopt sonrasi tekrar kurulabilir.
- Buyuk diff'lerde truncate/expand davranisi vardir.

## 8. Feature 5: Command Execution Timeline

### Amac

Komut calistirmalarini chat mesajlarindan ayri, okunabilir timeline olarak
gostermek.

### Backend Tasarimi

Command item normalize edilir:

```json
{
  "id": "item-id",
  "command": "npm test",
  "cwd": "C:/repo",
  "status": "running|completed|failed",
  "stdout": "...",
  "stderr": "...",
  "exitCode": 0,
  "startedAt": 123,
  "completedAt": 456
}
```

Stream delta'lari:

- `item/commandExecution/outputDelta`

ile ayni item'a append edilir.

### Android Tasarimi

UI:

- `Komutlar` sekmesi veya inline collapsible row.
- Running komut icin spinner.
- Exit code badge.
- Stdout/stderr copy butonu.

### Acceptance Criteria

- Komut output'u canli akar.
- Uzun output UI'i kilitlemez.
- Refresh/adopt sonrasi komut ozeti gorunur.

## 9. Feature 6: Permission Mode Selector

### Amac

Kullanici yeni session baslatirken veya idle session'da izin politikasini
secebilsin.

Olası modlar:

- `yolo`: danger-full-access + never approval
- `ask`: workspace-write + on-request
- `on-failure`: workspace-write + on-failure
- `untrusted`: workspace-write + untrusted
- `plan`: sadece plan/guvenli mod semantigi, app-server mapping'i netlestirilmeli

### Backend Durumu

`policyForMode()` ve `sandboxPolicyFor()` var.

### Android Tasarimi

Session setup sheet:

- Model selector
- Workspace selector
- Permission mode segmented control

Chat header:

- Mode chip: `YOLO`, `Ask`, `On failure`, `Untrusted`

### Acceptance Criteria

- Yeni session permission mode ile baslar.
- Idle session'da mode degistirme sonraki turn'e uygulanir.
- Running session'da degisim ya reddedilir ya sonraki turn'e ertelenir.

## 10. Feature 7: Thread Search, Pin, Archive

### Amac

Disk session drawer'i basit liste olmaktan cikarmak.

### Backend Tasarimi

App-server thread APIs:

- `thread/list`
- `thread/read`
- archive/unarchive destekleniyorsa route ekle

Endpoint'ler:

- `GET /codex-app/disk-sessions?q=...`
- `POST /codex-app/archive`
- `POST /codex-app/unarchive`
- `POST /codex-app/pin` bridge-local metadata olabilir

Pin bilgisi app-server'da yoksa bridge-local JSON metadata olarak tutulabilir.

### Android Tasarimi

Session drawer:

- Search input
- Filter: aktif / arsiv / pinned
- Swipe action: archive
- Long press: pin

### Acceptance Criteria

- Search title, lastText ve cwd uzerinden calisir.
- Archive edilen session normal listeden kaybolur veya filtrelenir.
- Pin bilgisi bridge restart sonrasi korunur.

## 11. Feature 8: Context ve Compact UX

### Amac

Context kullanimi sadece chip olarak kalmasin; kullaniciya aksiyon onersin.

### UX

- `%70` uzeri: chip sari.
- `%85` uzeri: chip kirmizi.
- `%85` uzeri ve compact mumkunse composer ustunde `Baglami sikistir` onerisi.
- Compact sonrasi: `Baglam sikistirildi` + once/sonra token farki.

### Backend Tasarimi

`thread/compacted` notification'i daha zengin islenir:

```json
{
  "lastCompactAt": 123,
  "contextTokensBefore": 120000,
  "contextTokensAfter": 45000
}
```

Eger app-server bu farki vermiyorsa bridge onceki token state'ini saklayip
yaklasik fark hesaplayabilir.

### Acceptance Criteria

- Compact butonu thread yokken disabled veya anlamli hata verir.
- Compact sonrasi plan/chat kaybolmaz.
- Token chip guncellenir.

## 12. Feature 9: Plan Navigation

### Amac

Plan paneli sadece durum gostergesi degil, agent aktivitesine navigasyon olsun.

### Backend Tasarimi

Plan item'lari item id veya turn event ile baglanabiliyorsa saklanir:

```json
{
  "text": "Run tests",
  "status": "in_progress",
  "itemId": "optional",
  "turnId": "optional"
}
```

### Android Tasarimi

- Plan item'a tiklayinca ilgili command/thought/message'a scroll.
- Completed item'lar collapse edilebilir.
- In-progress item vurgulanir.

### Acceptance Criteria

- Plan paneli max yukseklik korur.
- Tiklanan item ilgili UI bolumune gider.
- Item id yoksa tiklama disabled kalir.

## 13. Feature 10: Schema Version Check

### Amac

Codex app-server schema surume bagli oldugu icin, bridge acilista desteklenen
method/field uyumlulugunu kontrol etsin.

### Backend Tasarimi

Opsiyonlar:

1. Build-time:
   - `codex app-server generate-json-schema --out bridge/schemas/codex-app`
   - Schema repo'da tutulur.

2. Runtime:
   - `codex --version`
   - Desteklenen min version check.
   - Kritik method probe: `initialize`, `thread/start`, `thread/list`.

Endpoint:

```http
GET /codex-app/info
```

Response:

```json
{
  "ok": true,
  "codexVersion": "0.x.y",
  "appServer": true,
  "features": {
    "threadFork": true,
    "turnSteer": true,
    "compact": true,
    "approvals": true
  }
}
```

### Android Tasarimi

Settings veya backend setup ekraninda:

- Codex app-server version
- Desteklenen ozellikler
- Uyumlu degilse uyari

### Acceptance Criteria

- Codex yoksa anlamli hata.
- Eski Codex surumunde app crash olmaz.
- Feature flag'ler UI'da kullanilir.

## 14. Daha Buyuk Urun Firsatlari

### 14.1 Worktree Mode

Resmi Codex app'in onemli ozelliklerinden biri local/worktree/cloud mode ayrimi.
Mobil bridge icin en uygulanabilir kisim worktree mode'dur.

Ilk surum:

- Session setup'ta `Local` / `Worktree` secimi.
- Worktree path bridge tarafinda olusturulur.
- Session cwd worktree'e set edilir.

Risk:

- Git repo olmayan klasorlerde davranis.
- Worktree cleanup.
- Ayni branch adlari.

### 14.2 Git Paneli

Ozellikler:

- `git diff` goruntuleme.
- Stage/unstage.
- Commit.
- Push.
- PR hazirligi.

Ilk surum read-only diff ile baslamali. Mutating git aksiyonlari daha sonra
approval gerektirmeli.

### 14.3 MCP Status Paneli

Codex app, CLI ve IDE extension MCP ayarlarini paylasir. Mobilde MCP status
gostermek degerli olur.

Ozellikler:

- Bagli MCP server listesi.
- Server status.
- Tool listesi.
- Hata loglari.

### 14.4 Automations / Heartbeat

Thread automation fikri mobil bridge'e su sekilde uyarlanabilir:

- Belirli session'a periyodik prompt.
- Sonucu notification olarak gosterme.
- Uzun sureli takip isleri.

Ornek:

- `Her 30 dakikada test server loglarini kontrol et.`
- `Bu PR build sonucunu takip et.`

### 14.5 Artifact Preview

Agent dosya urettiginde Android'de:

- PDF preview
- Image preview
- Markdown preview
- Spreadsheet/download link

Bridge zaten `/download` benzeri route'lara sahip oldugu icin bunun uzerine
kurulabilir.

### 14.6 Voice Prompt

Codex app'te dictation birinci sinif ozellik. Mobilde daha da dogal:

- Android speech-to-text.
- Composer'a transkript.
- Gondermeden once edit.

Bu app-server'dan bagimsiz Android ozelligidir.

## 15. Onceliklendirme

### Faz 1: Stabilizasyon

1. Prompt HTTP 400 guard testini route seviyesinde kalici testle.
2. Restore/adopt transcript kalitesini artir.
3. App-server crash/restart sonrasi thread resume davranisini sertlestir.
4. Schema/version info endpoint'i ekle.

### Faz 2: Yuksek Etki

1. Turn steer.
2. Thread fork.
3. Approval scope secimi.
4. Command execution timeline.
5. File change/diff paneli.

### Faz 3: Session Yonetimi

1. Thread search.
2. Archive/unarchive.
3. Pin.
4. Better session metadata.

### Faz 4: Codex App Benzeri Deneyim

1. Worktree mode.
2. Git paneli.
3. MCP status paneli.
4. Artifact preview.
5. Thread heartbeat/automation.

## 16. Test Stratejisi

Backend pure tests:

- Unknown session returns stable errors.
- New session defaults.
- Snapshot shape.
- Plan reducer.
- User message restore.
- Approval response shape per method.
- Compact without thread.
- Prompt delivered guard.
- Thread fork no-thread error.
- Turn steer no-active-turn error.

Integration tests, opsiyonel env ile:

- `CODEX_APP_INTEGRATION=1`
- Gercek `codex app-server` spawn.
- `initialize -> thread/start -> turn/start`.
- Notification parse smoke test.

Android tests/build:

- `gradlew assembleDebug`
- UI state compile safety.
- Manual smoke:
  - Backend sec.
  - Workspace sec.
  - Prompt gonder.
  - Stop.
  - Cik gir resume.
  - Approval karti.
  - Compact.

## 17. Release Checklist

Her anlamli degisiklikte:

1. `node --check bridge/codex-app.mjs`
2. `node --check bridge/routes/backend.mjs`
3. `node --check bridge/server.mjs`
4. `npm test` in `bridge/`
5. `gradlew.bat assembleDebug` in `android/`
6. Bridge restart.
7. Canli endpoint kontrolu:

```powershell
GET /codex-app/models
GET /codex-app/disk-sessions
POST /codex-app/new
POST /codex-app/prompt
GET /codex-app/conversation
```

Android degisirse OTA:

```powershell
node bridge/release.mjs <versionName> "<notes>"
```

Sadece bridge degisirse OTA gerekmez; bridge restart yeterlidir.

## 18. Kisa Sonuc

`codex-app` icin en yuksek degerli sonraki adimlar:

1. `turn/steer`
2. `thread/fork`
3. approval scope secimi
4. command timeline
5. diff/file change paneli
6. schema/version check

Bu siralama hem resmi app-server primitive'lerine yaslanir hem de Android
tarafinda kullaniciya hemen hissedilir fayda verir.
