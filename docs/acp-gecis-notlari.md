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
