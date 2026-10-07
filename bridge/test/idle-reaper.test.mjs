import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { selectIdleChildren, reapIdleChildren } from '../idle-reaper.mjs';

const DK = 60_000;
const NOW = 1_000_000_000;

describe('idle-reaper selectIdleChildren', () => {
  it('30 dk siniri: eski bosta child secilir, taze olan kalir', () => {
    const r = selectIdleChildren({
      now: NOW, maxIdleMs: 30 * DK,
      sessions: [
        { id: 'eski', hasChild: true, status: 'idle', lastActivity: NOW - 31 * DK },
        { id: 'taze', hasChild: true, status: 'idle', lastActivity: NOW - 5 * DK },
        { id: 'sinir', hasChild: true, status: 'idle', lastActivity: NOW - 30 * DK },
      ],
    });
    assert.deepEqual(r.idle.map(s => s.id), ['eski', 'sinir']);
    assert.equal(r.fresh, 1);
  });

  it('kosan tur ve onay bekleyen oturuma dokunmaz', () => {
    const r = selectIdleChildren({
      now: NOW, maxIdleMs: 30 * DK,
      sessions: [
        { id: 'kosuyor', hasChild: true, status: 'running', lastActivity: NOW - 90 * DK },
        { id: 'onay', hasChild: true, status: 'idle', pendingApproval: { requestId: 'x' }, lastActivity: NOW - 90 * DK },
      ],
    });
    assert.equal(r.idle.length, 0);
    assert.equal(r.running, 1);
    assert.equal(r.awaiting, 1);
  });

  it('child olmayan oturum sayilir ama secilmez', () => {
    const r = selectIdleChildren({
      now: NOW, maxIdleMs: 30 * DK,
      sessions: [{ id: 'kabuk', hasChild: false, status: 'idle', lastActivity: 0 }],
    });
    assert.equal(r.idle.length, 0);
    assert.equal(r.noChild, 1);
  });

  it('zamani bilinmeyen canli child secilir (kapatma geri alinabilir)', () => {
    const r = selectIdleChildren({
      now: NOW, maxIdleMs: 30 * DK,
      sessions: [{ id: 'zamansiz', hasChild: true, status: 'idle', lastActivity: 0 }],
    });
    assert.deepEqual(r.idle.map(s => s.id), ['zamansiz']);
    assert.equal(r.unknown, 1);
  });

  it('maxIdleMs 0/gecersiz = ozellik kapali', () => {
    const sessions = [{ id: 'a', hasChild: true, status: 'idle', lastActivity: NOW - 999 * DK }];
    for (const v of [0, -1, NaN, undefined, 'x']) {
      assert.equal(selectIdleChildren({ now: NOW, maxIdleMs: v, sessions }).idle.length, 0, String(v));
    }
  });
});

describe('idle-reaper reapIdleChildren', () => {
  it('secilenleri kill ile kapatir, hatayi yutar ve sayar', () => {
    const killed = [];
    const r = reapIdleChildren({
      now: NOW, maxIdleMs: 30 * DK,
      sessions: [
        { id: 'a', hasChild: true, status: 'idle', lastActivity: NOW - 60 * DK },
        { id: 'patlar', hasChild: true, status: 'idle', lastActivity: NOW - 60 * DK },
        { id: 'taze', hasChild: true, status: 'idle', lastActivity: NOW - 1 * DK },
      ],
      kill: s => { if (s.id === 'patlar') throw new Error('taskkill yok'); killed.push(s.id); },
    });
    assert.deepEqual(killed, ['a']);
    assert.deepEqual(r.killed, ['a']);
    assert.equal(r.failed, 1);
    assert.equal(r.fresh, 1);
  });
});

describe('claude-app reapIdleChildren entegrasyonu', () => {
  it('bosta child kapanir, kosan/taze child kalir, oturum kaydi silinmez', async () => {
    const app = await import('../claude-app.mjs');
    const t = Date.now();
    const ids = ['reap-eski-' + t, 'reap-taze-' + t, 'reap-kosan-' + t];
    app.__restoreSessionsFromData({ sessions: ids.map(id => ({ id, cwd: process.cwd(), model: 'sonnet' })) });
    const [eski, taze, kosan] = ids.map(id => app.__getSession(id));
    // sahte child: killPersistentChild taskkill'i spawn eder, olmayan pid zararsiz
    for (const s of [eski, taze, kosan]) s.child = { pid: 4_000_000, exitCode: null };
    eski._lastActivity = t - 60 * DK;
    taze._lastActivity = t - 1 * DK;
    kosan._lastActivity = t - 60 * DK; kosan.status = 'running';
    const r = app.reapIdleChildren({ maxIdleMs: 30 * DK, now: t });
    assert.deepEqual(r.killed, [eski.id]);
    assert.equal(eski.child, null);
    assert.ok(taze.child);
    assert.ok(kosan.child);
    assert.ok(app.__getSession(eski.id), 'oturum kaydi kalmali');
    // temizlik
    kosan.status = 'idle';
    for (const s of [taze, kosan]) s.child = null;
  });
});
