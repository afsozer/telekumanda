import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { backfillThinkingDetails } from '../claude-app.mjs';

function thought(idx, text = '') { return { role: 'thought', text, thoughtIndex: idx }; }

describe('backfillThinkingDetails', () => {
  it('bos thinking detaylarini transkriptten sondan hizalayarak doldurur', () => {
    const s = {
      messages: [
        { role: 'user', text: 'soru' },
        thought(0),               // thinking (stream'de bos geldi)
        thought(1, 'Grep foo'),   // arac satiri — thinking degil, dokunulmaz
        thought(2),               // thinking
        { role: 'agent', text: 'cevap' },
      ],
      toolDetails: ['', '{}', ''],
    };
    const parsed = {
      messages: [thought(0), thought(1, 'Grep foo'), thought(2)],
      toolDetails: ['ilk düşünce', '{}', 'ikinci düşünce'],
    };
    const filled = backfillThinkingDetails(s, parsed);
    assert.equal(filled, 2);
    assert.equal(s.toolDetails[0], 'ilk düşünce');
    assert.equal(s.toolDetails[1], '{}');
    assert.equal(s.toolDetails[2], 'ikinci düşünce');
  });

  it('capMessages kirpmasinda sondan hizalama dogru satiri bulur', () => {
    // Bellekte yalniz SON thinking satiri kalmis (eskiler kirpildi);
    // transcript'te uc thinking var. Sondan hizalama son metni esler.
    const s = {
      messages: [thought(5)],
      toolDetails: ['', '', '', '', '', ''],
    };
    const parsed = {
      messages: [thought(0), thought(1), thought(2)],
      toolDetails: ['eski1', 'eski2', 'en yeni'],
    };
    backfillThinkingDetails(s, parsed);
    assert.equal(s.toolDetails[5], 'en yeni');
  });

  it('dolu detaylari ezmez ve bos transkriptte no-op kalir', () => {
    const s = { messages: [thought(0)], toolDetails: ['zaten dolu'] };
    assert.equal(backfillThinkingDetails(s, { messages: [thought(0)], toolDetails: ['baska'] }), 0);
    assert.equal(s.toolDetails[0], 'zaten dolu');
    assert.equal(backfillThinkingDetails({ messages: [], toolDetails: [] }, null), 0);
  });
});
