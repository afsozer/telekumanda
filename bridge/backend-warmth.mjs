const WARMABLE = new Set(['codex-app', 'opencode2-app']);
const inflightWarmups = new Map();

export function normalizeWarmTargets(rawTargets) {
  const byBackend = new Map();
  for (const target of Array.isArray(rawTargets) ? rawTargets : []) {
    const backend = String(target?.backend || '').trim();
    const sessionId = String(target?.sessionId || '').trim();
    const cwd = String(target?.cwd || '').trim();
    if (!WARMABLE.has(backend)) continue;
    // Backend kaydı sessionId'den önce açılır: henüz ilk mesajı gönderilmemiş
    // sekme de sağlayıcı sürecini sıcak tutar.
    if (!byBackend.has(backend)) byBackend.set(backend, new Map());
    if (sessionId && !byBackend.get(backend).has(sessionId)) {
      byBackend.get(backend).set(sessionId, cwd);
    }
  }
  return [...byBackend].map(([backend, sessions]) => ({
    backend,
    openSessions: [...sessions].map(([sessionId, cwd]) => ({ sessionId, cwd })),
  }));
}

/**
 * İstek HTTP yanıtını backend'in soğuk açılışına bağlamaz. Android hemen 200
 * alır; warmup arka planda devam eder. Aynı backend hem tek istek içinde hem de
 * eşzamanlı cihaz istekleri arasında yalnız bir kez ısıtılır.
 */
export function warmOpenTabBackends(rawTargets, modules, onError = () => {}) {
  const targets = normalizeWarmTargets(rawTargets);
  for (const target of targets) {
    const mod = modules[target.backend];
    if (!mod || typeof mod.warmup !== 'function') continue;
    if (inflightWarmups.has(target.backend)) continue;
    const task = Promise.resolve()
      .then(() => mod.warmup({
        openSessions: target.openSessions,
        sessionIds: target.openSessions.map(session => session.sessionId),
      }))
      .catch(error => onError(target.backend, error))
      .finally(() => {
        if (inflightWarmups.get(target.backend) === task) {
          inflightWarmups.delete(target.backend);
        }
      });
    inflightWarmups.set(target.backend, task);
  }
  return {
    ok: true,
    accepted: targets.map(target => target.backend),
  };
}
