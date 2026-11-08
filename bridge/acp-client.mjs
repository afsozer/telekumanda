// ACP (Agent Client Protocol) istemcisi — stdio üzerinden JSON-RPC 2.0.
//
// Ajana özel HİÇBİR şey bilmez: süreci başlatır, çerçeveleme yapar, istek/yanıt
// eşler, bildirimleri ve SUNUCUDAN GELEN istekleri (izin, dosya okuma) çağırana
// devreder. Antigravity'ye özgü eşleme `agy.mjs` içinde.
//
// Protokol ölçümü 08.11.2026, `agy acp` v1.5.3 (bkz. docs/acp-gecis-notlari.md):
// - Çerçeveleme satır tabanlı: her mesaj tek satır JSON + '\n'. Content-Length
//   başlığı YOK (LSP değil).
// - `session/cancel` BİLDİRİMDİR, istek değil. İstek olarak gönderilirse yanıt
//   hiç gelmez ve çağıran sonsuza kadar bekler (ölçümde yaşandı).
// - Sunucu da istek gönderir (`session/request_permission`, `fs/read_text_file`).
//   Bunlara CEVAP VERİLMEZSE tur ilerlemez; bu yüzden onRequest zorunlu.
import { spawn } from 'node:child_process';

const VARSAYILAN_ISTEK_ZAMAN_ASIMI = 10 * 60 * 1000;

