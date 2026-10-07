// Shared transcript utilities for Claude backends (claude.mjs and claude-app.mjs).
// Extracted from claude.mjs per plan §9 (Faz 7) to avoid duplication.
// Pure helpers: tool summary, content text extraction, .jsonl transcript parsing.

import fs from 'node:fs';
import { StringDecoder } from 'node:string_decoder';

// ── Tool summary ──────────────────────────────────────────────────────────
export function summarizeTool(c) {
  const inp = c.input || {};
  const base = p => (p ? String(p).split(/[\\/]/).pop() : '');
  switch (c.name) {
    case 'Read': return 'Read ' + base(inp.file_path);
    case 'Edit': return 'Edit ' + base(inp.file_path);
    case 'Write': return 'Write ' + base(inp.file_path);
    case 'Bash': case 'PowerShell': return '$ ' + String(inp.command || '').replace(/\s+/g, ' ').slice(0, 70);
    case 'Grep': return 'Grep ' + String(inp.pattern || '').slice(0, 40);
    case 'Glob': return 'Glob ' + String(inp.pattern || '').slice(0, 40);
    case 'Task': return 'Task: ' + String(inp.description || inp.subagent_type || '').slice(0, 40);
    case 'WebFetch': return 'WebFetch ' + String(inp.url || '').slice(0, 50);
    case 'WebSearch': return 'WebSearch ' + String(inp.query || '').slice(0, 40);
    default: return (c.name || 'tool');
  }
}

// ── Meta / komut gürültüsü filtresi ─────────────────────────────────────────
// Claude Code transcript'inde slash-komut iskeleleri ve compaction özeti `user`
// rolüyle saklanır; bunlar gerçek sohbet turu değildir ve app'te ham etiketler
// (<command-name>, <local-command-stdout>, <local-command-caveat>) ve devasa
// "This session is being continued…" özeti olarak baloncuklara dolar. Bunları
// gizle. Kayıt üzerindeki isMeta/isCompactSummary bayrakları birincil sinyal;
// içerik-tabanlı eşleşme eski/bayraksız kayıtlar için güvenlik ağı.
// Kullanici rolunde gelen ama kullanicinin YAZMADIGI satirlar. task-notification
// ve system-reminder harness enjeksiyonudur; baslik/ilk-mesaj turetiminde bunlar
// sayilinca sekme basligi "<task-notification> <ta…" gibi cikiyordu.
const NOISE_TEXT_RE = /^\s*<\/?(command-name|command-message|command-args|local-command-stdout|local-command-caveat|task-notification|system-reminder)\b/;
export function isNoiseUserRecord(r, txt) {
  if (r && (r.isMeta === true || r.isCompactSummary === true)) return true;
  const t = String(txt || '');
  if (NOISE_TEXT_RE.test(t)) return true;
  if (/^This session is being continued from a previous conversation/.test(t)) return true;
  return false;
}

// Transcript kaydının ISO timestamp'ini yerel HH:MM'e çevirir (mesaj altı damgası).
// Kayıtta timestamp yoksa/boşsa boş döner → app timestamp'i çizmez.
function hhmmFromIso(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '';
  return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}

// ── Text extraction ───────────────────────────────────────────────────────
export function textFromContent(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content.filter(c => c && c.type === 'text').map(c => c.text).join('').trim();
  }
  return '';
}

// ── Transcript parsing ────────────────────────────────────────────────────
// Parse a Claude .jsonl transcript file into { cwd, messages[], toolDetails[],
// firstUser, lastText, turns, contextTokens, model, aiTitle }.
export function newTranscriptState() {
  return { cwd: '', aiTitle: '', messages: [], toolDetails: [], firstUser: '', firstTs: 0, lastText: '', turns: 0, contextTokens: 0, model: '', lastTs: 0 };
}

