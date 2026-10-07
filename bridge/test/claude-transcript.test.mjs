import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { parseTranscript, parseTranscriptHead, parseTranscriptText, newTranscriptState, isNoiseUserRecord } from '../claude-transcript.mjs';

function line(obj) {
  return JSON.stringify(obj) + '\n';
}

function sampleTranscript(extra = '') {
  return [
    line({ cwd: os.homedir(), type: 'user', message: { content: [{ type: 'text', text: 'hello' }] } }),
    line({ type: 'assistant', message: { model: 'claude-test', usage: { input_tokens: 3 }, content: [{ type: 'text', text: 'hi there' }] } }),
    extra,
    line({ type: 'user', message: { content: [{ type: 'text', text: 'again' }] } }),
    line({ type: 'assistant', message: { content: [{ type: 'tool_use', name: 'Read', input: { file_path: 'README.md' } }] } }),
  ].join('');
}

describe('claude-transcript parser', () => {
  it('parseTranscript reads messages and tool details', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'claude-transcript-'));
    const file = path.join(dir, 't.jsonl');
    fs.writeFileSync(file, sampleTranscript(), 'utf-8');
    const parsed = parseTranscript(file);
    assert.equal(parsed.turns, 2);
    assert.equal(parsed.firstUser, 'hello');
    assert.equal(parsed.lastText, 'again');
    assert.equal(parsed.model, 'claude-test');
    assert.equal(parsed.contextTokens, 3);
    assert.ok(parsed.messages.some(m => m.role === 'agent' && m.text === 'hi there'));
    assert.equal(parsed.toolDetails.length, 1);
  });

  it('parseTranscriptText can incrementally append complete lines', () => {
    const out = newTranscriptState();
    parseTranscriptText(line({ type: 'user', message: { content: [{ type: 'text', text: 'one' }] } }), { out });
    parseTranscriptText(line({ type: 'assistant', message: { content: [{ type: 'text', text: 'two' }] } }), { out });
    assert.deepEqual(out.messages.map(m => m.text), ['one', 'two']);
    assert.equal(out.turns, 1);
    assert.equal(out.lastText, 'two');
  });

  it('filters slash-command scaffolding and compact summary from user messages', () => {
    const out = newTranscriptState();
    parseTranscriptText(line({ type: 'user', message: { content: [{ type: 'text', text: 'gerçek soru' }] } }), { out });
    // Slash-komut iskeleleri (bayraklı ve bayraksız) ve caveat gizlenmeli.
    parseTranscriptText(line({ type: 'user', isMeta: true, message: { content: [{ type: 'text', text: '<local-command-caveat>Caveat: ...</local-command-caveat>' }] } }), { out });
    parseTranscriptText(line({ type: 'user', message: { content: [{ type: 'text', text: '<command-name>/compact</command-name>' }] } }), { out });
    parseTranscriptText(line({ type: 'user', message: { content: [{ type: 'text', text: '<local-command-stdout>Compacted </local-command-stdout>' }] } }), { out });
    // Compaction özeti (bayraklı) da gizlenmeli.
    parseTranscriptText(line({ type: 'user', isCompactSummary: true, message: { content: [{ type: 'text', text: 'This session is being continued from a previous conversation...' }] } }), { out });
    parseTranscriptText(line({ type: 'assistant', message: { content: [{ type: 'text', text: 'cevap' }] } }), { out });
    assert.deepEqual(out.messages.map(m => m.text), ['gerçek soru', 'cevap']);
    assert.equal(out.turns, 1);
    assert.equal(out.firstUser, 'gerçek soru');
  });

  it('harness enjeksiyonlari (task-notification, system-reminder) baslik olamaz', () => {
    // Canli vaka: sekme basligi "<task-notification> <ta…" cikiyordu. Bu satirlar
    // user rolunde gelir ama kullanicinin YAZDIGI sey degildir; ilk-mesaj/baslik
    // turetiminde sayilmamalilar.
    assert.equal(isNoiseUserRecord({}, '<task-notification>\n<task-id>abc</task-id>'), true);
    assert.equal(isNoiseUserRecord({}, '<system-reminder>hatirlatma</system-reminder>'), true);
    assert.equal(isNoiseUserRecord({}, 'ttft ne'), false);

    const out = newTranscriptState();
    parseTranscriptText(line({ type: 'user', message: { content: [{ type: 'text', text: '<task-notification>\n<task-id>abc</task-id>' }] } }), { out });
    parseTranscriptText(line({ type: 'user', message: { content: [{ type: 'text', text: '<system-reminder>x</system-reminder>' }] } }), { out });
    parseTranscriptText(line({ type: 'user', message: { content: [{ type: 'text', text: 'asil sorum' }] } }), { out });
    assert.equal(out.firstUser, 'asil sorum');
    assert.equal(out.turns, 1);
  });

  it('lastTs son gerçek mesajın zamanıdır; CLI bakım satırları ilerletmez', () => {
    const out = newTranscriptState();
    parseTranscriptText(line({ type: 'user', timestamp: '2026-07-09T08:00:00.000Z', message: { content: [{ type: 'text', text: 'soru' }] } }), { out });
    parseTranscriptText(line({ type: 'assistant', timestamp: '2026-07-09T08:35:00.000Z', message: { content: [{ type: 'text', text: 'cevap' }] } }), { out });
    // CLI'ın sonradan eklediği zaman damgasız bakım kayıtları (eski oturumları
    // listede yukarı zıplatan dosya dokunuşları) lastTs'i DEĞİŞTİRMEMELİ.
    parseTranscriptText(line({ type: 'ai-title', aiTitle: 'Başlık' }), { out });
    parseTranscriptText(line({ type: 'last-prompt', lastPrompt: 'soru' }), { out });
    parseTranscriptText(line({ type: 'mode', mode: 'normal' }), { out });
    parseTranscriptText(line({ type: 'bridge-session', bridgeSessionId: 'cse_x', lastSequenceNum: 5 }), { out });
    assert.equal(out.lastTs, Date.parse('2026-07-09T08:35:00.000Z'));
  });

  it('parseTranscriptHead tail bölümünden lastTs çıkarır', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'claude-transcript-ts-'));
    const file = path.join(dir, 'ts.jsonl');
    const filler = line({ type: 'assistant', timestamp: '2026-07-09T08:10:00.000Z', message: { content: [{ type: 'text', text: 'x'.repeat(2000) }] } }).repeat(8);
    const content = [
      line({ cwd: os.homedir(), type: 'user', timestamp: '2026-07-09T08:00:00.000Z', message: { content: [{ type: 'text', text: 'hello' }] } }),
      filler,
      line({ type: 'assistant', timestamp: '2026-07-09T09:00:00.000Z', message: { content: [{ type: 'text', text: 'son cevap' }] } }),
      line({ type: 'bridge-session', bridgeSessionId: 'cse_x', lastSequenceNum: 3 }),
    ].join('');
    fs.writeFileSync(file, content, 'utf-8');
    const parsed = parseTranscriptHead(file, { headBytes: 512, tailBytes: 512 });
    assert.equal(parsed.lastTs, Date.parse('2026-07-09T09:00:00.000Z'));
  });

  it('parseTranscriptHead returns lightweight preview fields without message payloads', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'claude-transcript-head-'));
    const file = path.join(dir, 'big.jsonl');
    const filler = line({ type: 'assistant', message: { content: [{ type: 'text', text: 'x'.repeat(2000) }] } }).repeat(8);
    fs.writeFileSync(file, sampleTranscript(filler), 'utf-8');
    const parsed = parseTranscriptHead(file, { headBytes: 512, tailBytes: 512 });
    assert.equal(parsed.firstUser, 'hello');
    assert.ok(parsed.lastText === 'again' || parsed.lastText.length > 0);
    assert.equal(parsed.messages.length, 0);
  });

  it('parseTranscriptHead finds firstUser behind a huge image line (ekran görüntülü ilk mesaj)', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'claude-transcript-img-'));
    const file = path.join(dir, 'img.jsonl');
    // İlk user kaydı tek satırda dev base64 görsel + Türkçe metin taşır;
    // headBytes penceresinden çok daha büyük. Eski kod satırı tamamlayamayıp
    // firstUser'ı boş bırakıyordu (→ UI'da "2b1cb024" gibi id başlığı).
    const imgLine = line({
      type: 'user',
      message: {
        content: [
          { type: 'image', source: { type: 'base64', media_type: 'image/jpeg', data: 'A'.repeat(300 * 1024) } },
          { type: 'text', text: 'görselli ilk soru' },
        ],
      },
    });
    const filler = line({ type: 'assistant', message: { content: [{ type: 'text', text: 'y'.repeat(2000) }] } }).repeat(8);
    fs.writeFileSync(file, imgLine + filler, 'utf-8');
    const parsed = parseTranscriptHead(file, { headBytes: 4 * 1024, tailBytes: 512 });
    assert.equal(parsed.firstUser, 'görselli ilk soru');
    assert.equal(parsed.messages.length, 0);
  });

  it('parseTranscriptHead respects maxHeadScanBytes cap when no user text exists', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'claude-transcript-cap-'));
    const file = path.join(dir, 'cap.jsonl');
    const noise = line({ type: 'assistant', message: { content: [{ type: 'text', text: 'z'.repeat(4000) }] } }).repeat(64);
    fs.writeFileSync(file, noise, 'utf-8');
    const parsed = parseTranscriptHead(file, { headBytes: 1024, tailBytes: 512, maxHeadScanBytes: 8 * 1024 });
    assert.equal(parsed.firstUser, '');
    assert.ok(parsed.lastText.length > 0); // tail hâlâ okunur
  });
});
