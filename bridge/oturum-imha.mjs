// OpenCode silinmis oturum kalintilarinin imhasi.
//
// Neden kopruye bagli: opencode bir oturumu silince icerik gitmiyor —
// SQLite freelist sayfalari ve checkpoint edilmemis -wal cercevelerinde
// mesaj metni, model kimligi ve reasoning izi aynen kaliyor (30.09.2026'da
// silinmis bir gemma4 oturumu bu yolla birebir geri getirildi). Temizlik
// tek seferlik degil, her silmeden sonra kosulmasi gereken bir rutin;
// bu yuzden telefondan tek dokunusla tetiklenebilir olmali.
//
// Isin kendisi CoworkSpaces\opencode-v2\scripts\oturum-imha.ps1 icinde:
// opencode sureclerini durdurur, VACUUM + wal_checkpoint(TRUNCATE) yapar,
// loglari bosaltir, sonra YENIDEN TARAYIP dogrular, 4096 servisini geri
// kaldirir. Betik masaustunden de ayni sekilde calistirilabiliyor —
// runpod.mjs'teki desenin aynisi.
//
// Uzun is HTTP isteginde tutulmaz: cagiran /purge/run ile operasyonu
// baslatir, /purge/status ile ilerlemeyi yoklar.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawn } from 'node:child_process';

const RAPOR_TTL_MS = 60_000;
const RAPOR_TIMEOUT_MS = 3 * 60_000;
const IMHA_TIMEOUT_MS = 10 * 60_000;
const MAX_SATIR = 200;

const VARSAYILAN_BETIK = path.join(
  os.homedir(), 'CoworkSpaces', 'opencode-v2', 'scripts', 'oturum-imha.ps1');

// Betik hem insan okunur metin hem de son satirda tek satir JSON basiyor.
// Sadece JSON'a guveniyoruz; metin yalniz UI'da gosterilen ilerleme satiri.
export function sonJsonSatiri(text) {
  const lines = String(text || '').split(/\r?\n/);
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i].trim();
    if (!line.startsWith('{')) continue;
    try { return JSON.parse(line); } catch {}
  }
  return null;
}

// Telefon kartinda tek satir olarak duracak ozet.
export function imhaOzetMesaji(rapor) {
  if (!rapor) return 'Durum bilinmiyor';
  const canli = Number(rapor.oluCanli || 0);
  const artik = Number(rapor.oluArtik || 0);
  if (rapor.mod === 'uygula') {
    if (rapor.temiz) return 'Temiz — kalinti yok';
    const kalan = Number(rapor.dogrulama?.kalanOturum || 0);
    return kalan > 0 ? `${kalan} kalinti silinemedi` : 'Imha yarim kaldi';
  }
  if (canli === 0) return artik > 0
    ? `Temiz · eski dosyalarda ${artik} sohbet duruyor`
    : 'Temiz — kalinti yok';
  return `${canli} silinmis oturum izi hala okunabilir`;
}

