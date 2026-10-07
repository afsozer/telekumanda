import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { globalSearch } from '../search.mjs';

function fakeProjectsStore(projects = []) {
  return {
    list: async () => ({ ok: true, projects }),
  };
}

function fakeModule(id, searchFn) {
  const mod = {
    searchDiskSessions: searchFn || (() => []),
  };
  return [id, mod];
}

describe('globalSearch()', () => {
  it('returns empty hits for query shorter than 2 chars', async () => {
    const r = await globalSearch({ query: 'a', projectsStore: fakeProjectsStore() });
    assert.equal(r.ok, true);
    assert.equal(r.hits.length, 0);
  });

  it('finds project by display name', async () => {
    const r = await globalSearch({
      query: 'testbridge',
      projectsStore: fakeProjectsStore([{ id: 'p1', path: 'C:\\project', name: 'testbridge', displayName: 'TestBridge' }]),
    });
    assert.equal(r.ok, true);
    assert.ok(r.hits.some(h => h.type === 'project' && h.projectId === 'p1'));
  });

  it('finds project by path match', async () => {
    const r = await globalSearch({
      query: 'special',
      projectsStore: fakeProjectsStore([{ id: 'p2', path: 'C:\\special-project', name: 'special-project' }]),
    });
    assert.ok(r.hits.some(h => h.type === 'project'));
  });

  it('includes provider session hits', async () => {
    const mod = fakeModule('claude-app', () => [
      { type: 'session', sessionId: 's1', title: 'Dilekce hazirligi', projectPath: '/work', mtime: 1000, text: '' },
    ]);
    const r = await globalSearch({
      query: 'dilekce',
      projectsStore: fakeProjectsStore([]),
      modules: Object.fromEntries([mod]),
    });
    assert.ok(r.hits.some(h => h.type === 'session' && h.sessionId === 's1'));
  });

  it('includes provider message hits', async () => {
    const mod = fakeModule('claude-app', () => [
      { type: 'message', sessionId: 's2', title: 'Setup', projectPath: '/work', role: 'user',
        text: 'Please create a PDF petition', mtime: 2000 },
    ]);
    const r = await globalSearch({
      query: 'petition',
      projectsStore: fakeProjectsStore([]),
      modules: Object.fromEntries([mod]),
    });
    assert.ok(r.hits.some(h => h.type === 'message' && h.role === 'user'));
  });

  it('deduplicates genuinely repeated hits from one provider', async () => {
    const hit = { type: 'message', sessionId: 's3', title: 'T', projectPath: '/w', role: 'user', text: 'findme', mtime: 3000, matchOrdinal: 0 };
    const mod = fakeModule('a', () => [hit, { ...hit }]); // same identity twice → 1
    const r = await globalSearch({
      query: 'findme',
      projectsStore: fakeProjectsStore([]),
      modules: Object.fromEntries([mod]),
    });
    const msgHits = r.hits.filter(h => h.type === 'message');
    assert.equal(msgHits.length, 1);
  });

  it('keeps multiple ordinal matches from the same session', async () => {
    const mod = fakeModule('claude-app', () => [
      { type: 'message', sessionId: 's', title: 'T', projectPath: '/w', role: 'user', text: 'findme once', mtime: 1, matchOrdinal: 0 },
      { type: 'message', sessionId: 's', title: 'T', projectPath: '/w', role: 'agent', text: 'findme twice', mtime: 1, matchOrdinal: 1 },
    ]);
    const r = await globalSearch({
      query: 'findme',
      projectsStore: fakeProjectsStore([]),
      modules: Object.fromEntries([mod]),
    });
    const msgHits = r.hits.filter(h => h.type === 'message' && h.sessionId === 's');
    assert.equal(msgHits.length, 2);
    assert.deepEqual(msgHits.map(h => h.matchOrdinal).sort(), [0, 1]);
  });

  it('does not drop same session id across different backends', async () => {
    const make = () => [{ type: 'message', sessionId: 'same', title: 'T', projectPath: '/w', role: 'user', text: 'findme', mtime: 1, matchOrdinal: 0 }];
    const r = await globalSearch({
      query: 'findme',
      projectsStore: fakeProjectsStore([]),
      modules: Object.fromEntries([fakeModule('claude-app', make), fakeModule('codex-app', make)]),
    });
    const msgHits = r.hits.filter(h => h.type === 'message' && h.sessionId === 'same');
    assert.equal(msgHits.length, 2);
  });

  it('prefers rowId over ordinal for identity when present', async () => {
    const mod = fakeModule('claude-app', () => [
      { type: 'message', sessionId: 's', title: 'T', projectPath: '/w', role: 'user', text: 'findme a', rowId: 'r1', mtime: 1, matchOrdinal: 0 },
      { type: 'message', sessionId: 's', title: 'T', projectPath: '/w', role: 'agent', text: 'findme b', rowId: 'r2', mtime: 1, matchOrdinal: 0 },
    ]);
    const r = await globalSearch({
      query: 'findme',
      projectsStore: fakeProjectsStore([]),
      modules: Object.fromEntries([mod]),
    });
    // Same ordinal but distinct rowIds → both kept.
    const msgHits = r.hits.filter(h => h.type === 'message' && h.sessionId === 's');
    assert.equal(msgHits.length, 2);
  });

  it('respects limit', async () => {
    const many = Array.from({ length: 50 }, (_, i) => ({
      type: 'session', sessionId: `s${i}`, title: `findme-${i}`, projectPath: '/w', mtime: i, text: '',
    }));
    const mod = fakeModule('x', () => many);
    const r = await globalSearch({
      query: 'findme',
      limit: 10,
      projectsStore: fakeProjectsStore([]),
      modules: Object.fromEntries([mod]),
    });
    assert.ok(r.hits.length <= 10);
  });

  it('sorts projects before sessions before messages', async () => {
    const r = await globalSearch({
      query: 'x',
      projectsStore: fakeProjectsStore([{ id: 'p', path: 'x', name: 'x' }]),
      modules: Object.fromEntries([
        fakeModule('c', () => [
          { type: 'message', sessionId: 'm1', title: 't', projectPath: 'x', role: 'user', text: 'x msg', mtime: 1 },
          { type: 'session', sessionId: 's1', title: 'x session', projectPath: 'x', mtime: 2, text: '' },
        ]),
      ]),
    });
    for (let i = 1; i < r.hits.length; i++) {
      const prev = r.hits[i - 1];
      const curr = r.hits[i];
      const order = { project: 0, session: 1, message: 2 };
      assert.ok((order[prev.type] ?? 3) <= (order[curr.type] ?? 3),
        `Expected ${prev.type} <= ${curr.type} at index ${i}`);
    }
  });

  it('reports provider warnings but still returns results', async () => {
    const badMod = fakeModule('bad', () => { throw new Error('test error'); });
    const goodMod = fakeModule('good', () => [
      { type: 'session', sessionId: 'sok', title: 'findme ok', projectPath: '/', mtime: 1, text: '' },
    ]);
    const r = await globalSearch({
      query: 'findme',
      projectsStore: fakeProjectsStore([]),
      modules: Object.fromEntries([badMod, goodMod]),
    });
    assert.ok(r.hits.length > 0);
    assert.ok(r.warnings && r.warnings.length > 0);
  });
});

