// Antigravity backend'i — `agy acp` (Agent Client Protocol) üzerinden.
//
// 08.11.2026: eski PTY'li kurulum (TUI sürme + transcript.jsonl ayrıştırma +
// her turda kill/respawn + trust promptuna otomatik "1") tamamen söküldü.
// Gerekçe ve ölçümler: docs/acp-gecis-notlari.md. Özet:
// - Tur sonu artık heuristik değil: `session/prompt` yanıtı `stopReason` ile döner.
// - İçerik transcript'ten kazınmıyor: `session/update` bildirimleri akıyor.
// - Oturum listesi/devralma protokolde: `session/list` + `session/load`.
// - Onay protokolde: sunucu `session/request_permission` İSTEĞİ gönderiyor;
//   cevap gelene kadar tur bekler. Eski kurulumda bu yalnız kısmi (trust) idi.
//
// TEK SÜREÇ, ÇOK OTURUM: ACP bağlantısı birden çok oturum taşıyabildiği için
// tüm oturumlar tek `agy acp` çocuğunu paylaşır; `cwd` oturum başına
// `session/new` parametresiyle geçer. Süreç ölürse yeniden doğar ve oturumlar
// `session/load` ile geri alınır.
//
// KİMLİK: oturum kimliğimiz ACP'nin verdiği `sessionId`'nin kendisidir. Aynı
// kimlik `session/list` çıktısında da döndüğü için canlı oturum ile diskteki
// kayıt aynı şeydir; eski koddaki ayrı "disk oturumu → adopt" eşlemesi gerekmiyor.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createAcpClient } from './acp-client.mjs';
import {
  createAgentSessionCore,
  ensureMessageRowIds,
  paginateSessionMessages,
} from './agent-session-core.mjs';

export const AGY_EXE = path.join(process.env.LOCALAPPDATA || '', 'agy', 'bin', 'agy.exe');

// `agy models` çıktısıyla hizalı statik yedek. ASIL kaynak artık protokol:
// `session/new` yanıtı `models.availableModels` taşıyor ve oturum açılınca
// katalog oradan tazelenir. Bu liste yalnız hiç oturum açılmadan /agy/models
// sorulursa kullanılır (telefon kurulum ekranı).
export const AGY_MODELS = [
  { label: 'Gemini 3.8 Flash High', id: 'gemini-3.8-flash-high' },
  { label: 'Gemini 3.8 Flash Medium', id: 'gemini-3.8-flash-medium' },
  { label: 'Gemini 3.8 Flash Low', id: 'gemini-3.8-flash-low' },
  { label: 'Claude Sonnet 4.6', id: 'claude-sonnet-4-6' },
  { label: 'Claude Opus 4.6', id: 'claude-opus-4-6-thinking' },
  { label: 'GPT-OSS 120B Medium', id: 'gpt-oss-120b-medium' },
];
export const MODELS = AGY_MODELS;

// ACP `modes` çıktısıyla birebir (ölçüldü): izin kipi seçicisi bunları gösterir.
export const PERMISSION_MODES = [
  { id: 'default', name: 'Default', description: 'Düzenleme ve komutlarda onay ister' },
  { id: 'accept-edits', name: 'Accept edits', description: 'Çalışma alanındaki düzenlemeleri sormadan uygular' },
  { id: 'plan', name: 'Plan', description: 'Önce plan yapar, sonra uygular' },
];

const MESAJ_TAVANI = 400;
const DOSYA_OKUMA_TAVANI = 2 * 1024 * 1024;

const sessions = new Map();
// Süreç havuzu, ÇALIŞMA DİZİNİ başına bir `agy acp` çocuğu: cwd → istemci.
//
// Neden tek ortak süreç değil (08.11.2026, canlı ölçüm): tek çocuğu `homedir`'de
// açıp çalışma dizinini yalnız `session/new`'in `cwd` parametresiyle geçince ajan
// "bu klasör" dediğinde SÜREÇ cwd'sini kullanıyor ve dosyayı ev dizinine yazdı
// (`C:\Users\Sozer\deneme.txt`). Yani `session/new.cwd` çalışma alanını bildiriyor
// ama araçların göreli yol çözümünü değiştirmiyor. Çocuğu hedef dizinde başlatmak
// ikisini hizalar; aynı dizindeki oturumlar yine tek çocuğu paylaşır.
const istemciler = new Map();
let modelKatalogu = null;   // protokolden gelen taze katalog
let sonHata = '';

