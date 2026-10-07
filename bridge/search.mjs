// Global search orchestrator — scans projects, sessions, and message transcripts
// read‑only across all providers. Results are snippet‑limited, ranked, and deduped.
import crypto from 'node:crypto';

// Kararlı dedupe anahtarı. Mesaj sonuçlarında sağlayıcılar çoğunlukla rowId=''
// döndürüp eşleşmeyi matchOrdinal ile tanımladığından, rowId yoksa ordinal anahtara
// girer (aynı oturumdaki farklı eşleşmeler ayrı sonuç kalır). backend de dahildir:
// farklı sağlayıcılarda aynı sessionId birbirini düşürmez. matchOrdinal=0 falsy
// olduğu için ?? ile kontrol edilir; || yanlışlıkla 0'ı kaybederdi.
function stableKey({ type, backend = '', projectId = '', sessionId = '', rowId = '', matchOrdinal } = {}) {
  const matchKey = rowId || `ordinal:${matchOrdinal ?? 0}`;
  const src = `${type}:${backend}:${projectId}:${sessionId}:${matchKey}`;
  return crypto.createHash('sha256').update(src).digest('hex').slice(0, 12);
}

function snippet(text, query, maxLen = 200) {
  if (!text || !query) return text?.slice(0, maxLen) || '';
  const lower = text.toLowerCase();
  const q = query.toLowerCase();
  const idx = lower.indexOf(q);
  if (idx < 0) return text.slice(0, maxLen);
  const start = Math.max(0, idx - 60);
  const end = Math.min(text.length, idx + q.length + 140);
  const prefix = start > 0 ? '…' : '';
  const suffix = end < text.length ? '…' : '';
  return prefix + text.slice(start, end).replace(/\s+/g, ' ') + suffix;
}

/**
 * @param {object} opts
 * @param {string} opts.query
 * @param {number} [opts.limit=100]
 * @param {object} opts.projectsStore — must expose list() and at least { projects } with path/name/displayName/id
 * @param {object} opts.modules   — { [backendId]: module }
 * @param {object} [opts.coworkModule] — optional, for cowork session linking
 */
