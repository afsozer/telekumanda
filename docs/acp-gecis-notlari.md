# Antigravity'i ACP ile yeniden bağlamak — ölçüm notları

08.11.2026'da agy backend'i (PTY + transcript ayrıştırma + kill/respawn) koddan
tamamen söküldü. Sebep: agy artık iki tane düzgün protokol sunuyor ve eski
kurulumun gerekçesi ortadan kalktı. Bu dosya, yeniden bağlamadan önce ölçülmüş
olguları tutar — tahmin yok, hepsi bu makinede canlı doğrulandı.

## Sürüm tuzağı

`agy --version` **1.4.3** diyor; `agy acp` ile konuşunca kendini
`"agentInfo":{"name":"Antigravity ACP Server","version":"1.5.3"}` diye tanıtıyor.
`--version` çıktısına karar bağlama, yetenek yokluğunu ona dayandırma.

`acp` alt komutu `agy --help` çıktısında **görünmüyor** ve `agy acp --help`
bilinmeyen bayrak sayıp genel yardımı basıyor; "yok" sanmanın yolu budur.
Varlığını `initialize` göndererek doğrula.

## 1. ACP (tercih edilen yol)

`agy acp` → stdio üzerinden JSON-RPC. `initialize` yanıtından ölçülen yetenekler:

```
protocolVersion     : 1
sessionCapabilities : resume, close, load, list, additionalDirectories
promptCapabilities  : image, embeddedContext  (audio: false)
loadSession         : true
auth                : oauth-personal | oauth-business | gemini-api-key | agent-platform
_meta.antigravity   : tier, systemPrompt.append
```

Yani `listDiskSessions` / `adoptSession` / transcript tarama işinin tamamı
protokolden geliyor. CHANGELOG'a göre ayrıca (ölçülmedi): akan turu başka prompt
göndererek steer etmek, `session/resume` geçmişi yeniden oynatmadan sürdürmek,
izin istekleri ve soru formlarının protokol mesajı olarak gelmesi,
`default`/`accept-edits`/`plan` oturum kipleri.

Repoda ACP istemcisi YOK; `bridge/omp-rpc-client.mjs` benzer bir JSON-RPC
kalıbı, çoğaltmak için oradan başla. ACP standart olduğu için aynı istemci
başka ajanlara da hizmet eder.

## 2. stream-json (daha az iş, daha az yetenek)

`agy -p='' --input-format stream-json --output-format stream-json`

Belgelenmemiş; şema binary'den çıkarılıp deneyerek bulundu:

```jsonc
// GİRDİ (NDJSON, satır başına bir tur)
{"event":"user","message":{"role":"user","content":[{"type":"text","text":"..."}]}}

// ÇIKTI
{"event":"init","conversation_id":"...","init":{"model":...,"cwd":...,"tools":[...],"permission_mode":"always-proceed"}}
{"event":"step_update","step_update":{"step_index":1,"state":"ACTIVE|DONE","step_type":"agent_response","text_delta":"...","usage":{...}}}
{"event":"result","result":{"status":"SUCCESS|ERROR","response":"...","num_turns":2,"usage":{...},"finished_reason":"..."}}
```

Tuzaklar:
- `event` alanı ZORUNLU; `user_input`/`prompt`/`message` gibi adlar
  "ignoring unsupported stream input message event" ile sessizce yutulur.
- `-p` Go flag paketinden geliyor ve argüman ister: `-p=''` yaz. `-p --input-format`
  sırası `--input-format`'ı prompt sanıyor.

Ölçüm (gemini-3.8-flash-low): tek süreçte iki satır → aynı `conversation_id`,
`num_turns:2`, ikinci tur birinci turun bağlamını hatırladı. **kill+respawn
gereksiz.** `usage` input/output/thinking/cache_read ayrı ayrı geliyor; eski
backend'de context göstergesi hep 0'dı, artık doldurulabilir.

## Eski kurulumdan korunmaya değer tek bulgu: paylaşımlı home