function log(msg) { console.warn(msg); }

function cwdAnahtari(dir) {
  return path.resolve(dir || os.homedir()).toLowerCase();
}

// Bosta biçme: oturum listesini görmek için bile süreç açılıyor (session/list);
// o dizine ait oturum kalmadıysa çocuğun sonsuza kadar yaşaması gereksiz. 5 dk
// sessizlikten sonra kapanır, sonraki istekte yeniden açılır (el sıkışma ~1,5 sn).
const BOSTA_OLDUR_MS = 5 * 60 * 1000;
let bostaZaman = null;

function bostaZamanlayiciKur() {
  if (bostaZaman) return;
  bostaZaman = setInterval(() => {
    for (const [anahtar, c] of [...istemciler]) {
      if (!c.alive) { istemciler.delete(anahtar); continue; }
      const kullanan = [...sessions.values()].some((s) => s.cwdKey === anahtar);
      if (kullanan) continue;
      if (Date.now() - (c.sonKullanim || 0) < BOSTA_OLDUR_MS) continue;
      log(`[agy] bosta kalan acp sureci kapatiliyor (${anahtar})`);
      c.kill();
      istemciler.delete(anahtar);
    }
  }, 60 * 1000);
  if (bostaZaman.unref) bostaZaman.unref();
}

// unref'li çocuk ebeveyn çıkarken ÖLMEZ; yetim agy.exe bırakmamak için kapat.
let cikisKancasiKurulu = false;
function cikisKancasiKur() {
  if (cikisKancasiKurulu) return;
  cikisKancasiKurulu = true;
  const kapat = () => { for (const c of istemciler.values()) { try { c.kill(); } catch {} } };
  process.on('exit', kapat);
  process.once('SIGINT', () => { kapat(); process.exit(130); });
  process.once('SIGTERM', () => { kapat(); process.exit(143); });
}

// ── Süreç havuzu ─────────────────────────────────────────────────────────────

function istemciAl(cwd) {
  const anahtar = cwdAnahtari(cwd);
  const mevcut = istemciler.get(anahtar);
  if (mevcut && mevcut.alive) { mevcut.sonKullanim = Date.now(); return mevcut; }
  if (!fs.existsSync(AGY_EXE)) {
    sonHata = 'agy.exe bulunamadi: ' + AGY_EXE;
    return null;
  }
  const c = createAcpClient({
    exe: AGY_EXE,
    args: ['acp'],
    cwd: path.resolve(cwd || os.homedir()),
    onNotification: bildirimIsle,
    onRequest: sunucuIstegiIsle,
    onStderr: (d) => { const t = d.trim(); if (t) log(`[agy acp stderr] ${t.slice(0, 400)}`); },
    onExit: (code, signal) => {
      log(`[agy] acp sureci kapandi (${anahtar}, code=${code} signal=${signal})`);
      istemciler.delete(anahtar);
      // Bu çocuğa ait açık turları kapat: yanıt hiç gelmeyeceği için "koşuyor"
      // takılı kalmasın.
      for (const s of sessions.values()) {
        if (s.cwdKey !== anahtar) continue;
        if (s.status === 'running' || s.turnActive) {
          s.status = 'error';
          s.turnActive = false;
          mesajEkle(s, 'system', 'Antigravity ACP sureci kapandi; tur yarida kaldi.');
          itele(s);
        }
      }
    },
    log,
  });
  c.initPromise = null;
  c.sonKullanim = Date.now();
  istemciler.set(anahtar, c);
  return c;
}

async function elSikis(cwd) {
  bostaZamanlayiciKur();
  cikisKancasiKur();
  const c = istemciAl(cwd);
  if (!c) throw new Error(sonHata || 'agy baslatilamadi');
  if (!c.initPromise) {
    c.initPromise = c.request('initialize', {
      protocolVersion: 1,
      clientCapabilities: {
        fs: { readTextFile: true, writeTextFile: false },
        terminal: false,
      },
    }, { timeoutMs: 30000 }).catch((e) => {
      c.initPromise = null;
      throw e;
    });
  }
  await c.initPromise;
  return c;
}