export async function globalSearch({ query, limit = 100, projectsStore, modules, coworkModule } = {}) {
  const q = (query || '').trim();
  if (q.length < 2) return { ok: true, query: q, truncated: false, hits: [], warnings: [] };
  // Karşılaştırma locale bağımsız küçük harf üzerinden yapılır; q'nun kendisini
  // küçültmek geri dönen `query` alanını ve snippet vurgusunu bozacağı için ayrı
  // bir qLower kullanılır (display/path zaten toLowerCase edilmiş).
  const qLower = q.toLowerCase();

  const hits = [];
  const seen = new Set();
  const warnings = [];
  const effectiveLimit = Math.min(limit, 100);

  // 1. Project name/path matches
  let projects;
  try { projects = (await projectsStore.list()).projects || []; } catch { projects = []; }
  for (const p of projects) {
    const display = (p.displayName || p.name || '').toLowerCase();
    const pathLow = (p.path || '').toLowerCase();
    if (display.includes(qLower) || pathLow.includes(qLower)) {
      const key = stableKey({ type: 'project', projectId: p.id });
      if (!seen.has(key)) {
        seen.add(key);
        hits.push({
          id: key, type: 'project', projectId: p.id, projectPath: p.path, projectName: p.displayName || p.name,
          backend: '', backendLabel: '', sessionId: '', container: 'direct', title: p.displayName || p.name,
          role: '', snippet: p.path, rowId: '', matchOrdinal: 0, mtime: 0,
        });
      }
    }
  }

  // 2. Per‑provider session and message search (best‑effort, per provider limit ~40)
  //
  // SÜRE BÜTÇESİ — 18.08.2026'da baştan yazıldı, çünkü öncekisi ÖLÜYDÜ.
  //
  // Eski hali sağlayıcı başına bir `Promise.race` + `setTimeout`tu. Ölçüldü
  // (18.08): aynı uçtan üç sorgu 41 sn, 48 sn ve 68 sn sürdü ve `warnings`
  // BOŞ döndü — yani zaman aşımı bir kez bile tetiklenmedi. Sebep Node'un tek
  // iş parçacığı: sağlayıcıların üçü (`claude-app`, `agy`, `omp`) SENKRON
  // fonksiyon; `mod.searchDiskSessions(...)` daha race kurulmadan, çağrı
  // satırında baştan sona çalışıyor. Event loop bloke olduğu için `setTimeout`
  // sırasını hiç alamıyor. Dışarıdan sayaç tutmak, işi yapan kodun kendisi
  // durmuyorsa hiçbir şeye yaramaz.
  //
  // Yeni kural: bütçe TOPLAM (sağlayıcı başına değil — 6 sağlayıcı × 6 sn
  // istemcinin 15 sn'lik okuma penceresini yine aşardı) ve son tarih
  // sağlayıcının İÇİNE geçer. Her sağlayıcı kendi oturum döngüsünün başında
  // `deadline`ı kontrol edip elindekiyle döner. `Promise.race` yalnız EMNİYET
  // KEMERİ olarak duruyor: `deadline`ı önemsemeyen (ya da tek bir dosyada
  // takılan) bir sağlayıcı cevabı büsbütün rehin almasın.
  // 8 sn: istemcinin okuma penceresi 15 sn (BridgeClient.client.readTimeout),
  // araya JSON serileştirme ve ağ turu giriyor — 8 rahat sığar, 6 gereğinden
  // erken kesiyordu (codex'in 360 rollout'u tek turda taranamıyor).
  const budgetMs = Math.max(1_000, Number(process.env.AGENTBRIDGE_SEARCH_BUDGET_MS) || 8_000);
  const deadline = Date.now() + budgetMs;
  const providerLimit = Math.min(40, Math.ceil(effectiveLimit / Object.keys(modules || {}).length || 1));
  const budgetExceeded = Symbol('budget');
  // Sağlayıcı başına geçen süre. Cevapta DÖNÜYOR: "arama yavaş" şikâyeti
  // geldiğinde hangi sağlayıcının yediğini tahmin etmek yerine okuyalım.
  const timings = {};
  const providerTasks = Object.entries(modules || {}).map(async ([backend, mod]) => {
    if (typeof mod.searchDiskSessions !== 'function') return;
    const basladi = Date.now();
    try {
      let timer;
      const result = await Promise.race([
        Promise.resolve(mod.searchDiskSessions({ query: q, limit: providerLimit, deadline })),
        // Kalan süre kadar bekle, baştan bütçe kadar değil: bu sağlayıcı sıraya
        // geç girdiyse ona ayrıca tam bütçe tanımak toplamı ikiye katlardı.
        new Promise(resolve => {
          timer = setTimeout(() => resolve(budgetExceeded), Math.max(250, deadline - Date.now()));
        }),
      ]).finally(() => clearTimeout(timer));
      timings[backend] = Date.now() - basladi;
      if (result === budgetExceeded) {
        warnings.push(`${backend}: arama süre bütçesini aştı (${Math.round(budgetMs / 1000)} sn), sonuçları bu turda eksik`);
        return;
      }
      if (!result) return;
      const results = Array.isArray(result) ? result : (result.hits || result.results || []);
      // KESİLDİ Mİ sorusunu sağlayıcı cevaplıyor, biz TAHMİN ETMİYORUZ.
      // İlk hali `Date.now() >= deadline` bakıyordu ve bu yanlış pozitif
      // üretiyordu: sıradaki sağlayıcı işini eksiksiz bitirse bile, kendinden
      // ÖNCEKİLER bütçeyi yemişse damga yiyordu. Canlıda beş sağlayıcının
      // beşi de "eksik" işaretlendi, oysa 64 isabetle tam sonuç dönmüştü.
      if (!Array.isArray(result) && result.truncated) {
        warnings.push(`${backend}: süre doldu, sonuçları bu turda eksik`);
      }
      for (const r of results) {
        const key = stableKey({
          type: r.type || 'message', backend, projectId: r.projectId || '',
          sessionId: r.sessionId || '', rowId: r.rowId || '', matchOrdinal: r.matchOrdinal,
        });
        if (seen.has(key)) continue;
        seen.add(key);
        hits.push({
          id: key,
          type: r.type || 'message',
          projectId: r.projectId || '',
          projectPath: r.projectPath || '',
          projectName: r.projectName || '',
          backend,
          backendLabel: r.backendLabel || backend,
          sessionId: r.sessionId || '',
          container: r.container || 'direct',
          title: r.title || '',
          role: r.role || '',
          snippet: snippet(r.text || r.snippet || '', q),
          rowId: r.rowId || '',
          matchOrdinal: r.matchOrdinal || 0,
          mtime: r.mtime || 0,
        });
      }
    } catch (e) {
      timings[backend] = Date.now() - basladi;
      warnings.push(`${backend}: ${e.message || String(e).slice(0, 120)}`);
    }
  });

  await Promise.allSettled(providerTasks);

  // 3. Sort: projects first, then sessions, then messages; newer first within each group
  const typeOrder = { project: 0, session: 1, message: 2 };
  hits.sort((a, b) => {
    const ta = typeOrder[a.type] ?? 3;
    const tb = typeOrder[b.type] ?? 3;
    if (ta !== tb) return ta - tb;
    if (a.mtime !== b.mtime) return b.mtime - a.mtime;
    return (a.title || '').localeCompare(b.title || '');
  });

  const truncated = hits.length > effectiveLimit;
  return {
    ok: true,
    query: q,
    truncated,
    hits: hits.slice(0, effectiveLimit),
    warnings: warnings.length ? warnings : undefined,
    timings,
  };
}