Masaüstü Antigravity IDE ve agy CLI diskte **birebir aynı formatı** kullanıyor
(`brain/<id>/.system_generated/logs/transcript.jsonl` + `conversations/<id>.db`),
yalnız `~/.gemini` altında farklı home dizinlerinde:

| home | dizin |
|---|---|
| CLI | `~/.gemini/antigravity-cli` |
| IDE | `~/.gemini/antigravity` |

Home'u seçen şey agy'nin `--help`'te görünmeyen **`--app_data_dir`** bayrağı
(desktop `language_server.exe` de aynı bayrakla çalışıyor; 07.07.2026'da izole
home ile doğrulandı). Telefonun masaüstü IDE oturumlarını izlemesi ve
sürdürmesi buna dayanıyordu — ACP'de `session/list`/`session/resume` ile aynı
şey protokolden gelebilir, ama gelmezse yol budur.

Aynı `.db`'ye iki `language_server` yazarsa SQLite bozulabilir: konuşma başına
tek yazar ilkesini koru (eski kodda "son N ms'de başka istemci yazdıysa resume
etme" heuristiğiydi; ACP'de daha sağlam bir kilide çevrilebilir).

## Söküldüğünde birlikte giden şeyler

- `node-pty` bağımlılığı (tek kullanıcısı agy'ydi) — `bridge/package.json`'dan düştü.
  Node 22 pini gerekçesini kaybetti ama yerinde duruyor.
- Antigravity kota kartı: `language_server.exe`'nin gRPC-web ucundan
  `RetrieveUserQuotaSummary` (PID + `--csrf_token` PowerShell'le bulunuyordu).
  Geri isteniyorsa git geçmişinde `bridge/usage.mjs`.
- Lite sürüm artık yalnız Claude sunuyor.
- Antigravity MCP kaydı (`bridge/mcp.mjs` + Ayarlar > MCP) **kaldı**: makinedeki
  Antigravity kurulumunun config dosyasını yönetiyor, backend'den bağımsız.
  `mcpTargetFor` içindeki `agy -> antigravity` eşlemesi kalktı; ACP backend'i
  gelince oraya yeniden bağlanır.
- `screenshot-extract.mjs` ve `pdf-lang.mjs` agy'yi tek seferlik `--print`
  çıkarım motoru olarak kullanmaya devam ediyor (not çıkarma, PDF dil tespiti).
  Bunlar backend değil; `AGY_EXE` yolu pdf-lang içine gömüldü.


## Köprüye bağlandı (08.11.2026, aynı gün)

Yukarıdaki plan uygulandı: `bridge/acp-client.mjs` (taşıma) + `bridge/agy.mjs`
(backend) yazıldı, `server.mjs`'e kaydedildi. Uygulama sırasında ÖLÇÜLEN,
yukarıda yazılı olmayan dört şey:

1. **`session/new`'in `cwd`'si araçların göreli yol çözümünü değiştirmiyor.**
   Tek ortak ACP çocuğunu `homedir`'de açıp çalışma dizinini yalnız
   `session/new` parametresiyle geçtim; ajan "bu klasörde dosya oluştur"
   denince `C:\Users\Sozer\deneme.txt` yazdı. Araçlar SÜREÇ cwd'sini kullanıyor.
   Çözüm: süreç havuzu çalışma dizini başına bir çocuk
   (`istemciler: cwd -> istemci`), aynı dizindeki oturumlar paylaşır.
   Bu kusur yalnız canlı uçtan uca denemede göründü; birim testi yakalamazdı.

2. **`session/cancel` bildirimdir.** İstek olarak gönderilirse yanıt hiç
   gelmiyor ve çağıran sonsuza kadar bekliyor. Bu yüzden istemcideki her
   isteğin zaman aşımı var.

3. **Olay döngüsü dengesi.** Çocuk + stdio boruları döngüyü tutuyor:
   `node --test` 5 sn yerine 100 sn'de zorla kapandı. Hepsini unref edince
   ters kusur çıktı: bekleyen tek iş bir ACP isteği olduğunda Node "yapacak iş
   yok" deyip çıkıyor ve istek yarıda kalıyor (`newSession` "ACP istemcisi
   kapatildi" döndü). Doğrusu ikisinin arası: **bekleyen istek varken ref'li,
   boşta unref'li.** Ayrıca boşta kalan çocuk 5 dk sonra biçiliyor ve çıkış
   kancası yetim `agy.exe` bırakmıyor.

4. **`session/set_config_option` eşlenemedi.** `id` / `configOptionId` /
   `optionId` üç ad da `-32602 Unknown config option: ` veriyor (parametre adı
   boş geliyor, yani beklenen alan başka). Düşünme seviyesi (thought_level) bu
   yüzden değiştirilemiyor; model `session/set_model`, kip `session/set_mode`
   ile çalışıyor.

### Canlı doğrulama (uçtan uca, gerçek ajan)

23 kontrol: oturum açma, model kataloğunun protokolden gelmesi, basit tur
(cevap + token sayacı), dosya yazma turunda **onay kartının çıkması ve
`respond('yes')` ile çözülmesi**, dosyanın gerçekten yazılması, araç satırı +
detay, `session/list` (30 oturum), eski oturumun `session/load` ile
devralınması ve geçmişinin yüklenmesi, izin kipi/model değiştirme, slash
komutlarının protokolden gelmesi, kapatma. Hepsi geçti.

### Sırada ne var

Köprü tarafı bitti. Kalan: **Android ve web istemcilerini yeniden bağlamak**
(backend kataloğu `/backends`'ten geldiği için kontroller kendiliğinden
çizilir, ama `Backend.AGY`, delege, oturum ekranları ve MCP hedef eşlemesi
silinmişti). Bu turda yapılmadı.


## İstemciler bağlandı (08.11.2026, üçüncü tur)

Android ve web yeniden bağlandı; iş bitti.

**Android'de ayrı bir AgyDelegate YAZILMADI.** Eski kurulumda 252 satırlık kendi
delegesi vardı çünkü disk oturumları, trust promptu ve PTY akışı agy'ye özeldi.
ACP'de agy diğer provider'larla aynı iskelete oturuyor, bu yüzden yaşam döngüsü
`ProviderLifecycleDelegate` içine girdi (enterAgy / startAgy / resumeAgy /
startAgyPolling / exitAgy / setAgyModel / setAgyPermissionMode). Durum kutusu
`AgyUiState`, jenerik tablolara (model seçici, izin kipi seçici, oturum
çekmecesi, sekme etiketi, monogram, çevrimdışı anahtar) birer dal eklendi.

**Onay kartı için istemcide agy'ye özel kod YOK.** Köprü, ACP izin isteğini
istemcinin zaten ayrıştırdığı `ApprovalInfo` şekline çeviriyor
(`requestId`/`kind`/`tool`/`summary`/`description`/`options`), kart olduğu gibi
çiziliyor. `POST /agy/approve {allow}` bekleyen protokol isteğini çözüyor.

### Rota testinin yakaladığı gerçek hata

`bridge/test/agy-routes.test.mjs` yazılırken ilk koşuda çıktı:
**`registerBackend`'in `/new` ucu `newSession`'ı await etmiyordu.** Diğer
backend'lerde senkron olduğu için yıllarca görünmemiş; agy'de async olduğu için
`r.ok` undefined kalıyor ve uç HER istekte 400 dönüyordu. Yani modül testi ve
canlı ölçüm yeşilken telefondan oturum açmak imkânsız olurdu. Düzeltildi
(`await mod.newSession(...)`, senkron modüllerde zararsız).

Ders: modül testi + canlı ajan ölçümü rota katmanını ATLIYOR. Uçları port
açmadan süren test o boşluğu kapatıyor.

### Lite

Lite yine Claude + Antigravity sunuyor (söküm turunda Claude'a inmişti).
