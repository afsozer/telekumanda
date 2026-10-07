// Hatırlatıcı kaydı + zamanlayıcı (docs/ekran-goruntusu-hatirlatici-plani.md,
// Faz E3). Kayıt = `_genel-notlar/` altında normal bir markdown notu; frontmatter
// zamanlama bilgisini taşır. Ayrı veritabanı YOKTUR — notun kendisi tek kaynak,
// böylece mevcut "Notlarım" arayüzünde görünür ve elle düzenlenebilir.
//
// - tick() vakti gelmiş `pending` kayıtları `send` ile cihaza bildirir; en az
//   bir cihaz `ok:true` döndüyse `sent`, yoksa `attempts++` (maxAttempts sonrası
//   `failed` + log). Bridge kapalıyken vakti geçenler açılışta gecikmeli olarak
//   yine gönderilir.
// - send imzası: `async (extras, opts) => Array<{deviceId, ok, pending?}>`
//   (notification-delivery.mjs). `pending` = kuyrukta, uygulama onayı bekliyor.
// - send ve now dışa enjekte edilir; modül ağ/cihaz kullanmaz (testlerde sahte
//   send ile sürülür, geçici klasörde çalışır).
import fs from 'node:fs';
import path from 'node:path';
import { slugifyNoteName } from './note-slug.mjs';

// Dosya adı slug'ı cowork notlarıyla ORTAK (note-slug.mjs) — aynı başlık her
// iki yolda da aynı dosya adını üretsin. Buradaki tek fark boş sonuç yedeği.
export function slugify(raw) {
  return slugifyNoteName(raw) || 'hatirlatici';
}

