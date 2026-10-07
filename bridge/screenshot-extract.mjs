// Ekran goruntusunu agy (Antigravity CLI) ile okutup icindeki yapilacak is /
// randevu / tarih bilgisini JSON olarak cikaran modul (hatirlatici plani Faz E2).
//
// - `--add-dir` ZORUNLU: agy calisma dizinini yok sayiyor, verilmezse kendi
//   scratch klasorune bakip olmayan dosya icin "basardim" diyebiliyor
//   (07.08'de canli gorulen davranis). Bu modulde asla atlanmaz.
// - `--mode plan`: salt okunur; cikarim icin hicbir yazma yetkisi gerekmez.
// - `--app_data_dir`: bu sistem isi normal Antigravity CLI oturum deposuna
//   yazilmaz. Aksi halde her ekran goruntusu kullanicinin AGY gecmisinde ayri
//   bir sohbet olarak gorunuyor. Izole home yalniz siniflandiriciya aittir.
// - `--dangerously-skip-permissions` ZORUNLU: agy 07.08'den beri goruntuyu
//   acmak icin izin soruyor, headless'ta soramadigi icin otomatik reddediyor
//   ve extract HER goruntude sessizce null donuyordu. Bayragin adi urkutucu
//   ama `--mode plan` yazmayi zaten kapatiyor: salt okunurluk korunur.
// - Cikti savunmacı islenir: markdown fence soyulur, ilk `{` ile son `}`
//   arasi parse edilir. Her basarisizlikta `null` doner, asla throw etmez —
//   cagiran (Faz E3) kaydi `seen`'de birakip sessizce gecer.
import { execFile } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

// Sabit saat dilimi: Turkiye 2016'dan beri DST kullanmiyor.
const TZ_OFFSET = '+03:00';
const LIMIT_BASLIK = 60;
const LIMIT_ALINTI = 120;
// Aciklama ve metin bilerek genis: not bir INDEKS, goruntunun kendisi zaten
// duruyor. Asil isi arandiginda bulunmak, o yuzden metin kirpmaktansa bol
// tutulur (07.08 karari - 120 karakter sozlesme ekraninda yetmiyordu).
const LIMIT_ACIKLAMA = 400;
const LIMIT_METIN = 800;
const LIMIT_ETIKET = 4;
const TURLER = new Set(['hatirlatici', 'bilgi', 'yok']);
// Cikarim kendi agy home'unda kosar (bkz. dosya basi). Disari aciliyor cunku
// her cikarim orada bir konusma birakiyor ve o birikimi budayan taraf
// server.mjs — home adini iki yerde elle yazmak sessiz kacak demek olurdu.
export const EXTRACT_APP_DATA_DIR = 'agentbridge-screenshot-notes';
const APP_DATA_DIR = EXTRACT_APP_DATA_DIR;

// Date'i +03:00 offset'li ISO'ya bicimler (milisaniyesiz). Duvar saati UTC
// alanlarindan degil, tarihe +3 saat kaydirilmis bir kopyanin UTC alanlarindan
// okunur — boylece PC'nin kendi saat dilimi ne olursa olsun deterministik.
function toIsoWithOffset(d) {
  const p = (n) => String(n).padStart(2, '0');
  const local = new Date(d.getTime() + 3 * 60 * 60 * 1000);
  return (
    `${local.getUTCFullYear()}-${p(local.getUTCMonth() + 1)}-${p(local.getUTCDate())}` +
    `T${p(local.getUTCHours())}:${p(local.getUTCMinutes())}:${p(local.getUTCSeconds())}` +
    TZ_OFFSET
  );
}

// Dosyanin mtime'i: ad tanima uymuyorsa referans an olarak son degisiklik
// zamani kullanilir. Dosya yoksa null (extract orada new Date()'e duser).
function fileMtime(localPath) {
  try {
    const st = fs.statSync(localPath);
    return st.mtime instanceof Date && !Number.isNaN(st.mtime.getTime()) ? st.mtime : null;
  } catch {
    return null;
  }
}

