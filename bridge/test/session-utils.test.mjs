import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { extractChoices, appendToolDetail, capToolDetails, capMessages, pruneEvictedThoughtDetail, withStreamSeq } from '../session-utils.mjs';

describe('extractChoices()', () => {
  it('extracts numbered list with question mark', () => {
    const text = 'Hangisini tercih edersin?\n1. Kırmızı\n2. Mavi\n3. Yeşil';
    const r = extractChoices(text);
    assert.ok(r);
    assert.equal(r.choices.length, 3);
    assert.ok(r.choices.includes('Kırmızı'));
    assert.ok(r.choices.includes('Mavi'));
    assert.ok(r.choices.includes('Yeşil'));
    assert.ok(r.question.includes('Hangisini'));
  });

  it('extracts bulleted list with keyword "seç"', () => {
    const text = 'Lütfen bir seçenek seçin:\n- X\n- Y\n- Z';
    const r = extractChoices(text);
    assert.ok(r);
    assert.equal(r.choices.length, 3);
    assert.ok(r.choices.includes('X'));
    assert.ok(r.choices.includes('Y'));
    assert.ok(r.choices.includes('Z'));
  });

  it('extracts with English keyword "which"', () => {
    const text = 'Which approach would you like?\n1) Option A\n2) Option B';
    const r = extractChoices(text);
    assert.ok(r);
    assert.equal(r.choices.length, 2);
    assert.ok(r.choices.includes('Option A'));
  });

  it('returns null for plain text without question', () => {
    const text = 'İşte kodun analizi:\n1. Hata yok\n2. Performans iyi';
    const r = extractChoices(text);
    assert.equal(r, null);
  });

  it('returns null for non-text input', () => {
    assert.equal(extractChoices(''), null);
    assert.equal(extractChoices(null), null);
    assert.equal(extractChoices(undefined), null);
  });

  it('returns null for fewer than 2 choices', () => {
    const text = 'Seçenek nedir?\n1. Tek seçenek';
    const r = extractChoices(text);
    assert.equal(r, null);
  });

  it('extracts with asterisk bullets', () => {
    const text = 'Hangi yolu izleyelim?\n* Refactor\n* Rewrite\n* Patch';
    const r = extractChoices(text);
    assert.ok(r);
    assert.equal(r.choices.length, 3);
  });

  it('extracts with bullet character •', () => {
    const text = 'Select a color?\n• Red\n• Green\n• Blue';
    const r = extractChoices(text);
    assert.ok(r);
    assert.equal(r.choices.length, 3);
  });

  it('returns null when prose follows the list (not a menu)', () => {
    const text = 'Kapsam dışı (YAGNI)?\n- Web\n- Split-view\n- Windows otomatik güncelleme\n\nBu son bölüm de uygun mu? Bir itirazın yoksa commit edeceğim.';
    assert.equal(extractChoices(text), null);
  });

  it('returns null for scattered bullets across prose sections', () => {
    const text = 'Hangi yol?\n- A\n\nAraya giren düz metin paragrafı burada.\n- B\n- C';
    assert.equal(extractChoices(text), null);
  });

  it('returns null when a choice is a long paragraph', () => {
    const text = 'Seç:\n- ' + 'x'.repeat(100) + '\n- kısa';
    assert.equal(extractChoices(text), null);
  });

  it('handles leading/trailing whitespace in choices', () => {
    const text = 'Which one?\n1.   lots of spaces   \n2. normal';
    const r = extractChoices(text);
    assert.ok(r);
    assert.equal(r.choices[0], 'lots of spaces');
    assert.equal(r.choices[1], 'normal');
  });
});

describe('toolDetails budget helpers', () => {
  it('appendToolDetail trims a single growing detail from the front', () => {
    const s = { toolDetails: ['abc'] };
    appendToolDetail(s, 0, 'defghij', 6);
    assert.equal(s.toolDetails[0], '[trimmed]\nefghij');
  });

  it('capToolDetails preserves indexes while replacing oldest contents', () => {
    const s = { toolDetails: ['a'.repeat(1000), 'b'.repeat(1000), 'fresh'] };
    capToolDetails(s, 1200);
    assert.equal(s.toolDetails.length, 3);
    assert.equal(s.toolDetails[0], '[old detail trimmed]');
    assert.equal(s.toolDetails[2], 'fresh');
  });

  it('capMessages eviction can prune unreferenced thought details', () => {
    const s = {
      messages: [
        { role: 'thought', thoughtIndex: 0 },
        { role: 'agent', text: 'keep' },
      ],
      toolDetails: ['old detail'],
    };
    capMessages(s, 1, msg => pruneEvictedThoughtDetail(s, msg));
    assert.equal(s.messages.length, 1);
    assert.equal(s.toolDetails[0], '');
  });

  it('capMessages counts only real turns, not tool/thinking rows', () => {
    // 2 tur (user+agent) arasına serpilmiş 6 thought satırı. max=2 tur → hepsi kalır.
    const s = {
      messages: [
        { role: 'user', text: 'q' },
        { role: 'thought', text: '$ grep a' },
        { role: 'thought', text: '$ grep b' },
        { role: 'thought', text: '' },
        { role: 'agent', text: 'done' },
      ],
    };
    assert.deepEqual(capMessages(s, 2), []);       // 2 tur var, tavan 2 → kırpma yok
    assert.equal(s.messages.length, 5);
  });

  it('capMessages evicts oldest turn and its leading thought rows', () => {
    const s = {
      messages: [
        { role: 'user', text: 'q1' },
        { role: 'thought', text: '$ old' },
        { role: 'agent', text: 'a1' },
        { role: 'user', text: 'q2' },
        { role: 'agent', text: 'a2' },
      ],
    };
    const evicted = capMessages(s, 2);             // son 2 turu tut (q2,a2 değil — user/agent'ın son 2'si: q2,a2)
    // son 2 GERÇEK tur: index 3 (q2) ve 4 (a2). Kesme index 3'te → 0..2 düşer.
    assert.equal(evicted.length, 3);
    assert.deepEqual(s.messages.map(m => m.text), ['q2', 'a2']);
  });

  it('capMessages enforces hard row ceiling even within turn budget', () => {
    const messages = [{ role: 'user', text: 'u' }];
    for (let i = 0; i < 10; i++) messages.push({ role: 'thought', text: 't' + i });
    messages.push({ role: 'agent', text: 'a' });
    const s = { messages };
    capMessages(s, 800, undefined, 5);             // 1 tur ama 12 ham satır, hardMax 5
    assert.equal(s.messages.length, 5);
    assert.equal(s.messages[s.messages.length - 1].text, 'a');
  });
});

describe('stream sequence helpers', () => {
  it('adds monotonically increasing seq to stream payloads', () => {
    const s = {};
    assert.equal(withStreamSeq(s, { type: 'conversation' }).seq, 1);
    assert.equal(withStreamSeq(s, { type: 'end' }).seq, 2);
  });

  it('does not change non-stream payloads or existing seq', () => {
    const s = {};
    assert.deepEqual(withStreamSeq(s, { type: 'error', error: 'x' }), { type: 'error', error: 'x' });
    assert.deepEqual(withStreamSeq(s, { type: 'conversation', seq: 7 }), { type: 'conversation', seq: 7 });
  });
});