// ── Oturum kayıtları ─────────────────────────────────────────────────────────

function yeniKayit({ id, cwd, model, permissionMode }) {
  return {
    id,
    cwd,
    cwdKey: cwdAnahtari(cwd),
    model: model || '',
    permissionMode: permissionMode || 'default',
    status: 'idle',
    title: '',
    messages: [],
    toolDetails: [],
    turnActive: false,
    awaitingFirstOutput: false,
    pendingApproval: null,   // telefona gidecek onay kartı
    _approvalResolve: null,  // ACP istegini cozen fonksiyon
    contextTokens: 0,
    contextWindow: 0,
    availableCommands: [],
    subscribers: new Set(),
    lastActivity: Date.now(),
    _toolRows: new Map(),    // toolCallId -> mesaj indeksi
    initialized: true,
  };
}

function snapshot(s) {
  ensureMessageRowIds(s);
  return {
    type: 'conversation',
    sessionId: s.id,
    messages: s.messages,
    running: s.status === 'running' || s.turnActive,
    awaitingApproval: !!s.pendingApproval,
    awaitingFirstOutput: !!s.awaitingFirstOutput,
    approval: s.pendingApproval,
    pendingPrompt: s.pendingApproval,
    contextTokens: s.contextTokens || 0,
    contextWindow: s.contextWindow || 0,
    permissionMode: s.permissionMode || 'default',
  };
}

const sessionCore = createAgentSessionCore({ snapshot });

function capSess(s) {
  if (s.messages.length <= MESAJ_TAVANI) return;
  const atilan = s.messages.splice(0, s.messages.length - MESAJ_TAVANI);
  // rowId'siz bir satır kırpıldıysa diff kimliği kayar; bir sonraki push tam
  // snapshot olarak gitsin (claude/codex/omp'deki aynı sigorta).
  if (atilan.some((m) => !m?.rowId)) s._deltaDirty = true;
  // Satır indeksleri kaydı için araç eşlemesi de kaydırılır.
  const kayma = atilan.length;
  const yeni = new Map();
  for (const [k, i] of s._toolRows) if (i - kayma >= 0) yeni.set(k, i - kayma);
  s._toolRows = yeni;
}

function itele(s) {
  capSess(s);
  s.lastActivity = Date.now();
  sessionCore.pushSnapshot(s);
}

function iteleYumusak(s) {
  capSess(s);
  s.lastActivity = Date.now();
  sessionCore.throttledPush(s);
}

function mesajEkle(s, role, text, extra = {}) {
  s.messages.push({ role, text: String(text ?? ''), time: new Date().toISOString(), ...extra });
  return s.messages.length - 1;
}

// Son mesaj aynı rolden akıyorsa ona ekle (chunk'lar parça parça geliyor).
function akanMetinEkle(s, role, parca) {
  const son = s.messages[s.messages.length - 1];
  if (son && son.role === role && son._streaming) {
    son.text += parca;
    return s.messages.length - 1;
  }
  const i = mesajEkle(s, role, parca);
  s.messages[i]._streaming = true;
  return i;
}

function akisiKapat(s) {
  for (const m of s.messages) if (m._streaming) delete m._streaming;
}

// ── Bildirim eşlemesi (session/update) ───────────────────────────────────────

function bildirimIsle(method, params) {
  if (method !== 'session/update') return;
  const s = sessions.get(params.sessionId);
  if (!s) return;
  const u = params.update || {};
  switch (u.sessionUpdate) {
    case 'user_message_chunk':
      // Kendi gönderdiğimiz istem geri yankılanıyor; prompt() zaten ekledi.
      break;
    case 'agent_message_chunk':
      s.awaitingFirstOutput = false;
      akanMetinEkle(s, 'agent', metinCikar(u.content));
      iteleYumusak(s);
      break;
    case 'agent_thought_chunk': {
      // Düşünce ayrı satır değil: son araç/ajan satırının detayına yazılır,
      // telefon "düşünce" kartını GET /agy/thought ile oradan okur.
      s.awaitingFirstOutput = false;
      const i = Math.max(0, s.messages.length - 1);
      s.toolDetails[i] = (s.toolDetails[i] || '') + metinCikar(u.content);
      break;
    }
    case 'tool_call': {
      const i = mesajEkle(s, 'tool', aracBasligi(u), { tool: u.name || '', status: u.status || '' });
      s._toolRows.set(u.toolCallId, i);
      s.toolDetails[i] = aracDetayi(u);
      s.awaitingFirstOutput = false;
      itele(s);
      break;
    }
    case 'tool_call_update': {
      const i = s._toolRows.get(u.toolCallId);
      if (i === undefined || !s.messages[i]) break;
      s.messages[i].text = aracBasligi(u);
      s.messages[i].status = u.status || '';
      s.toolDetails[i] = aracDetayi(u);
      itele(s);
      break;
    }
    case 'session_info_update':
      if (u.title) s.title = u.title;
      break;
    case 'current_mode_update':
      if (u.currentModeId) s.permissionMode = u.currentModeId;
      itele(s);
      break;
    case 'available_commands_update':
      s.availableCommands = (u.availableCommands || []).map((c) => ({
        name: c.name, desc: c.description || '',
      }));
      break;
    case 'config_option_update':
      katalogGuncelle(u.configOptions);
      break;
    default:
      break;
  }
}