// Minimal frontmatter: ilk satır `---`, kapanış `---`, arada `anahtar: değer`
// satırları. Tam YAML parser gerekmez, bağımlılık eklenmez. Çift tırnakla
// yazılmış değerlerin tırnakları sökülür (title'da `:`/`#` geçebildiği için
// create tırnakla yazar). Frontmatter yoksa null döner.
function parseFrontmatter(md) {
  const m = /^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/.exec(md || '');
  if (!m) return null;
  const data = {};
  for (const line of m[1].split(/\r?\n/)) {
    const mm = /^([A-Za-z0-9_]+):\s*(.*)$/.exec(line);
    if (!mm) continue;
    let value = mm[2];
    const quoted = /^"(.*)"$/.exec(value);
    if (quoted) value = quoted[1].replace(/\\"/g, '"');
    data[mm[1]] = value;
  }
  return { data, body: md.slice(m[0].length) };
}

// Verilen frontmatter alanlarını yazar/günceller; gövde ve DİĞER alanlar
// birebir korunur, satır sonu stili (LF/CRLF) de korunur. Değer `null` ise
// alan SİLİNİR (hatırlatıcıyı kaldırmak için). Frontmatter yoksa null döner —
// dosyaya dokunulmaz.
function setFrontmatterFields(md, fields) {
  const m = /^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/.exec(md || '');
  if (!m) return null;
  const nl = /\r\n/.test(m[1]) ? '\r\n' : '\n';
  const seen = new Set();
  const out = [];
  for (const line of m[1].split(/\r?\n/)) {
    const key = (/^([A-Za-z0-9_]+):/.exec(line) || [])[1];
    if (key && Object.prototype.hasOwnProperty.call(fields, key)) {
      seen.add(key);
      if (fields[key] !== null) out.push(`${key}: ${fields[key]}`);
      continue; // null → satır düşer, alan silinir
    }
    out.push(line);
  }
  for (const [k, v] of Object.entries(fields)) {
    if (!seen.has(k) && v !== null) out.push(`${k}: ${v}`);
  }
  return `---${nl}${out.join(nl)}${nl}---${nl}${md.slice(m[0].length)}`;
}

function updateReminderMeta(md, state, attempts) {
  return setFrontmatterFields(md, { reminder_state: state, reminder_attempts: attempts });
}

// `:`/`#` geçebilen değerleri çift tırnakla sar; içteki çift tırnağı kaçır
// (basit YAML kaçışı — tırnak aç/kapa kırmayacak kadar).
function yamlQuoted(value) {
  return '"' + String(value ?? '').replace(/"/g, '\\"') + '"';
}

// Date → `+03:00` offsetli ISO. `toISOString()` UTC `Z` üretiyor; çalışır ama
// notu elle düzenleyen için okunaksız. Türkiye 2016'dan beri DST kullanmıyor,
// sabit offset güvenli.
function isoWithOffset(d) {
  const p = (n) => String(n).padStart(2, '0');
  const t = new Date(d.getTime() + 3 * 60 * 60 * 1000);
  return `${t.getUTCFullYear()}-${p(t.getUTCMonth() + 1)}-${p(t.getUTCDate())}`
    + `T${p(t.getUTCHours())}:${p(t.getUTCMinutes())}:${p(t.getUTCSeconds())}+03:00`;
}

export function createReminders({
  notesDir,                  // create() buraya yazar (`_genel-notlar/`)
  // scan() kapsamı: verilirse TÜM notların .md yolları (proje `notlar/`
  // klasörleri dahil — cowork.listNotes()). Verilmezse yalnız notesDir taranır.
  // Hatırlatıcı yalnız genel notlarda çalışsın diye bir sebep yok; kullanıcı
  // proje notuna da tarih koyabilmeli.
  listNotePaths = null,
  // mdPath → cowork not kimliği. Bildirime konur; uygulama dokunulduğunda
  // ilgili notu açar. Verilmezse derin bağlantı olmadan gönderilir.
  noteIdOf = null,
  send,
  targets = [],
  maxAttempts = 10,
  log = () => {},
  now = () => new Date(),
} = {}) {
  // Gövde: ilk satır bildirim özeti olarak kullanıldığı için (firstLineOf)
  // en bilgilendirici parça başa konur — hatırlatıcıda alıntı, yoksa açıklama.
  // Ardından ekrandan birebir okunan metin ve etiketler gelir: notun asıl işi
  // arandığında bulunmak, o yüzden metin kırpılmaz, gövdeye olduğu gibi girer.
  function buildBody({ alinti, aciklama, metin, etiketler } = {}) {
    const ozet = String(alinti ?? '').trim();
    const acik = String(aciklama ?? '').trim();
    const parts = [];
    parts.push(ozet || acik);
    if (ozet && acik && ozet !== acik) parts.push(acik);
    const govde = String(metin ?? '').trim();
    if (govde) {
      parts.push('## Ekrandaki metin');
      parts.push(govde.split(/\r?\n/).map((l) => `> ${l}`.trimEnd()).join('\n'));
    }
    const tags = Array.isArray(etiketler)
      ? etiketler.map((t) => String(t ?? '').trim()).filter(Boolean)
      : [];
    if (tags.length) parts.push(`Etiketler: ${tags.join(' · ')}`);
    return parts.filter((p) => p !== '').join('\n\n');
  }

  // ── create ────────────────────────────────────────────────────────────────
  // Yeni not yazar. tarihSaat yoksa reminder_* alanları HİÇ yazılmaz: not
  // zamanlayıcıya girmez, sıradan bir not olarak yaşar. Ekran görüntüsünden
  // gelen `bilgi` kayıtlarının çoğu böyle — saklanır, bildirim atılmaz.
  function create({ baslik, tarihSaat, alinti, aciklama, metin, etiketler, sourceScreenshot } = {}) {
    const title = String(baslik ?? '').trim();
    if (!title) return null;
    fs.mkdirSync(notesDir, { recursive: true });
    // Çakışmada -2, -3... eki.
    let name = `${slugify(title)}.md`;
    let i = 2;
    while (fs.existsSync(path.join(notesDir, name))) {
      name = `${slugify(title)}-${i}.md`;
      i += 1;
    }

    const lines = ['---', `title: ${yamlQuoted(title)}`];
    if (tarihSaat !== undefined && tarihSaat !== null && tarihSaat !== '') {
      // Date verilirse de `+03:00` offsetli yazılır (UTC `Z` değil).
      lines.push(`reminder_at: ${tarihSaat instanceof Date ? isoWithOffset(tarihSaat) : String(tarihSaat)}`);
      // Durum/sayaç yalnız gerçekten zamanlanmış notta anlamlı; tarihsiz notta
      // yazılırsa `pending` görünüp asla çalmayan hayalet kayıt izlenimi verir.
      lines.push('reminder_state: pending', 'reminder_attempts: 0');
    }
    if (sourceScreenshot !== undefined && sourceScreenshot !== null && sourceScreenshot !== '') {
      lines.push(`source_screenshot: ${String(sourceScreenshot)}`);
    }
    lines.push('---');

    const body = buildBody({ alinti, aciklama, metin, etiketler });
    const filePath = path.join(notesDir, name);
    fs.writeFileSync(filePath, `${lines.join('\n')}\n${body}\n`, 'utf8');
    return { path: filePath, id: name };
  }

  // ── scan ──────────────────────────────────────────────────────────────────
  // notesDir altındaki .md dosyalarının frontmatter'ını ayrıştırır; reminder_at
  // olmayan notları (sıradan notlar da burada yaşıyor) atlar. Bozuk frontmatter
  // veya geçersiz tarih → log + atla, çökme yok. Klasör yoksa boş dizi.
  // Taranacak .md yolları: enjekte edilmiş liste (tüm notlar) ya da notesDir.
  function notePaths() {
    if (typeof listNotePaths === 'function') {
      try {
        return listNotePaths().filter((p) => typeof p === 'string' && p.toLowerCase().endsWith('.md'));
      } catch (err) {
        log(`reminders: not listesi alinamadi: ${String(err?.message || err).slice(0, 160)}`);
        return [];
      }
    }
    try {
      return fs.readdirSync(notesDir, { withFileTypes: true })
        .filter((e) => e.isFile() && e.name.toLowerCase().endsWith('.md'))
        .map((e) => path.join(notesDir, e.name));
    } catch {
      return [];
    }
  }

  function scan() {
    const out = [];
    for (const filePath of notePaths()) {
      const e = { name: path.basename(filePath) };
      let md;
      try {
        md = fs.readFileSync(filePath, 'utf8');
      } catch {
        log(`reminders: ${e.name} okunamadi, atlandi`);
        continue;
      }
      const fm = parseFrontmatter(md);
      if (!fm) {
        log(`reminders: ${e.name} bozuk frontmatter, atlandi`);
        continue;
      }
      if (fm.data.reminder_at === undefined || fm.data.reminder_at === null || fm.data.reminder_at === '') {
        continue; // sıradan not — zamanlayıcıyla ilgisi yok
      }
      const at = new Date(fm.data.reminder_at);
      if (Number.isNaN(at.getTime())) {
        log(`reminders: ${e.name} gecersiz reminder_at, atlandi`);
        continue;
      }
      out.push({
        path: filePath,
        title: fm.data.title ?? '',
        at,
        state: fm.data.reminder_state ?? 'pending',
        attempts: Number(fm.data.reminder_attempts) || 0,
        source: fm.data.source_screenshot ?? null,
      });
    }
    return out;
  }

  // Gövdenin ilk satırı (bildirim summary'si için). Dosya tick sırasında okunur —
  // kullanıcı notu elle düzenlediyse taze metin gider.
  function firstLineOf(filePath) {
    try {
      const fm = parseFrontmatter(fs.readFileSync(filePath, 'utf8'));
      if (!fm) return '';
      return fm.body.split(/\r?\n/)[0] ?? '';
    } catch {
      return '';
    }
  }

  // Atomik güncelleme: geçici dosya + renameSync. Başarısızlıkta log, throw yok.
  function writeMeta(filePath, state, attempts) {
    let tmp = '';
    try {
      const updated = updateReminderMeta(fs.readFileSync(filePath, 'utf8'), state, attempts);
      if (updated === null) {
        log(`reminders: ${path.basename(filePath)} frontmatter bulunamadi, durum yazilamadi`);
        return false;
      }
      tmp = `${filePath}.tmp-${process.pid}-${Date.now()}`;
      fs.writeFileSync(tmp, updated, 'utf8');
      fs.renameSync(tmp, filePath);
      return true;
    } catch (err) {
      if (tmp) { try { fs.unlinkSync(tmp); } catch {} }
      log(`reminders: ${path.basename(filePath)} durum yazilamadi: ${String(err?.message || err).slice(0, 160)}`);
      return false;
    }
  }

  // ── tick ──────────────────────────────────────────────────────────────────
  // Vakti gelmiş pending kayıtları gönderir. Asla throw etmez; her kayıt için
  // en az bir cihaz ok dediyse sent, yoksa attempts++ (maxAttempts sonrası
  // failed). Vakti geçmişler de gönderilir — bridge kapalıyken kaçanlar
  // açılışta gecikmeli olarak yine gitsin.
  async function tick() {
    const result = { sent: 0, failed: 0, skipped: 0 };
    try {
      const all = scan();
      const nowMs = now().getTime();
      const due = all.filter((r) => r.state === 'pending' && r.at.getTime() <= nowMs);
      result.skipped = all.length - due.length;

      for (const rec of due) {
        let outcomes = [];
        let threw = false;
        try {
          let noteId = '';
          if (typeof noteIdOf === 'function') {
            try { noteId = String(noteIdOf(rec.path) || ''); } catch { noteId = ''; }
          }
          outcomes = await send(
            {
              // `reminder` kendi bildirim tipi (APK 11.47+). Eski sürümler
              // bilinmeyen kind'ı sessizce düşürür — bu yüzden APK bridge'den
              // ÖNCE kurulmalı.
              kind: 'reminder',
              deliveryId: `reminder:${noteId || rec.path}:${rec.at.toISOString()}`,
              noteId,
              title: rec.title.slice(0, 300),
              summary: firstLineOf(rec.path).slice(0, 300),
            },
            { targets },
          );
        } catch (err) {
          threw = true;
          log(`reminders: ${path.basename(rec.path)} gonderim hatasi: ${String(err?.message || err).slice(0, 160)}`);
        }
        const ok = !threw && Array.isArray(outcomes) && outcomes.some((o) => o && o.ok === true);
        if (ok) {
          result.sent += 1;
          writeMeta(rec.path, 'sent', rec.attempts);
        } else if (!threw && outcomes.some(o => o?.pending === true)) {
          // Kuyrukta bekleyen (uygulama henüz onaylamadı) başarısız deneme sayılmaz.
          result.skipped += 1;
        } else {
          result.failed += 1;
          const attempts = rec.attempts + 1;
          if (attempts >= maxAttempts) {
            log(`reminders: ${path.basename(rec.path)} ${maxAttempts} deneme sonrasi failed`);
            writeMeta(rec.path, 'failed', attempts);
          } else {
            writeMeta(rec.path, 'pending', attempts);
          }
        }
      }
    } catch (err) {
      // tick asla throw etmez — beklenmedik hata log'a düşer, sayaçlar olduğu gibi döner.
      log(`reminders: tick hatasi: ${String(err?.message || err).slice(0, 160)}`);
    }
    return result;
  }

  // ── setReminder ───────────────────────────────────────────────────────────
  // VAR OLAN bir nota (genel ya da proje notu) hatırlatıcı kurar/kaldırır.
  // Elle kurmanın yolu bu: `at` null ise reminder_* alanları silinir, doluysa
  // sayaç sıfırlanıp `pending`e döner (geçmişte `sent` olmuş not yeniden kurulur).
  // Gövdeye ve diğer frontmatter alanlarına dokunulmaz.
  function setReminder(mdPath, at) {
    let md;
    try {
      md = fs.readFileSync(mdPath, 'utf8');
    } catch {
      return { ok: false, error: 'not okunamadi' };
    }
    let fields;
    if (at === null || at === undefined || at === '') {
      fields = { reminder_at: null, reminder_state: null, reminder_attempts: null };
    } else {
      const when = at instanceof Date ? at : new Date(at);
      if (Number.isNaN(when.getTime())) return { ok: false, error: 'gecersiz tarih' };
      // Frontmatter'a her zaman +03:00 offsetli yazılır (plan kararı); `Z`
      // biçimi çalışır ama notu elle düzenlerken okunaksız.
      fields = { reminder_at: isoWithOffset(when), reminder_state: 'pending', reminder_attempts: 0 };
    }
    const updated = setFrontmatterFields(md, fields);
    if (updated === null) return { ok: false, error: 'frontmatter yok' };
    const tmp = `${mdPath}.tmp-${process.pid}-${Date.now()}`;
    try {
      fs.writeFileSync(tmp, updated, 'utf8');
      fs.renameSync(tmp, mdPath);
    } catch (err) {
      try { fs.unlinkSync(tmp); } catch {}
      return { ok: false, error: String(err?.message || err).slice(0, 160) };
    }
    return { ok: true, path: mdPath, reminder_at: fields.reminder_at };
  }

  return { create, scan, tick, setReminder, slugify };
}