export function createOturumImhaManager(config = {}, deps = {}) {
  const spawnImpl = deps.spawn || spawn;
  const existsSync = deps.existsSync || fs.existsSync;
  const logger = deps.logger || console;
  const now = deps.now || (() => Date.now());
  const script = String(config.script || VARSAYILAN_BETIK).trim();
  const powershell = String(config.powershell || 'powershell.exe').trim();
  const enabled = config.enabled !== false && !!script && existsSync(script);
  const disabledMessage = !script
    ? 'oturum-imha betigi yapilandirilmamis'
    : `oturum-imha.ps1 bulunamadi: ${script}`;

  let operation = null;      // { action, lines[], startedAt }
  let lastReport = null;     // rapor modunun son JSON ozeti
  let lastReportAt = 0;
  let lastResult = null;     // uygula modunun son JSON ozeti
  let lastError = '';

  const snapshot = (extra = {}) => ({
    ok: enabled && !lastError,
    enabled,
    running: !!operation,
    action: operation?.action || '',
    message: enabled
      ? (operation ? 'Imha suruyor…' : (lastError || imhaOzetMesaji(lastResult || lastReport)))
      : disabledMessage,
    // Son iki JSON ozeti ayri tutuluyor: kart "ne var" (rapor) ile "ne
    // yapildi" (sonuc) bilgisini birlikte gosteriyor.
    report: lastReport,
    result: lastResult,
    lines: operation ? operation.lines.slice(-MAX_SATIR) : [],
    updatedAt: now(),
    ...extra,
  });

  function kos(args, timeoutMs, onLine = null) {
    return new Promise((resolve) => {
      const child = spawnImpl(powershell, [
        '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
        '-File', script, ...args,
      ], { windowsHide: true, cwd: path.dirname(script), stdio: ['ignore', 'pipe', 'pipe'] });
      let stdout = '';
      let stderr = '';
      let buf = '';
      let settled = false;
      const finish = (result) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve(result);
      };
      const timer = setTimeout(() => {
        try { child.kill(); } catch {}
        finish({ ok: false, error: 'zaman asimi', stdout, stderr });
      }, timeoutMs);
      child.stdout?.on('data', (chunk) => {
        const text = String(chunk);
        stdout += text;
        if (!onLine) return;
        buf += text;
        const parts = buf.split(/\r?\n/);
        buf = parts.pop() || '';
        for (const line of parts) if (line.trim()) onLine(line.trim());
      });
      child.stderr?.on('data', (chunk) => { stderr += String(chunk); });
      child.on('error', (err) => finish({ ok: false, error: String(err?.message || err), stdout, stderr }));
      // 'close' DEGIL 'exit' dinleniyor. Betik sonunda 4096 servisini geri
      // kaldiriyor ve o servis sureci stdout borumuzu MIRAS ALIYOR; boru acik
      // kaldigi icin 'close' hic gelmiyordu ve kart bitmis isi "suruyor"
      // gostermeye devam ediyordu (30.09.2026 olculdu: is 13:55'te bitti,
      // kart 10 dk timeout'a kadar donuyordu). Ayni tuzak gradle daemon'da da
      // var. 'exit' surecin kendi bitisidir, torunlari beklemez.
      child.on('exit', (code) => {
        // Son parcalarin akmasi icin kisa bir soluk; sonra sonucu kapat.
        setTimeout(() => {
          if (buf.trim() && onLine) onLine(buf.trim());
          // Cikis kodu 2 = "kalinti var"; hata degil, dogrulama sonucu.
          finish({ ok: code === 0 || code === 2, code, stdout, stderr });
        }, 250);
      });
    });
  }

  async function report({ refresh = false } = {}) {
    if (!enabled) return snapshot();
    if (!refresh && lastReport && now() - lastReportAt < RAPOR_TTL_MS) return snapshot();
    if (operation) return snapshot();
    const r = await kos(['-Json'], RAPOR_TIMEOUT_MS);
    const ozet = sonJsonSatiri(r.stdout);
    if (ozet) {
      lastReport = ozet;
      lastReportAt = now();
      lastError = '';
    } else {
      lastError = r.error || 'rapor okunamadi';
      logger.warn?.(`[oturum-imha] rapor ayristirilamadi: ${lastError}`);
    }
    return snapshot();
  }

  function run({ eskiDb = false, snapshotSil = false, loguKoru = false, kanit = [] } = {}) {
    // Dikkat: snapshot() kendi `ok` alanini tasiyor — reddi anlatan alanlar
    // spread'den SONRA gelmeli, yoksa "zaten suruyor" cevabi ok:true doner.
    if (!enabled) return { ...snapshot(), ok: false, error: disabledMessage };
    if (operation) return { ...snapshot(), ok: false, error: 'imha zaten suruyor' };
    // -SurecleriDurdur + -ServisiBaslat sabit: calisan opencode veritabanini
    // acik tutuyor, VACUUM ya basarisiz olur ya da hemen ardindan yeni WAL
    // yazilir. Servis is bitince geri kaldiriliyor (4096 telefonun ucu).
    const args = ['-Uygula', '-SurecleriDurdur', '-ServisiBaslat', '-Json'];
    if (eskiDb) args.push('-EskiDb');
    if (snapshotSil) args.push('-Snapshot');
    if (loguKoru) args.push('-LoguKoru');
    for (const k of kanit) if (String(k || '').trim()) args.push('-Kanit', String(k));

    operation = { action: 'purge', lines: [], startedAt: now() };
    lastError = '';
    const current = operation;
    kos(args, IMHA_TIMEOUT_MS, (line) => {
      // JSON ozet satirini ilerleme listesine koymuyoruz; UI'da cop olur.
      if (!line.startsWith('{')) current.lines.push(line);
    }).then((r) => {
      const ozet = sonJsonSatiri(r.stdout);
      if (ozet) {
        lastResult = ozet;
        lastReport = ozet;
        lastReportAt = now();
      } else {
        lastError = r.error || 'imha sonucu okunamadi';
        logger.warn?.(`[oturum-imha] sonuc ayristirilamadi: ${lastError} ${r.stderr || ''}`);
      }
    }).finally(() => {
      if (operation === current) operation = null;
    });

    return { ...snapshot(), ok: true, accepted: true };
  }

  return { status: () => snapshot(), report, run, get enabled() { return enabled; } };
}