function metinCikar(content) {
  if (!content) return '';
  if (typeof content === 'string') return content;
  if (content.type === 'text') return String(content.text || '');
  if (content.type === 'content') return metinCikar(content.content);
  return '';
}

function aracBasligi(u) {
  const ad = u.name || 'arac';
  const baslik = u.title || '';
  const durum = u.status === 'pending' ? ' (onay bekliyor)'
    : u.status === 'failed' ? ' (hata)'
    : '';
  return baslik ? `${ad}: ${baslik}${durum}` : `${ad}${durum}`;
}

function aracDetayi(u) {
  const parcalar = [];
  if (u.rawInput) parcalar.push('girdi:\n' + guvenliJson(u.rawInput));
  for (const c of u.content || []) {
    if (c.type === 'diff') parcalar.push(`diff ${c.path}:\n${c.newText ?? ''}`);
    else if (c.type === 'content') parcalar.push(metinCikar(c));
  }
  if (u.rawOutput && Object.keys(u.rawOutput).length) parcalar.push('cikti:\n' + guvenliJson(u.rawOutput));
  return parcalar.join('\n\n').slice(0, 20000);
}

function guvenliJson(v) {
  try { return JSON.stringify(v, null, 2).slice(0, 8000); } catch { return String(v).slice(0, 2000); }
}

function katalogGuncelle(configOptions) {
  const model = (configOptions || []).find((o) => o.id === 'model');
  if (model?.options?.length) {
    modelKatalogu = model.options.map((o) => ({ id: o.value, label: o.name || o.value }));
  }
}

// ── Sunucudan gelen istekler ─────────────────────────────────────────────────

async function sunucuIstegiIsle(method, params) {
  if (method === 'session/request_permission') return izinIstegi(params);
  if (method === 'fs/read_text_file') {
    try {
      const st = fs.statSync(params.path);
      if (st.size > DOSYA_OKUMA_TAVANI) return { content: '' };
      return { content: fs.readFileSync(params.path, 'utf8') };
    } catch {
      return { content: '' };
    }
  }
  // Bilinmeyen isteğe BOŞ nesne dönmek turu askıda bırakmaktan iyidir.
  log(`[agy] bilinmeyen sunucu istegi: ${method}`);
  return {};
}

// Onay: tur, biz cevap verene kadar BEKLER. Kartı snapshot'a koyup Promise'i
// açık tutuyoruz; respond() çözer. Oturum ölürse reddederek turu serbest bırak.
function izinIstegi(params) {
  const s = sessions.get(params.sessionId);
  const secenekler = (params.options || []).map((o) => ({
    id: o.optionId, name: o.name || o.optionId, kind: o.kind || '',
  }));
  if (!s) {
    const reddet = secenekler.find((o) => o.kind.includes('reject')) || secenekler[1] || secenekler[0];
    return { outcome: { outcome: 'selected', optionId: reddet?.id } };
  }
  const tc = params.toolCall || {};
  s.pendingApproval = {
    toolCallId: tc.toolCallId || '',
    tool: tc.name || '',
    title: tc.title || tc.name || 'Onay',
    kind: tc.kind || '',
    detail: aracDetayi(tc),
    options: secenekler,
    // Telefondaki eski onay kartı iki seçenekli: evet/hayır eşlemesi hazır gelsin.
    allowId: (secenekler.find((o) => o.kind.includes('allow')) || secenekler[0])?.id || '',
    denyId: (secenekler.find((o) => o.kind.includes('reject')) || secenekler[1])?.id || '',
  };
  itele(s);
  return new Promise((resolve) => {
    s._approvalResolve = (optionId) => {
      s._approvalResolve = null;
      s.pendingApproval = null;
      itele(s);
      resolve({ outcome: { outcome: 'selected', optionId } });
    };
  });
}