describe('globalSearch() süre bütçesi', () => {
  it('bütçeyi aşan sağlayıcı uyarıya düşer, diğerlerinin sonuçları döner', async () => {
    // Canlıda görülen hata: tek yavaş sağlayıcı bütün cevabı Android'in okuma
    // zaman aşımının ötesine itiyor, telefon "Read timed out" gösteriyordu.
    //
    // Bu test yalnız EMNİYET KEMERİNİ ölçer (Promise.race): "donuk" sağlayıcı
    // hiç çözülmeyen bir Promise döndürüyor, yani `deadline`ı da önemsemiyor.
    // Asıl mekanizma — sağlayıcının kendi döngüsünde son tarihi kontrol etmesi —
    // sağlayıcı modüllerinin kendi testlerinde ölçülür; burada taklit modüller
    // var ve onların döngüsü yok.
    process.env.AGENTBRIDGE_SEARCH_BUDGET_MS = '1000';
    try {
      const modules = Object.fromEntries([
        fakeModule('hizli', () => [{ type: 'session', sessionId: 's1', title: 'kira testi', text: 'kira' }]),
        fakeModule('donuk', () => new Promise(() => {})), // hiç çözülmez
      ]);
      const t0 = Date.now();
      const r = await globalSearch({ query: 'kira', projectsStore: fakeProjectsStore(), modules });
      assert.ok(Date.now() - t0 < 5_000, 'bütçe cevabı sınırlamalı, donuk sağlayıcıyı beklememeli');
      assert.ok(r.hits.some(h => h.backend === 'hizli' && h.sessionId === 's1'));
      assert.ok((r.warnings || []).some(w => w.startsWith('donuk:')), 'aşan sağlayıcı uyarıda anılmalı');
    } finally {
      delete process.env.AGENTBRIDGE_SEARCH_BUDGET_MS;
    }
  });

  it('son tarih sağlayıcıya GEÇER — senkron sağlayıcı kendi döngüsünde kesebilsin', async () => {
    // Asıl düzeltme bu (18.08.2026). Sağlayıcıların üçü senkron; Node tek iş
    // parçacığı olduğu için onlar çalışırken globalSearch'ün setTimeout'u
    // sırasını ALAMIYOR ve bütçe sessizce hiç uygulanmıyordu — canlıda üç
    // sorgu 41/48/68 sn sürdü, `warnings` boş döndü. Durma kararı sağlayıcının
    // içinde verilmek zorunda, o yüzden son tarih argüman olarak geçiyor.
    let gorulen = null;
    const modules = Object.fromEntries([
      fakeModule('senkron', ({ deadline }) => { gorulen = deadline; return []; }),
    ]);
    const t0 = Date.now();
    await globalSearch({ query: 'kira', projectsStore: fakeProjectsStore(), modules });
    assert.equal(typeof gorulen, 'number', 'sağlayıcı deadline almalı');
    // Gelecekte ve makul bir pencerede olmalı (varsayılan bütçe 8 sn).
    assert.ok(gorulen > t0, 'son tarih gelecekte olmalı');
    assert.ok(gorulen - t0 <= 60_000, 'son tarih makul bir pencerede olmalı');
  });

  it('sağlayıcı başına geçen süre cevapta dönüyor', async () => {
    const modules = Object.fromEntries([
      fakeModule('olculen', () => []),
    ]);
    const r = await globalSearch({ query: 'kira', projectsStore: fakeProjectsStore(), modules });
    assert.equal(typeof r.timings?.olculen, 'number', 'timings sağlayıcı adıyla dönmeli');
  });

  it('hata fırlatan sağlayıcı da uyarıya düşer, cevabı düşürmez', async () => {
    const modules = Object.fromEntries([
      fakeModule('saglam', () => [{ type: 'session', sessionId: 's2', title: 'kira dosyası', text: 'kira' }]),
      fakeModule('bozuk', () => { throw new Error('disk okunamadı'); }),
    ]);
    const r = await globalSearch({ query: 'kira', projectsStore: fakeProjectsStore(), modules });
    assert.ok(r.hits.some(h => h.sessionId === 's2'));
    assert.ok((r.warnings || []).some(w => w.startsWith('bozuk:')));
  });
});