export function applyTranscriptRecord(out, r, { includeMessages = true } = {}) {
  if (!out || !r) return out;
  if (r.cwd && !out.cwd) out.cwd = r.cwd;
  // Son GERÇEK etkinlik zamanı: yalnız timestamp'li user/assistant kayıtları.
  // CLI'ın sonradan eklediği zaman damgasız bakım satırları (ai-title,
  // last-prompt, mode, bridge-session) dosya mtime'ını ilerletir ama lastTs'i
  // İLERLETMEZ — oturum listesi sıralaması bu yüzden lastTs'e dayanır
  // (eski oturumların "yukarı zıplaması" bug'ı).
  if ((r.type === 'user' || r.type === 'assistant') && r.timestamp) {
    const t = Date.parse(r.timestamp);
    if (Number.isFinite(t) && t > (out.lastTs || 0)) out.lastTs = t;
  }
  if (r.type === 'ai-title' && r.aiTitle) { out.aiTitle = r.aiTitle; return out; }
  if (r.type === 'user' && r.message) {
    const txt = textFromContent(r.message.content);
    if (!txt) return out;
    if (isNoiseUserRecord(r, txt)) return out;
    // firstUser + firstTs birlikte oturumun "kökü"nü tanımlar: CLI bir oturumu
    // devam ettirirken tüm geçmişi yeni uuid'li dosyaya kopyalar, kopyalarda bu
    // ikili aynı kalır. Liste bunu kullanıp aynı sohbeti tek satırda toplar.
    if (!out.firstUser) {
      out.firstUser = txt;
      const t = Date.parse(r.timestamp || '');
      out.firstTs = Number.isFinite(t) ? t : 0;
    }
    out.turns = (out.turns || 0) + 1;
    if (includeMessages) out.messages.push({ role: 'user', text: txt, time: hhmmFromIso(r.timestamp) });
    out.lastText = txt;
  } else if (r.type === 'assistant' && r.message) {
    if (r.message.model && !out.model) out.model = String(r.message.model);
    const u = r.message.usage;
    if (u && (typeof u.input_tokens === 'number' || typeof u.cache_read_input_tokens === 'number')) {
      const t = (u.input_tokens || 0) + (u.cache_creation_input_tokens || 0) + (u.cache_read_input_tokens || 0);
      if (t > 0) out.contextTokens = t;
    }
    if (!Array.isArray(r.message.content)) return out;
    for (const c of r.message.content) {
      if (c.type === 'text' && c.text && c.text.trim()) {
        if (includeMessages) out.messages.push({ role: 'agent', text: c.text, time: hhmmFromIso(r.timestamp) });
        out.lastText = c.text.trim();
      } else if (includeMessages && c.type === 'thinking') {
        const idx = out.toolDetails.length;
        out.toolDetails.push(String(c.thinking || ''));
        out.messages.push({ role: 'thought', text: '', thoughtIndex: idx });
      } else if (includeMessages && c.type === 'tool_use') {
        const idx = out.toolDetails.length;
        out.toolDetails.push(JSON.stringify(c.input || {}, null, 2));
        out.messages.push({ role: 'thought', text: summarizeTool(c), thoughtIndex: idx });
      }
    }
  }
  return out;
}

export function applyTranscriptLine(out, line, opts = {}) {
  const t = String(line || '').trim();
  if (!t) return out;
  let r; try { r = JSON.parse(t); } catch { return out; }
  return applyTranscriptRecord(out, r, opts);
}

export function parseTranscriptText(raw, opts = {}) {
  const out = opts.out || newTranscriptState();
  for (const line of String(raw || '').split('\n')) applyTranscriptLine(out, line, opts);
  return out;
}

export function parseTranscript(file) {
  let raw;
  try { raw = fs.readFileSync(file, 'utf-8'); } catch { return newTranscriptState(); }
  return parseTranscriptText(raw);
}

export function parseTranscriptHead(file, { headBytes = 64 * 1024, tailBytes = 16 * 1024, maxHeadScanBytes = 8 * 1024 * 1024 } = {}) {
  const out = newTranscriptState();
  let fd, st;
  try { st = fs.statSync(file); fd = fs.openSync(file, 'r'); } catch { return out; }
  try {
    if (st.size <= headBytes + tailBytes) {
      return parseTranscript(file);
    }
    // Baş bölümü satır satır tara. Ekran görüntüsüyle başlayan oturumlarda ilk
    // user kaydı tek satırda yüz binlerce bayt base64 taşır; sabit 64KB pencere
    // o satırı hiç tamamlayamıyor, firstUser (→ oturum başlığı) boş kalıp UI
    // "2b1cb024" gibi id kırpmasına düşüyordu. firstUser bulunana kadar
    // (tavana dek) okumaya devam et; satır sınırı taşınır (carry), çok baytlı
    // UTF-8 karakterleri chunk sınırında bölünmesin diye StringDecoder.
    const tailLen = Math.min(tailBytes, st.size);
    const headEnd = st.size - tailLen;
    const dec = new StringDecoder('utf-8');
    const buf = Buffer.alloc(Math.min(headBytes, headEnd));
    let offset = 0, carry = '';
    while (offset < headEnd) {
      const n = fs.readSync(fd, buf, 0, Math.min(buf.length, headEnd - offset), offset);
      if (n <= 0) break;
      offset += n;
      carry += dec.write(buf.subarray(0, n));
      const lines = carry.split('\n');
      carry = lines.pop();
      for (const line of lines) applyTranscriptLine(out, line, { includeMessages: false });
      if (offset >= headBytes && (out.firstUser || offset >= maxHeadScanBytes)) break;
    }
    if (offset >= headEnd && carry) applyTranscriptLine(out, carry, { includeMessages: false });

    const tail = Buffer.alloc(tailLen);
    fs.readSync(fd, tail, 0, tailLen, st.size - tailLen);
    parseTranscriptText(tail.toString('utf-8').replace(/^[^\n]*\n?/, ''), { out, includeMessages: false });
    if (!out.turns && (out.firstUser || out.lastText)) out.turns = 1;
    return out;
  } finally {
    try { if (fd != null) fs.closeSync(fd); } catch {}
  }
}