export function respond(sessionId, answer) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'not found' };
  if (!s._approvalResolve) return { ok: false, error: 'bekleyen onay yok' };
  const p = s.pendingApproval || {};
  // Telefon "yes"/"no" ya da doğrudan optionId gönderebilir.
  const ham = String(answer ?? '').trim();
  const olumlu = /^(y|yes|evet|allow|onayla|true|1)$/i.test(ham);
  const olumsuz = /^(n|no|hayir|hayır|deny|reddet|false|0)$/i.test(ham);
  const optionId = olumlu ? p.allowId : olumsuz ? p.denyId : (ham || p.denyId);
  s._approvalResolve(optionId);
  return { ok: true, optionId };
}

export function getPendingApproval(sessionId) {
  const s = sessions.get(sessionId);
  return s?.pendingApproval || null;
}

// ── Sözleşme yüzeyi ──────────────────────────────────────────────────────────

export async function newSession({ cwd, model, permissionMode }) {
  let dir = cwd && String(cwd).trim() ? String(cwd).trim() : os.homedir();
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) {
    return { ok: false, error: 'cwd not a directory: ' + dir };
  }
  let c;
  try { c = await elSikis(dir); }
  catch (e) { return { ok: false, error: String(e.message || e) }; }

  let sonuc;
  try {
    sonuc = await c.request('session/new', { cwd: dir, mcpServers: [] }, { timeoutMs: 60000 });
  } catch (e) {
    return { ok: false, error: 'session/new: ' + String(e.message || e) };
  }
  const id = sonuc?.sessionId;
  if (!id) return { ok: false, error: 'session/new sessionId dondurmedi' };

  if (sonuc.models?.availableModels?.length) {
    modelKatalogu = sonuc.models.availableModels.map((m) => ({ id: m.modelId, label: m.name || m.modelId }));
  }
  katalogGuncelle(sonuc.configOptions);

  const s = yeniKayit({
    id,
    cwd: dir,
    model: model || sonuc.models?.currentModelId || '',
    permissionMode: permissionMode || sonuc.modes?.currentModeId || 'default',
  });
  sessions.set(id, s);

  if (model) await modelAyarla(id, model).catch(() => {});
  if (permissionMode) await kipAyarla(id, permissionMode).catch(() => {});

  return { ok: true, sessionId: id, cwd: dir, model: s.model, permissionMode: s.permissionMode };
}

export async function prompt({ sessionId, text, model, permissionMode }) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'not found' };
  if (s.turnActive) return { ok: false, error: 'tur zaten koşuyor' };
  let c;
  try { c = await elSikis(s.cwd); }
  catch (e) { return { ok: false, error: String(e.message || e) }; }

  if (model && model !== s.model) await modelAyarla(sessionId, model).catch(() => {});
  if (permissionMode && permissionMode !== s.permissionMode) await kipAyarla(sessionId, permissionMode).catch(() => {});

  mesajEkle(s, 'user', text);
  s.status = 'running';
  s.turnActive = true;
  s.awaitingFirstOutput = true;
  itele(s);

  // Tur, ACP isteğinin yanıtıyla biter — heuristik yok.
  c.request('session/prompt', {
    sessionId,
    prompt: [{ type: 'text', text: String(text ?? '') }],
  }).then(
    (r) => {
      const u = r?.usage || {};
      if (Number.isFinite(u.totalTokens)) s.contextTokens = u.totalTokens;
      s.status = r?.stopReason === 'refusal' ? 'error' : 'idle';
      s.turnActive = false;
      s.awaitingFirstOutput = false;
      akisiKapat(s);
      if (r?.stopReason && r.stopReason !== 'end_turn' && r.stopReason !== 'cancelled') {
        mesajEkle(s, 'system', `Tur bitti: ${r.stopReason}`);
      }
      sessionCore.flushThrottle?.(s);
      itele(s);
      sessionCore.sendEnd(s);
    },
    (e) => {
      s.status = 'error';
      s.turnActive = false;
      s.awaitingFirstOutput = false;
      akisiKapat(s);
      mesajEkle(s, 'system', 'Hata: ' + String(e.message || e));
      itele(s);
      sessionCore.sendEnd(s);
    },
  );

  return { ok: true };
}

