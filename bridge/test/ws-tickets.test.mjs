import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createWsTickets } from '../ws-tickets.mjs';

describe('ws-tickets', () => {
  it('bilet bir kez kullanılır', () => {
    const t = createWsTickets();
    const { ticket } = t.issue({ kind: 'device', id: 'd1' });
    assert.deepEqual(t.redeem(ticket), { kind: 'device', id: 'd1' });
    assert.equal(t.redeem(ticket), null);
  });

  it('süresi dolan bilet reddedilir', () => {
    let saat = 1000;
    const t = createWsTickets({ ttlMs: 30_000, now: () => saat });
    const { ticket } = t.issue({ kind: 'legacy', id: 'legacy' });
    saat += 30_000;
    assert.equal(t.redeem(ticket), null);
  });

  it('bilinmeyen ya da boş bilet reddedilir', () => {
    const t = createWsTickets();
    assert.equal(t.redeem('tkt_uydurma'), null);
    assert.equal(t.redeem(''), null);
    assert.equal(t.redeem(null), null);
  });

  it('kimliksiz bilet çıkarılamaz', () => {
    assert.throws(() => createWsTickets().issue(null));
  });

  it('canlı bilet sayısı sınırlı', () => {
    const t = createWsTickets({ maxLive: 3 });
    for (let i = 0; i < 10; i++) t.issue({ kind: 'device', id: String(i) });
    assert.equal(t.size(), 3);
  });
});
