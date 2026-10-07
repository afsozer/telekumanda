// Minimal CDP sürücüsü — chrome-devtools MCP work profilinde kayıtlı olmadığı
// için elle. tarayici-debug.ps1'in açtığı 9222 portuna bağlanır.
//
//   node scripts/cdp.mjs open <url>
//   node scripts/cdp.mjs eval "<js ifadesi>"      -> JSON sonuç
//   node scripts/cdp.mjs shot <dosya> [genişlik] [yükseklik]
//   node scripts/cdp.mjs console                  -> biriken konsol mesajları
//   node scripts/cdp.mjs close

import WebSocket from '../bridge/node_modules/ws/index.js';

const PORT = 9222;
const BASE = `http://127.0.0.1:${PORT}`;

async function targets() {
  const res = await fetch(`${BASE}/json/list`);
  return (await res.json()).filter(t => t.type === 'page');
}

async function firstPage() {
  let list = await targets();
  if (!list.length) {
    await fetch(`${BASE}/json/new?about:blank`, { method: 'PUT' }).catch(() => {});
    list = await targets();
  }
  if (!list.length) throw new Error('sayfa hedefi yok');
  return list[0];
}

function connect(wsUrl) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl, { maxPayload: 256 * 1024 * 1024 });
    let id = 0;
    const pending = new Map();
    const events = [];
    ws.on('open', () => resolve({
      ws,
      events,
      send(method, params = {}) {
        const msgId = ++id;
        ws.send(JSON.stringify({ id: msgId, method, params }));
        return new Promise((res, rej) => pending.set(msgId, { res, rej }));
      },
      close: () => ws.close(),
    }));
    ws.on('error', reject);
    ws.on('message', raw => {
      const msg = JSON.parse(raw);
      if (msg.id && pending.has(msg.id)) {
        const { res, rej } = pending.get(msg.id);
        pending.delete(msg.id);
        msg.error ? rej(new Error(JSON.stringify(msg.error))) : res(msg.result);
      } else if (msg.method) {
        events.push(msg);
      }
    });
  });
}

const [cmd, ...args] = process.argv.slice(2);
const page = await firstPage();
const cdp = await connect(page.webSocketDebuggerUrl);

try {
  if (cmd === 'open') {
    await cdp.send('Page.enable');
    await cdp.send('Runtime.enable');
    await cdp.send('Page.navigate', { url: args[0] });
    // Yükleme + ilk render için kısa bekleme; SPA olduğu için load yetmiyor.
    await new Promise(r => setTimeout(r, Number(args[1] || 2500)));
    const t = await cdp.send('Runtime.evaluate', { expression: 'document.title', returnByValue: true });
    console.log('başlık:', t.result.value);
  } else if (cmd === 'eval') {
    const r = await cdp.send('Runtime.evaluate', {
      expression: args[0],
      returnByValue: true,
      awaitPromise: true,
    });
    if (r.exceptionDetails) {
      console.error('HATA:', r.exceptionDetails.text, r.exceptionDetails.exception?.description || '');
      process.exitCode = 1;
    } else {
      const v = r.result.value;
      console.log(typeof v === 'string' ? v : JSON.stringify(v, null, 2));
    }
  } else if (cmd === 'shot') {
    const [file, w, h] = args;
    if (w && h) {
      await cdp.send('Emulation.setDeviceMetricsOverride', {
        width: Number(w), height: Number(h), deviceScaleFactor: 1, mobile: false,
      });
      await new Promise(r => setTimeout(r, 600));
    }
    const { data } = await cdp.send('Page.captureScreenshot', { format: 'png' });
    const fs = await import('node:fs');
    fs.writeFileSync(file, Buffer.from(data, 'base64'));
    if (w && h) await cdp.send('Emulation.clearDeviceMetricsOverride');
    console.log('yazıldı:', file);
  } else if (cmd === 'console') {
    await cdp.send('Runtime.enable');
    await cdp.send('Log.enable');
    await new Promise(r => setTimeout(r, Number(args[0] || 1500)));
    const out = cdp.events
      .filter(e => e.method === 'Runtime.consoleAPICalled' || e.method === 'Log.entryAdded')
      .map(e => e.method === 'Log.entryAdded'
        ? `[${e.params.entry.level}] ${e.params.entry.text}`
        : `[${e.params.type}] ${e.params.args.map(a => a.value ?? a.description ?? '').join(' ')}`);
    console.log(out.length ? out.join('\n') : '(konsol temiz)');
  } else if (cmd === 'close') {
    await fetch(`${BASE}/json/close/${page.id}`);
    console.log('sekme kapatıldı');
  } else {
    console.error('bilinmeyen komut:', cmd);
    process.exitCode = 1;
  }
} finally {
  cdp.close();
}