export function getConversation(sessionId, opts = {}) {
  const s = sessions.get(sessionId);
  if (!s) {
    return {
      messages: [], running: false, awaitingApproval: false, pendingPrompt: null,
      awaitingFirstOutput: false, contextTokens: 0, contextWindow: 0,
    };
  }
  const base = snapshot(s);
  if (opts.before || opts.limit) {
    return { ...base, messages: paginateSessionMessages(s, opts) };
  }
  return base;
}

export function getThought(sessionId, i) {
  const s = sessions.get(sessionId);
  if (!s) return { text: '' };
  return { text: (s.toolDetails && s.toolDetails[i]) || '' };
}

export async function stop(sessionId) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'not found' };
  // session/cancel BİLDİRİMDİR (ölçüldü): istek olarak gönderilirse yanıt gelmez.
  const c = istemciler.get(s.cwdKey);
  if (c?.alive) c.notify('session/cancel', { sessionId });
  // Bekleyen onay varsa reddederek turu serbest bırak, yoksa iptal askıda kalır.
  if (s._approvalResolve) s._approvalResolve(s.pendingApproval?.denyId || '');
  s.turnActive = false;
  s.status = 'idle';
  akisiKapat(s);
  itele(s);
  return { ok: true };
}

export function listSessions() {
  return [...sessions.values()].map((s) => ({
    id: s.id,
    cwd: s.cwd,
    model: s.model,
    status: s.status,
    title: s.title,
    turns: s.messages.filter((m) => m.role === 'user').length,
    lastText: [...s.messages].reverse().find((m) => m.role === 'agent')?.text?.slice(0, 200) || '',
    lastActivity: s.lastActivity,
    awaitingApproval: !!s.pendingApproval,
  }));
}

export function runningSessionId() {
  for (const s of sessions.values()) if (s.status === 'running' || s.turnActive) return s.id;
  return null;
}

export function defaultModel() {
  return (modelKatalogu || AGY_MODELS)[0]?.id || '';
}

export function getModels() {
  return modelKatalogu || AGY_MODELS;
}

async function modelAyarla(sessionId, modelId) {
  const c = await elSikis(sessions.get(sessionId)?.cwd);
  await c.request('session/set_model', { sessionId, modelId }, { timeoutMs: 20000 });
  const s = sessions.get(sessionId);
  if (s) { s.model = modelId; itele(s); }
  return { ok: true, model: modelId };
}

async function kipAyarla(sessionId, modeId) {
  const c = await elSikis(sessions.get(sessionId)?.cwd);
  await c.request('session/set_mode', { sessionId, modeId }, { timeoutMs: 20000 });
  const s = sessions.get(sessionId);
  if (s) { s.permissionMode = modeId; itele(s); }
  return { ok: true, permissionMode: modeId };
}

export async function setModel({ sessionId, model }) {
  try { return await modelAyarla(sessionId, model); }
  catch (e) { return { ok: false, error: String(e.message || e) }; }
}

export async function setPermissionMode({ sessionId, permissionMode }) {
  try { return await kipAyarla(sessionId, permissionMode); }
  catch (e) { return { ok: false, error: String(e.message || e) }; }
}