export function createAcpClient({
  exe,
  args = ['acp'],
  cwd,
  env,
  onNotification = () => {},
  onRequest = async () => ({}),
  onExit = () => {},
  onStderr = () => {},
  log = () => {},
}) {
  const child = spawn(exe, args, {
    cwd: cwd || undefined,
    env: env || process.env,
    stdio: ['pipe', 'pipe', 'pipe'],
    windowsHide: true,
  });

  // Cocuk, ebeveynin olay dongusunu TUTMAMALI: kopru zaten HTTP sunucusuyla
  // ayakta kalir, ama testler ve kisa omurlu betikler bu cocuk yuzunden hic
  // cikmiyordu (olculdu: node --test 5 sn yerine 100 sn'de zorla kapandi).
  // Karsiligi: surec cikarken cocugu kendimiz oldurmek zorundayiz (kill()).
  // Olay dongusu politikasi — iki olcumun sonucu:
  // 1. Cocuk ve stdio borulari dongüyü TUTUYOR: boyle birakilinca `node --test`
  //    5 sn yerine 100 sn'de zorla kapaniyordu (cocuk hic bitmez). child.unref()
  //    tek basina yetmiyor, boru handle'lari ayri.
  // 2. Hepsini birakip unref etmek de yanlis: bekleyen tek is bir ACP istegi
  //    oldugunda Node "yapacak is yok" deyip cikiyor ve istek yarida kaliyor
  //    (olculdu: newSession "ACP istemcisi kapatildi" ile dondu).
  // Dogrusu ikisinin arasi: BEKLEYEN ISTEK VARKEN ref'li, bosta unref'li.
  const refle = () => { child.ref(); child.stdout?.ref?.(); child.stdin?.ref?.(); };
  const unrefle = () => { child.unref(); child.stdout?.unref?.(); child.stderr?.unref?.(); child.stdin?.unref?.(); };
  unrefle();

  let siradakiId = 1;
  const bekleyen = new Map();
  let tampon = '';
  let kapandi = false;

  function yaz(obj) {
    if (kapandi || !child.stdin.writable) return false;
    try {
      child.stdin.write(JSON.stringify(obj) + '\n');
      return true;
    } catch (e) {
      log(`[acp] stdin yazilamadi: ${e.message}`);
      return false;
    }
  }

  child.stdout.setEncoding('utf8');
  child.stdout.on('data', (parca) => {
    tampon += parca;
    let nl;
    while ((nl = tampon.indexOf('\n')) >= 0) {
      const satir = tampon.slice(0, nl).trim();
      tampon = tampon.slice(nl + 1);
      if (!satir) continue;
      let msg;
      try {
        msg = JSON.parse(satir);
      } catch {
        // Ajan bazen stdout'a düz metin (uyarı/banner) yazabiliyor; yutma, logla.
        log(`[acp] JSON olmayan satir: ${satir.slice(0, 200)}`);
        continue;
      }
      islemeAl(msg);
    }
  });

  function islemeAl(msg) {
    // Sunucudan gelen İSTEK: id + method birlikte.
    if (msg.id !== undefined && msg.method) {
      Promise.resolve()
        .then(() => onRequest(msg.method, msg.params || {}))
        .then(
          (result) => yaz({ jsonrpc: '2.0', id: msg.id, result: result ?? {} }),
          (err) => yaz({
            jsonrpc: '2.0',
            id: msg.id,
            error: { code: -32603, message: String(err?.message || err) },
          }),
        );
      return;
    }
    // Bildirim: method var, id yok.
    if (msg.method) {
      try { onNotification(msg.method, msg.params || {}); }
      catch (e) { log(`[acp] bildirim isleyicisi patladi: ${e.message}`); }
      return;
    }
    // Yanıt.
    if (msg.id !== undefined && bekleyen.has(msg.id)) {
      const { resolve, reject, timer } = bekleyen.get(msg.id);
      bekleyen.delete(msg.id);
      clearTimeout(timer);
      if (bekleyen.size === 0) unrefle();
      if (msg.error) {
        const e = new Error(msg.error.message || 'ACP hatasi');
        e.code = msg.error.code;
        e.data = msg.error.data;
        reject(e);
      } else {
        resolve(msg.result);
      }
    }
  }

  child.stderr.setEncoding('utf8');
  child.stderr.on('data', (d) => onStderr(String(d)));

  child.on('exit', (code, signal) => {
    kapandi = true;
    const hata = new Error(`ACP sureci kapandi (code=${code} signal=${signal})`);
    for (const [, { reject, timer }] of bekleyen) {
      clearTimeout(timer);
      reject(hata);
    }
    bekleyen.clear();
    onExit(code, signal);
  });

  child.on('error', (e) => {
    kapandi = true;
    log(`[acp] surec hatasi: ${e.message}`);
  });

  function request(method, params = {}, { timeoutMs = VARSAYILAN_ISTEK_ZAMAN_ASIMI } = {}) {
    if (kapandi) return Promise.reject(new Error('ACP sureci kapali'));
    const id = siradakiId++;
    return new Promise((resolve, reject) => {
      // Zaman aşımı sigortası: bildirim olması gereken bir metodu istek olarak
      // göndermek (ya da ajanın yanıtı düşürmesi) çağıranı sonsuza kadar
      // bekletiyordu. Sonsuz bekleme yok; süre dolunca hata.
      const timer = setTimeout(() => {
        bekleyen.delete(id);
        if (bekleyen.size === 0) unrefle();
        reject(new Error(`ACP ${method} zaman asimi (${timeoutMs} ms)`));
      }, timeoutMs);
      if (timer.unref) timer.unref();
      bekleyen.set(id, { resolve, reject, timer });
      if (bekleyen.size === 1) refle();
      if (!yaz({ jsonrpc: '2.0', id, method, params })) {
        bekleyen.delete(id);
        clearTimeout(timer);
        reject(new Error('ACP sureci stdin kabul etmiyor'));
      }
    });
  }

  function notify(method, params = {}) {
    return yaz({ jsonrpc: '2.0', method, params });
  }

  function kill() {
    if (kapandi) return;
    kapandi = true;
    for (const [, { reject, timer }] of bekleyen) {
      clearTimeout(timer);
      reject(new Error('ACP istemcisi kapatildi'));
    }
    bekleyen.clear();
    try { child.stdin.end(); } catch {}
    try { child.kill(); } catch {}
  }

  return {
    request,
    notify,
    kill,
    get pid() { return child.pid; },
    get alive() { return !kapandi; },
    get bekleyenSayisi() { return bekleyen.size; },
  };
}
