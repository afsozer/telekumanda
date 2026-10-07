# Claude App — Özellik Planı

Hedef: kalıcı backend (`bridge/claude-app.mjs`) üzerine 5 özellik. Backend uçları
`bridge/server.mjs` içinde `registerBackend(router, 'claude-app', ...)` altında; UI
telefon istemcisinde (ayrı repo/app) — burada "UI" maddeleri o tarafı işaret eder.

Event reducer: `handleEvent` (claude-app.mjs:280). Snapshot şekli: `snapshot` (claude-app.mjs:186).

---

## 1. Slash komut desteği

**Mevcut durum.** `prompt()` (claude-app.mjs:387) mesajı düz `user` text olarak stdin'e
yazıyor. `--input-format stream-json` modunda CLI slash komutlarını yorumlamaz; init
event'i `slash_commands` listesini veriyor (claude-app.mjs:147, `getInfo` ile UI'a akıyor).

**Adımlar.**
- [ ] **Doğrulama (önce bu):** stdin'e `/help` ve bir custom `.claude/commands/*` komutu
      gönderip stream-json çıktısını incele. CLI bunları text-içi yorumluyor mu, yoksa
      literal mi geçiyor? Sonuca göre dallan.
- [ ] **Built-in komutlar** (`/compact`, `/clear`, `/model`):
      - `/compact` → control_request gönder (compact_boundary event'ini zaten işliyoruz,
        claude-app.mjs:336). Yoksa stdin'e uygun control mesajı.
      - `/clear` → süreci öldür + mesajları temizle + yeni session id (resumeFromDisk=false).
      - `/model <x>` → `setModel` (bkz. Özellik 4).
- [ ] **Custom komutlar**: init'teki `slashCommands` listesini UI'a autocomplete olarak ver
      (zaten `/info` ucunda mevcut). Seçilince prompt'a komut metnini koy.
- [ ] `prompt()` başında `text.trimStart().startsWith('/')` ise slash router'a yönlendir.
- [ ] Yeni uç gerekirse: `POST /claude-app/slash { sessionId, command, args }`.

**Risk.** stream-json modunda built-in slash davranışı CLI sürümüne bağlı; doğrulama şart.

---

## 2. Interrupt'ı UI'a bağlamak

**Mevcut durum.** Backend tam: `interrupt()` (claude-app.mjs:373) + uç
`POST /claude-app/interrupt` (server.mjs:266). `stop()`'tan farkı: süreci öldürmez,
sadece aktif turu keser; sonraki prompt aynı oturumda devam eder.

**Adımlar.**
- [ ] **UI:** oturum `running` iken görünen "Durdur" butonu → `POST /interrupt { sessionId }`.
- [ ] Buton durumu snapshot'taki `running` alanına bağlı (snapshot.running zaten var).
- [ ] Interrupt sonrası tur `result` event'i ile idle'a düşer (finalizeTurnIdle), UI otomatik güncellenir.

**Backend değişikliği gerekmez** — sadece UI. En hızlı kazanç.

---

## 3. Tool sonuçlarını gösterme

**Mevcut durum.** `tool_use` (çağrı) gösteriliyor (claude-app.mjs:330): özet satır + tam
input `toolDetails`'te. Ama `tool_result` (çıktı) hiç işlenmiyor — `handleEvent` yalnızca
`assistant` content'ini geziyor.

**Adımlar.**
- [ ] stream-json'da tool sonuçları `user` rollü mesaj içinde `tool_result` content bloğu
      olarak döner (`{ type:'tool_result', tool_use_id, content }`). `handleEvent`'e
      `ev.type === 'user'` dalı ekle ve content'teki `tool_result`'ları yakala.
- [ ] Eşleştirme: `tool_use` satırını gönderirken `tool_use_id`'yi sakla (thought satırına
      ekle). Sonuç gelince ilgili thought'un detayına çıktıyı iliştir (`toolDetails[idx]`'e
      append ya da ayrı `resultIndex`).
- [ ] Uzun çıktıyı kırp (capMessages mantığına benzer limit); tam metni `/thought` ile aç.
- [ ] **UI:** thought satırı açıldığında input + output birlikte görünsün.

---

## 4. Model / permission-mode değişimi (tur ortası)

**Mevcut durum.** `setPermissionMode()` (claude-app.mjs:477) var ama yalnızca idle/plan-toggle
senaryosu; uç `POST /permission-mode` (server.mjs:237). Model değişimi: `prompt()` `model`
parametresi alıyor ama kalıcı süreç zaten ayağa kalkmışsa `--model` yeniden uygulanmaz.

**Adımlar.**
- [ ] **Permission mode (çalışırken):** CLI control_request ile mode değişimini destekliyor mu
      doğrula. Destekliyorsa `setPermissionMode`'u running iken de control üzerinden uygula;
      yoksa "bir sonraki turda geçerli" notuyla bayrağı sakla.
- [ ] **Model değişimi:** `setModel({ sessionId, model })` ekle. Süreç çalışmıyorsa `s.model`
      güncelle (sonraki ensureProc `--model` ile alır). Çalışıyorsa: ya control_request,
      ya da turu bitirip resume'da yeni model (resumeFromDisk=true olduğu için --resume güvenli).
- [ ] Yeni uç: `POST /claude-app/model { sessionId, model }`.
- [ ] **UI:** model + permission-mode seçici (mevcut `init` envanterinden besle).

---

## 5. Maliyet / token rozeti

**Mevcut durum.** `contextTokens` + `contextWindow` snapshot'ta var (claude-app.mjs:192).
`lastCost` ve `lastUsage` `result` event'inde toplanıyor (claude-app.mjs:340) ama snapshot'a
**konmuyor**.

**Adımlar.**
- [ ] `snapshot()`'a `cost: s.lastCost || 0` ekle (ve istenirse turn-bazlı token).
- [ ] **UI:** başlıktaki ctx göstergesinin yanına `$0.0123` rozeti; uzun bas → detay.
- [ ] Küçük iş — backend tek satır + UI rozet.

---

## Öncelik sırası (getiri/efor)

1. **Interrupt UI** — backend hazır, sadece buton. (en hızlı)
2. **Maliyet rozeti** — backend tek satır.
3. **Tool sonuçları** — orta; reducer'a `user`/`tool_result` dalı.
4. **Model / permission-mode** — orta; control davranışı doğrulaması gerekli.
5. **Slash komutları** — en büyük; CLI davranış doğrulaması + built-in/custom ayrımı.
