// Merkezi dosya logger'ı (madde 14).
//
// Eskiden run-bridge.cmd içindeki `node server.mjs >> bridge.log 2>&1` CMD
// redirect'i tüm node stdout/stderr'ini bridge.log'a basıyordu. Bu redirect
// bridge.log'a paylaşımsız bir Windows HANDLE tuttuğu için Node içinden
// dosyayı rename edemiyor (EBUSY) ve boyut-tabanlı rotasyon imkânsız oluyordu;
// dosya 44,8 MB'a kadar büyüdü.
//
// Bu modül log yazımını Node tarafına taşır: console.log/warn/error her satırı
// fs.appendFileSync ile bridge.log'a yazar (sync → her satır anında diskte,
// rotasyon sırasında buffer bekleme yok). Böylece rotasyon sırasında dosya
// rename edilebilir ve yeni boş dosyaya devam edilir — madde 14'ün "akış
// kapatılıp yeniden açılır" gereksinimi (stream olmasa da etki aynı: dosya
// rename edilir, append bir sonraki satırda yeni dosyaya gider).
//
// run-bridge.cmd artık çıktıyı bootstrap.log'a yönlendirir; bu dosya yalnızca
// logger devreye girmeden ÖNCE ölen erken hataları (node.exe bulunamadı,
// server.mjs sözdizimi hatası) yakalar. Normal koşulda hep boş kalır.
//
// Mevcut `logWarn` (session-utils.mjs:100) ve tüm console.* çağrıları
// değiştirilmeden çalışır: bu override util.format ile orijinal formatı
// (space-join) korur ve başına `ISO_TS [level] ` öneki ekler.

import fs from 'node:fs';
import util from 'node:util';
import { rotateLogIfNeeded } from './log-rotation.mjs';

// Aktif logger durumu. initLogger çağrılana kadar null.
let state = null;

// Orijinal console metodlarını sakla — resetLoggerForTest ve güvenlik ağı için.
const origConsole = {
  log: console.log,
  warn: console.warn,
  error: console.error,
};

export function maskToken(str) {
  if (typeof str !== 'string') return str;
  return str
    .replace(/(token|ticket)=([^&\s]+)/g, '$1=***')
    .replace(/(Bearer\s+)[A-Za-z0-9._~+/=-]+/gi, '$1***')
    .replace(/\b(abk|tkt)_[A-Za-z0-9_-]{16,}/g, '$1_***');
}

/**
 * console.log/warn/error'ı bridge.log'a (append, sync) yönlendirir. Diğer
 * modüllerin import edilmesinden ÖNCE çağrılmalı (top-level console çağrısı
 * yoktur; runtime çağrıları yakalanır).
 *
 * @param {{filePath: string, maxBytes?: number, maxGenerations?: number}} opts
 */
export function initLogger({ filePath, maxBytes, maxGenerations } = {}) {
  state = {
    filePath,
    maxBytes: maxBytes ?? 5 * 1024 * 1024,
    maxGenerations: maxGenerations ?? 3,
  };

  const writeLine = (level, args) => {
    if (!state) return; // reset sonrası güvenlik ağı
    const ts = new Date().toISOString();
    let body = util.format(...args);
    body = maskToken(body);
    const line = `${ts} [${level}] ${body}\n`;
    try { fs.appendFileSync(state.filePath, line); } catch {}
  };

  console.log = (...args) => writeLine('log', args);
  console.warn = (...args) => writeLine('warn', args);
  console.error = (...args) => writeLine('error', args);
}

/**
 * Aktif log dosyasını rotate eder (log-rotation.mjs saf fonksiyonu). append
 * modu sync olduğu için kapatılacak stream yoktur: rename güvenle yapılır ve
 * bir sonraki console.* çağrısı yeni boş dosyaya yazar. sunucu açılışında ve
 * 10 dakikada bir çağrılır. logger init edilmediyse hiçbir şey yapmaz.
 */
export function rotate() {
  if (!state) return { rotated: false };
  return rotateLogIfNeeded(state.filePath, {
    maxBytes: state.maxBytes,
    maxGenerations: state.maxGenerations,
  });
}

/**
 * console.* override'larını kaldırır ve state'i temizler. Yalnızca testlerde
 * çağrılır; normal çalışmada logger ömrü boyu aktif kalır.
 */
export function resetLoggerForTest() {
  console.log = origConsole.log;
  console.warn = origConsole.warn;
  console.error = origConsole.error;
  state = null;
}