// 14.08.2026: 3.6-high -> 3.7-medium (kullanici karari).
//
// Onceki olcum 3.6 UZERINDE yapilmisti ve hala gecerli olan uyariyi tasiyor:
// 07.08'de 4 gercek ekran goruntusunde low ile high yan yana denendi. Kayda-deger
// yargisinda ikisi de 4/4 dogruydu (oyun ekranlarinda yanlis pozitif yok), fark
// METIN CIKARIMINDA cikti: low, sozlesme ekraninda telefon numarasini ve lisans
// bitis tarihini dusurdu. Sure farki yoktu (ort. low 16 sn, high 14 sn).
//
// Yani bu isteki risk hiz degil, dusuk cabanin metin atlamasi. 3.7-medium bu
// testten GECMEDI; sozlesme/fatura gibi yogun metinli ekranlarda alan dustugunu
// gorursen ilk denenecek sey `-high`.
//
// Disari aciliyor cunku server.mjs hem yapilandirma yedegi hem acilis logu icin
// ayni degeri kullaniyordu; iki ayri dizge birbirinden kayabiliyordu.
export const DEFAULT_SCREENSHOT_MODEL = 'gemini-3.7-flash-medium';

export function createScreenshotExtract({
  agy = 'agy',                 // calistirilabilir yol
  model = DEFAULT_SCREENSHOT_MODEL,
  addDir,                      // agy'ye --add-dir ile verilecek klasor (ZORUNLU)
  timeoutMs = 120000,
  runCommand = null,           // test enjeksiyonu: (file, args) => Promise<{err, out, errOut}>
  log = () => {},
} = {}) {
  // Test enjeksiyonu (file, args) imzasiyla cagrilir; canli calismada ayni
  // imza execFile'e cevrilir. Argumanlar hep dizi olarak gecer — kabuk
  // stringi kurulmaz, tirnak kacisi derdi olmaz.
  const run = typeof runCommand === 'function'
    ? (args) => Promise.resolve(runCommand(agy, args))
    : (args) => new Promise((resolve) => {
      execFile(agy, args, { timeout: timeoutMs, windowsHide: true }, (err, stdout, stderr) => {
        resolve({ err, out: String(stdout || ''), errOut: String(stderr || '') });
      });
    });

  // Android ekran goruntusu adi: Screenshot_YYYYMMDD_HHMMSS[_<paket>].jpg
  // → yil/ay/gun/saat/dakika/saniye, +03:00 yerel saat. Desene uymuyorsa null.
  // Paket eki OPSIYONEL: bazi kayitlarda yok (Screenshot_20260807_110354.jpg)
  // ve sondaki `_` sart kosulursa bu adlar bosuna mtime'a dusuyordu.
  function parseCapturedAt(fileNameOrPath) {
    const name = path.basename(String(fileNameOrPath || ''));
    const m = /^Screenshot_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})(?:[_.]|$)/.exec(name);
    if (!m) return null;
    const [, y, mo, d, h, mi, s] = m;
    const t = new Date(Date.UTC(+y, +mo - 1, +d, +h - 3, +mi, +s));
    return Number.isNaN(t.getTime()) ? null : t;
  }

  // Prompt Ingilizce (model talimatlari daha iyi izliyor), istenen cikti
  // Turkce. Referans an olarak goruntunun CEKIM zamani verilir: goreli
  // ifadeler ("bugun/yarin/sali") ona gore cozulur, sistem saatine degil.
  //
  // `siniflandir=false`: kullanici PAYLAS menusunden "Not ekle" dedigi icin
  // kayda-degerlik sorusu ZATEN CEVAPLANMIS. "yok" dali sorulmaz — sorulsaydi
  // model kullanicinin acik kararini iptal edebilirdi. Bu, elle tetiklenen
  // yolun otomatik taramadan asil farki: pahali ve yaniltici olan kisim
  // cikarim degil, NIYET TAHMINIYDI.
  function buildPrompt(fileName, capturedAt, { siniflandir = true } = {}) {
    const at = capturedAt instanceof Date ? capturedAt : new Date();
    const iso = toIsoWithOffset(at);
    const turSatirlari = siniflandir
      ? [
        'Classify the screenshot into exactly one of:',
        '- "hatirlatici": it contains a to-do, appointment, deadline or date the owner must act on.',
        '- "bilgi": no action needed, but it holds information worth keeping and searching for later.',
        '- "yok": nothing worth keeping.',
        '',
        'The owner is a Turkish lawyer. Treat as "bilgi": people/institutions with contact details, IBAN or account numbers, addresses, case or file numbers, prices and offers, a legal or professional argument worth quoting, an explanation of a career path, procedure or regulation, book/product/place recommendations, useful how-to information.',
        'Treat as "yok": memes with no information, decontextualised chat fragments, games, weather, UI errors, app screens with no content.',
      ]
      : [
        'The owner has EXPLICITLY asked to save this as a note, so it is worth keeping. Do not judge whether it is worth keeping.',
        'Classify it into exactly one of:',
        '- "hatirlatici": it contains a to-do, appointment, deadline or date the owner must act on.',
        '- "bilgi": everything else.',
        '',
        'The owner is a Turkish lawyer. Never answer "yok".',
      ];
    return [
      // Otomatik taramada ifade AYNEN korunuyor: o yolun davranisi olculmus,
      // kelime degisikligi olcumu gecersiz kilar. Elle paylasilan sey ise
      // goruntu olmayabilir (metin, baglanti) — orada ifade genisletiliyor.
      siniflandir
        ? `Read the image file named ${fileName} (inside the workspace provided via --add-dir).`
        : `Read the file named ${fileName} (inside the workspace provided via --add-dir). It may be an image, a screenshot or a text file.`,
      '',
      `Reference moment: ${iso} (timezone ${TZ_OFFSET}). Resolve relative expressions like "today", "tomorrow", "Tuesday" against this reference moment, NOT against the system clock.`,
      '',
      ...turSatirlari,
      '',
      'Respond with ONLY a JSON object, no markdown fences, no explanation:',
      '{"tur":"hatirlatici|bilgi|yok","baslik":"...","tarih_saat":null,"alinti":null,"aciklama":"...","metin":"...","etiketler":["..."]}',
      '',
      `- baslik: short Turkish title, at most ${LIMIT_BASLIK} characters`,
      '- tarih_saat: ISO8601 with +03:00 offset, or null when there is no date the owner must act on',
      `- alinti: verbatim text the date was read from, at most ${LIMIT_ALINTI} characters, or null`,
      `- aciklama: Turkish summary, 1-3 sentences, at most ${LIMIT_ACIKLAMA} characters. State the substance itself, not "a screenshot showing...".`,
      `- metin: the meaningful visible text transcribed verbatim, at most ${LIMIT_METIN} characters. Skip the status bar, navigation bar and like/comment counts. Empty string when tur is "yok".`,
      `- etiketler: 1-${LIMIT_ETIKET} short Turkish keywords for later search`,
      '',
      'If you cannot actually open and see the image, return {"error":"cannot read image"}. Do not guess or invent.',
    ].join('\n');
  }

  // Model ciktisini savunmacı isler: fence soy, ilk { ile son } arasini al,
  // parse et, alanlari dogrula/kirp. Basarisizlikta null (throw YOK).
  function parseOutput(raw, warn, { siniflandir = true } = {}) {
    const text = String(raw || '').trim();
    if (!text) return null;
    // ```json ... ``` fence'ini soy (bazen model yine de sarabiliyor).
    const fenced = text.match(/```(?:json)?\s*([\s\S]*?)```/);
    const body = fenced ? fenced[1] : text;
    const first = body.indexOf('{');
    const last = body.lastIndexOf('}');
    if (first === -1 || last <= first) {
      warn('screenshot-extract: cikti icinde JSON bulunamadi');
      return null;
    }
    let data;
    try {
      data = JSON.parse(body.slice(first, last + 1));
    } catch (e) {
      warn(`screenshot-extract: JSON parse hatasi: ${String(e?.message || e).slice(0, 160)}`);
      return null;
    }
    if (data?.error) {
      warn(`screenshot-extract: model hatasi: ${String(data.error).slice(0, 160)}`);
      return null;
    }
    // `tur` yeni sozlesme. Eski `actionable` bicimi de kabul edilir: model
    // arada eski sekle donerse kayit bosuna dusmesin.
    let tur = String(data?.tur ?? '').trim().toLowerCase();
    if (!TURLER.has(tur)) {
      if (data?.actionable === true) tur = data?.tarih_saat ? 'hatirlatici' : 'bilgi';
      else if (data?.actionable === false) tur = 'yok';
      else {
        warn(`screenshot-extract: taninmayan tur "${String(data?.tur).slice(0, 40)}", kayit atlandi`);
        return null;
      }
    }
    // Elle tetiklenen yolda "yok" bir RET degil, modelin talimati kacirmasi:
    // kullanicinin acik karari kazanir, kayit `bilgi` olarak yazilir.
    if (tur === 'yok') {
      if (siniflandir) return null;
      tur = 'bilgi';
    }

    const baslik = String(data.baslik || '').trim();
    if (!baslik) {
      warn('screenshot-extract: baslik bos, kayit atlandi');
      return null;
    }
    const rec = {
      tur,
      baslik: baslik.slice(0, LIMIT_BASLIK),
      // Tarih YALNIZ `hatirlatici` turunde zamanlayiciya girer. `bilgi`
      // kaydinda model sozlesme/fatura tarihini de dolduruyor (07.08 olcumu:
      // sozlesme tarihi 22.07 → GECMIS tarih; tick onu "vakti gelmis" sayip
      // aninda bildirim calardi). Bilgi notunda tarih yalniz govdede kalir.
      tarih_saat: tur === 'hatirlatici' ? (data.tarih_saat ?? null) : null,
      alinti: data.alinti ? String(data.alinti).trim().slice(0, LIMIT_ALINTI) : null,
      aciklama: data.aciklama ? String(data.aciklama).trim().slice(0, LIMIT_ACIKLAMA) : null,
      metin: data.metin ? String(data.metin).trim().slice(0, LIMIT_METIN) : '',
      etiketler: Array.isArray(data.etiketler)
        ? data.etiketler.map((t) => String(t ?? '').trim()).filter(Boolean).slice(0, LIMIT_ETIKET)
        : [],
    };
    // Tarih parse edilemiyorsa alani null'a cevir, kaydi ATMA: tarihsiz
    // yapilacak is de degerli.
    if (rec.tarih_saat != null) {
      const t = new Date(rec.tarih_saat);
      if (Number.isNaN(t.getTime())) {
        warn(`screenshot-extract: gecersiz tarih_saat null'a cevrildi: ${String(rec.tarih_saat).slice(0, 80)}`);
        rec.tarih_saat = null;
      }
    }
    return rec;
  }

  async function extract(localPath, capturedAt, { siniflandir = true } = {}) {
    if (!addDir) {
      log('screenshot-extract: addDir zorunlu (agy scratch klasorune bakip uydurabilir)');
      return null;
    }
    // Referans an: verilen Date → dosya adindan cozum → mtime → sistem saati.
    const at = capturedAt instanceof Date
      ? capturedAt
      : (parseCapturedAt(localPath) || fileMtime(localPath) || new Date());
    const args = [
      '--print', buildPrompt(path.basename(localPath), at, { siniflandir }),
      `--app_data_dir=${APP_DATA_DIR}`,
      '--model', model,
      '--add-dir', addDir,
      '--mode', 'plan',
      '--dangerously-skip-permissions',
      '--print-timeout', '2m',
    ];
    let res;
    try {
      res = await run(args);
    } catch (e) {
      log(`screenshot-extract surec hatasi: ${String(e?.message || e).slice(0, 200)}`);
      return null;
    }
    // res?.err — runCommand bozuk/undefined dondururse de throw etmemeliyiz:
    // sozlesme "extract asla throw etmez, basarisizlik null'dir".
    if (!res || res.err) {
      log(`screenshot-extract agy hatasi: ${String(res?.err?.message || res?.errOut || res?.out || 'bos yanit').slice(0, 200)}`);
      return null;
    }
    return parseOutput(res.out, log, { siniflandir });
  }

  return { extract, parseCapturedAt, buildPrompt };
}