// Disk oturumları = protokolün kendi listesi. `cwd` alanı ölçümde İSTEMCİNİN
// cwd'siyle dönüyordu (oturumun açıldığı dizin değil); bu yüzden listedeki cwd
// bilgisine güvenmiyoruz, adopt sırasında çağıranın verdiği cwd esas alınır.
export async function listDiskSessions({ cwd } = {}) {
  let c;
  try {
    // session/list GLOBAL (olculdu: baska dizinlerde acilmis oturumlar da donuyor),
    // o yuzden yeni surec acmak yerine ayakta olan herhangi birini kullan.
    const ayakta = [...istemciler.values()].find((x) => x.alive && x.initPromise);
    c = ayakta || await elSikis(cwd);
  } catch (e) { return { ok: false, error: String(e.message || e), sessions: [] }; }
  try {
    const r = await c.request('session/list', {}, { timeoutMs: 30000 });
    const liste = (r?.sessions || []).map((x) => ({
      id: x.sessionId,
      cwd: x.cwd || '',
      title: x.title || '',
      lastText: '',
      turns: 0,
      mtime: Date.parse(x.updatedAt || '') || 0,
    }));
    liste.sort((a, b) => b.mtime - a.mtime);
    return { ok: true, sessions: liste };
  } catch (e) {
    return { ok: false, error: String(e.message || e), sessions: [] };
  }
}

// session/load geçmişi `tool_call` / mesaj bildirimleri olarak YENİDEN OYNATIR
// (ölçüldü): bu yüzden kaydı önce kurup sonra load çağırıyoruz, bildirimler
// doğrudan mesaj listesine yazılsın.
export async function adoptSession({ id, cwd }) {
  if (!id) return { ok: false, error: 'id required' };
  const mevcut = sessions.get(id);
  if (mevcut) return { ok: true, sessionId: id, cwd: mevcut.cwd, model: mevcut.model };
  let dir = cwd && String(cwd).trim() ? String(cwd).trim() : os.homedir();
  if (!fs.existsSync(dir)) return { ok: false, error: 'cwd not found: ' + dir };
  let c;
  try { c = await elSikis(dir); }
  catch (e) { return { ok: false, error: String(e.message || e) }; }

  const s = yeniKayit({ id, cwd: dir });
  sessions.set(id, s);
  try {
    const r = await c.request('session/load', { sessionId: id, cwd: dir, mcpServers: [] }, { timeoutMs: 120000 });
    if (r?.models?.currentModelId) s.model = r.models.currentModelId;
    if (r?.modes?.currentModeId) s.permissionMode = r.modes.currentModeId;
    akisiKapat(s);
    itele(s);
    return { ok: true, sessionId: id, cwd: dir, model: s.model };
  } catch (e) {
    sessions.delete(id);
    return { ok: false, error: 'session/load: ' + String(e.message || e) };
  }
}

export function getSlashCommands(sessionId) {
  if (sessionId) {
    const s = sessions.get(sessionId);
    if (s?.availableCommands?.length) return s.availableCommands;
  }
  for (const s of sessions.values()) if (s.availableCommands.length) return s.availableCommands;
  return [];
}

export function subscribe(sessionId, ws, opts = {}) {
  sessionCore.subscribe(sessions.get(sessionId), ws, opts);
}

// ── Süreç yönetimi ───────────────────────────────────────────────────────────
// Tek bir `agy acp` çocuğu var; "süreçler" listesi onu bildirir.

export function listLiveProcesses() {
  const processes = [];
  for (const [anahtar, c] of istemciler) {
    if (!c.alive) continue;
    processes.push({
      pid: c.pid,
      name: 'agy.exe acp',
      cwd: anahtar,
      sessions: [...sessions.values()].filter((s) => s.cwdKey === anahtar).length,
    });
  }
  return { processes, count: processes.length };
}

export function killAllSessions() {
  const n = sessions.size;
  for (const s of sessions.values()) {
    if (s._approvalResolve) s._approvalResolve(s.pendingApproval?.denyId || '');
  }
  sessions.clear();
  for (const c of istemciler.values()) { try { c.kill(); } catch {} }
  istemciler.clear();
  return { ok: true, killed: n };
}

export function cleanupTmp() { /* ACP'de geçici klasör yok; sözleşme uyumu için duruyor. */ }

// Testler için: modülün içine bakmadan durumu kurmak/boşaltmak.
export const __agyTest = {
  sessions,
  reset() {
    sessions.clear();
    for (const c of istemciler.values()) { try { c.kill(); } catch {} }
    istemciler.clear();
    modelKatalogu = null;
  },
  ekleSahteOturum(id, alanlar = {}) {
    const s = yeniKayit({ id, cwd: alanlar.cwd || os.homedir() });
    Object.assign(s, alanlar);
    sessions.set(id, s);
    return s;
  },
  bildirimIsle,
  sunucuIstegiIsle,
  snapshot,
};
