import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import * as claude from '../claude-app.mjs';
// OpenCode soru protokolü testleri 30.09.2026'da DÜŞTÜ: v1 backend'i söküldü ve
// normalizeOpenCodeQuestions/buildOpenCodeQuestionAnswers onunla gitti. v2'nin
// izin akışı tek allow/deny (answers alanı yok), karşılığı yok.

const QUESTIONS = [
  { id: 'q1', question: 'Birinci?', options: [{ label: 'A' }, { label: 'B' }] },
  { id: 'q2', question: 'İkinci?', options: [{ label: 'C' }, { label: 'D' }] },
  { id: 'q3', question: 'Üçüncü?', options: [{ label: 'E' }, { label: 'F' }] },
];

describe('Claude AskUserQuestion batching', () => {
  it('eksik cevapta isteği açık tutar, bütün cevaplarda tek response gönderir', () => {
    const { sessionId } = claude.newSession({ cwd: os.homedir() });
    const s = claude.__getSession(sessionId);
    const writes = [];
    s.child = { stdin: { destroyed: false, write: value => { writes.push(value); return true; } } };
    s.pendingApproval = {
      requestId: 'request-1',
      kind: 'question',
      input: { questions: QUESTIONS },
    };

    const incomplete = claude.approve({
      sessionId,
      allow: true,
      answers: [{ id: 'q1', optionId: 'A', label: 'A' }],
    });
    assert.equal(incomplete.ok, false);
    assert.deepEqual(incomplete.missingQuestionIds, ['q2', 'q3']);
    assert.equal(writes.length, 0);
    assert.ok(s.pendingApproval, 'eksik cevapta pending istek korunmalı');

    const complete = claude.approve({
      sessionId,
      allow: true,
      answers: [
        { id: 'q1', optionId: 'A', label: 'A' },
        { id: 'q2', optionId: 'D', label: 'D' },
        { id: 'q3', optionId: 'E', label: 'E' },
      ],
    });
    assert.equal(complete.ok, true);
    assert.equal(writes.length, 1);
    const sent = JSON.parse(writes[0]);
    assert.deepEqual(sent.response.response.updatedInput.answers, {
      'Birinci?': 'A',
      'İkinci?': 'D',
      'Üçüncü?': 'E',
    });
    assert.equal(s.pendingApproval, null);
  });
});

describe('Claude AskUserQuestion multiSelect', () => {
  it('aynı soruya gelen birden fazla seçimi tek satırda birleştirir', () => {
    // AskUserQuestion şemasında answers bir Record<string, string>: değer STRING,
    // dizi değil. Çoklu seçim bu yüzden virgülle tek metne iner.
    const input = {
      questions: [
        { id: 'q1', question: 'Hangileri?', multiSelect: true, options: [{ label: 'A' }, { label: 'B' }, { label: 'C' }] },
      ],
    };
    const built = claude.buildQuestionUpdatedInput(input, [
      { id: 'q1', optionId: 'A', label: 'A' },
      { id: 'q1', optionId: 'C', label: 'C' },
    ]);
    assert.deepEqual(built.answers, { 'Hangileri?': 'A, C' });
  });

  it('tek seçimli soruda davranış değişmez', () => {
    const input = { questions: QUESTIONS };
    const built = claude.buildQuestionUpdatedInput(input, [
      { id: 'q1', optionId: 'B', label: 'B' },
      { id: 'q2', optionId: 'C', label: 'C' },
      { id: 'q3', optionId: 'F', label: 'F' },
    ]);
    assert.deepEqual(built.answers, { 'Birinci?': 'B', 'İkinci?': 'C', 'Üçüncü?': 'F' });
  });
});
