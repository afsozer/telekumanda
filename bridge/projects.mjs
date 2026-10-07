import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

function projectId(cwd) {
  return crypto.createHash('sha256').update(path.resolve(cwd).toLowerCase()).digest('hex').slice(0, 16);
}

function readRegistry(filePath) {
  try {
    const value = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    return value && typeof value === 'object' ? value : {};
  } catch { return {}; }
}

function writeRegistry(filePath, registry) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const tmp = filePath + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(registry, null, 2) + '\n', 'utf8');
  fs.renameSync(tmp, filePath);
}

function scanOutputs(cwd) {
  const dir = path.join(cwd, 'outputs');
  let names = [];
  try { names = fs.readdirSync(dir); } catch { return []; }
  const result = [];
  for (const name of names) {
    try {
      const file = path.join(dir, name);
      const stat = fs.statSync(file);
      if (!stat.isFile()) continue;
      result.push({ name, path: file, size: stat.size, mtime: stat.mtimeMs });
    } catch {}
  }
  return result.sort((a, b) => b.mtime - a.mtime);
}

// Geçici teslimat zip'lerinin tutulduğu tek dizin. archiveProjectOutputs buraya
// yazar; cleanupOutputsArchive yalnız buradaki dosyaları silebilir.
function outputsTmpDir() {
  return path.join(process.cwd(), 'bridge', 'tmp');
}

// Telefon zip'i indirip klasör olarak açtıktan sonra bridge tarafındaki geçici
// arşivi siler (başarı ve hata yollarında çağrılır — plan §6: kalıcı zip oluşmaz).
// Güvenlik: yalnız bridge/tmp içindeki dosyalar silinebilir; dizin dışına çıkan
// veya yol gezme (path traversal) içeren istekler reddedilir.
export function cleanupOutputsArchive(archivePath) {
  const raw = String(archivePath || '').trim();
  if (!raw) return { ok: false, error: 'path required' };
  const tmpDir = path.resolve(outputsTmpDir());
  const target = path.resolve(raw);
  const rel = path.relative(tmpDir, target);
  if (rel.startsWith('..') || path.isAbsolute(rel)) {
    return { ok: false, error: 'path bridge/tmp dışında' };
  }
  try {
    if (fs.existsSync(target)) fs.unlinkSync(target);
    return { ok: true };
  } catch (e) {
    return { ok: false, error: String(e.message || e) };
  }
}

export function archiveProjectOutputs(projectPath) {
  const cwd = path.resolve(String(projectPath || ''));
  const outputsDir = path.join(cwd, 'outputs');
  try {
    if (!fs.existsSync(outputsDir) || !fs.statSync(outputsDir).isDirectory()) {
      return Promise.resolve({ ok: false, error: 'outputs/ klasörü bulunamadı veya boş' });
    }
    const entries = fs.readdirSync(outputsDir);
    if (entries.length === 0) {
      return Promise.resolve({ ok: false, error: 'outputs/ klasörü boş' });
    }
  } catch (e) {
    return Promise.resolve({ ok: false, error: String(e.message || e) });
  }
  const name = path.basename(cwd);
  const stamp = safeArchiveStamp();
  const tmpDir = outputsTmpDir();
  fs.mkdirSync(tmpDir, { recursive: true });
  const zipPath = path.join(tmpDir, `${name}-outputs-${stamp}.zip`);
  const ps = [
    '$ErrorActionPreference = "Stop";',
    `$items = Get-ChildItem -LiteralPath '${outputsDir.replace(/'/g, "''")}' -Force;`,
    `if (-not $items) { throw 'outputs bos' };`,
    `Compress-Archive -LiteralPath ($items | ForEach-Object { $_.FullName }) -DestinationPath '${zipPath.replace(/'/g, "''")}' -Force;`,
  ].join(' ');
  return new Promise((resolve) => {
    const child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', ps], { windowsHide: true });
    let err = '';
    child.stderr.on('data', d => { err += d; });
    const timer = setTimeout(() => { try { child.kill(); } catch {} resolve({ ok: false, error: 'zip timeout' }); }, 120_000);
    child.on('close', (code) => {
      clearTimeout(timer);
      if (code === 0 && fs.existsSync(zipPath)) {
        resolve({ ok: true, path: zipPath, name: `${name}-teslimatlar.zip`, size: fs.statSync(zipPath).size });
      } else {
        resolve({ ok: false, error: (err || `powershell exit ${code}`).trim().slice(0, 300) });
      }
    });
    child.on('error', (e) => { clearTimeout(timer); resolve({ ok: false, error: e.message }); });
  });
}

function safeArchiveStamp() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${pad(d.getMonth()+1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}`;
}

function sessionSummary(session) {
  return String(session?.lastText || session?.title || '').trim().slice(0, 240);
}

function unwrapList(value, key) {
  if (Array.isArray(value)) return value;
  if (value && Array.isArray(value[key])) return value[key];
  return [];
}

const SECURITY_PROFILES = new Set(['safe', 'standard', 'full', 'custom']);
const PERMISSION_MODES = {
  safe: { 'claude-app': 'plan', 'codex-app': 'untrusted', 'opencode2-app': 'plan' },
  standard: { 'claude-app': 'default', 'codex-app': 'ask', 'opencode2-app': 'ask' },
  full: { 'claude-app': 'bypassPermissions', 'codex-app': 'yolo', 'opencode2-app': 'yolo' },
};

const DEFAULT_PROJECTS_STATE_DIR = process.env.NODE_TEST_CONTEXT
  ? path.join(os.tmpdir(), `agentbridge-projects-test-${process.pid}`)
  : path.join(os.homedir(), '.agentbridge');

export function createProjectsStore({
  modules,
  mcpModules = {},
  coworkModule,
  labels = {},
  filePath = path.join(DEFAULT_PROJECTS_STATE_DIR, 'projects.json'),
  now = () => new Date(),
  cacheTtl = 5000,
} = {}) {
  let registry = readRegistry(filePath);
  const sessionCache = new Map();
  let inventoryCache = null;

  function invalidateCache() {
    sessionCache.clear();
    inventoryCache = null;
  }

  function ensureProjectDefaults(project) {
    let changed = false;
    if (!SECURITY_PROFILES.has(project.securityProfile)) { project.securityProfile = 'standard'; changed = true; }
    if (!project.pathPolicy || typeof project.pathPolicy !== 'object') {
      project.pathPolicy = { readRoots: [project.path], writeRoots: [project.path] };
      changed = true;
    }
    if (!Array.isArray(project.audit)) { project.audit = []; changed = true; }
    if (!Array.isArray(project.observedAuditKeys)) { project.observedAuditKeys = []; changed = true; }
    if (!project.customPolicy || typeof project.customPolicy !== 'object') {
      project.customPolicy = { permissions: {}, readRoots: [project.path], writeRoots: [project.path] };
      changed = true;
    }
    if (!project.mcpProfile || typeof project.mcpProfile !== 'object') { project.mcpProfile = { provider: '', enabledNames: [] }; changed = true; }
    if (project.pinned === undefined) { project.pinned = false; changed = true; }
    if (project.lastOpenedAt === undefined) { project.lastOpenedAt = ''; changed = true; }
    if (!project.quickStart || typeof project.quickStart !== 'object') {
      project.quickStart = {
        provider: '',
        modelByProvider: {},
        permissionByProvider: {},
        effortByProvider: {}
      };
      changed = true;
    }
    return changed;
  }

  function persist() { writeRegistry(filePath, registry); }

  function audit(project, action, detail = {}) {
    project.audit.push({ id: crypto.randomUUID(), action, actor: 'user', detail, at: now().toISOString() });
    if (project.audit.length > 200) project.audit.splice(0, project.audit.length - 200);
  }

  function isWithin(root, target) {
    const rel = path.relative(path.resolve(root), path.resolve(target));
    return rel === '' || (!rel.startsWith('..' + path.sep) && rel !== '..' && !path.isAbsolute(rel));
  }

  function samePath(a, b) {
    if (!a || !b) return false;
    try { return path.resolve(String(a)).toLowerCase() === path.resolve(String(b)).toLowerCase(); }
    catch { return false; }
  }

  function observe(project, action, detail) {
    const key = crypto.createHash('sha256').update(action + JSON.stringify(detail)).digest('hex').slice(0, 24);
    if (project.observedAuditKeys.includes(key)) return false;
    project.observedAuditKeys.push(key);
    if (project.observedAuditKeys.length > 500) project.observedAuditKeys.splice(0, project.observedAuditKeys.length - 500);
    audit(project, action, detail);
    return true;
  }

  function liveSessions() {
    const result = [];
    for (const [backend, mod] of Object.entries(modules || {})) {
      let sessions = [];
      try { sessions = mod.listSessions?.() || []; } catch { continue; }
      for (const session of sessions) {
        const cwd = String(session?.cwd || '').trim();
        if (!session?.id || !cwd || !path.isAbsolute(cwd)) continue;
        // Persisted provider shells can outlive test/temp workspaces. They are
        // not live work and must not resurrect deleted project records.
        if (session.restoredShell && !fs.existsSync(cwd)) continue;
        result.push({ backend, backendLabel: labels[backend] || backend, session, cwd: path.resolve(cwd) });
      }
    }
    return result;
  }

  function isEphemeralTestProject(project) {
    if (!project?.path || fs.existsSync(project.path)) return false;
    if (!isWithin(os.tmpdir(), project.path)) return false;
    const firstSegment = path.relative(os.tmpdir(), path.resolve(project.path)).split(path.sep)[0] || '';
    return /^(cowork-project-root-|cowork-prompt-outside-)/i.test(firstSegment);
  }

  function refreshRegistry() {
    let changed = false;
    // Eski test süreçlerinin canlı kullanıcı registry'sine yazdığı, diskte
    // artık bulunmayan açıkça test amaçlı geçici kayıtları temizle.
    for (const [id, project] of Object.entries(registry)) {
      if (isEphemeralTestProject(project)) { delete registry[id]; changed = true; }
    }
    // Cowork hayalet kayıtlarını purge et: workspace'ler registry'ye canlı oturum
    // cwd'sinden otomatik düşer; klasör silinince (app'ten veya elle) kayıt burada
    // sahipsiz kalıyor ve Merkez'de var olmayan proje olarak beliriyordu (icy-urchin
    // vakası). Cowork kökü altındaki ve diskte artık olmayan yollar silinir; kök
    // dışındaki projelere dokunulmaz (exists=false ile görünmeye devam ederler).
    let coworkRootPath = '';
    try { coworkRootPath = coworkModule?.listProjects?.()?.root || ''; } catch {}
    if (coworkRootPath) {
      for (const [id, project] of Object.entries(registry)) {
        if (!project?.path || samePath(coworkRootPath, project.path)) continue;
        if (!isWithin(coworkRootPath, project.path)) continue;
        if (!fs.existsSync(project.path)) { delete registry[id]; changed = true; }
      }
    }
    for (const live of liveSessions()) {
      const id = projectId(live.cwd);
      if (!registry[id]) {
        registry[id] = {
          id,
          path: live.cwd,
          name: path.basename(live.cwd),
          label: '',
          createdAt: now().toISOString(),
          lastSeenAt: now().toISOString(),
          securityProfile: 'standard',
          pathPolicy: { readRoots: [live.cwd], writeRoots: [live.cwd] },
          audit: [],
          observedAuditKeys: [],
          customPolicy: { permissions: {}, readRoots: [live.cwd], writeRoots: [live.cwd] },
          mcpProfile: { provider: '', enabledNames: [] },
        };
        changed = true;
      }
      if (ensureProjectDefaults(registry[id])) changed = true;
    }
    for (const project of Object.values(registry)) if (ensureProjectDefaults(project)) changed = true;
    if (changed) {
      try { writeRegistry(filePath, registry); } catch {}
    }
  }

  async function buildSessionInventory() {
    const disk = [];
    for (const [backend, mod] of Object.entries(modules || {})) {
      try {
        const diskResult = await Promise.resolve(mod.listDiskSessions?.({ all: true }));
        for (const session of unwrapList(diskResult, 'sessions')) {
          disk.push({ backend, backendLabel: labels[backend] || backend, session });
        }
      } catch {
        // Bir sağlayıcının envanter hatası diğer sağlayıcıları engellemez.
      }
      // Claude normal oturum listesinden Cowork cwd'lerini bilinçli olarak ayırır.
      // Proje envanteri bu ayrı havuzu bir kez eklemezse `.cowork` metadata'sı
      // gerçek transcript ile eşleşemiyor ve proje detayında mevcut oturumlar
      // kaybolmuş gibi görünüyor. Bu tarama da provider başına yalnız bir kez
      // yapılır; Cowork projesi başına tekrar edilmez.
      if (typeof mod.listCoworkDiskSessions === 'function') {
        try {
          const coworkDiskResult = await Promise.resolve(mod.listCoworkDiskSessions({ all: true }));
          for (const session of unwrapList(coworkDiskResult, 'sessions')) {
            disk.push({ backend, backendLabel: labels[backend] || backend, session });
          }
        } catch {
          // Ayrı Cowork envanteri başarısızsa diğer sağlayıcıları yine engelleme.
        }
      }
    }
    return { live: liveSessions(), disk };
  }

  async function getSessionInventory() {
    if (cacheTtl > 0 && inventoryCache && (Date.now() - inventoryCache.timestamp) < cacheTtl) {
      return inventoryCache.value;
    }
    const value = await buildSessionInventory();
    if (cacheTtl > 0) inventoryCache = { timestamp: Date.now(), value };
    return value;
  }

  async function collectProjectSessions(project, {
    includeArchived = true,
    inventory = null,
    coworkProjects = null,
  } = {}) {
    if (cacheTtl > 0) {
      const cached = sessionCache.get(project.id);
      if (cached && (Date.now() - cached.timestamp) < cacheTtl) {
        const sessions = cached.sessions;
        return includeArchived ? sessions : sessions.filter(s => !s.archived);
      }
    }

    const unified = new Map();
    const source = inventory || await getSessionInventory();
    const live = source.live;

    const knownCoworkProjects = coworkProjects || (coworkModule ? (coworkModule.listProjects()?.projects || []) : []);
    const isCowork = knownCoworkProjects.some(cp => samePath(cp.path, project.path));

    function belongsToProject(cwd) {
      if (!cwd) return false;
      const resolved = path.resolve(cwd);
      if (isCowork) {
        return samePath(resolved, project.path) || isWithin(project.path, resolved);
      } else {
        return samePath(resolved, project.path);
      }
    }

    function getNativeSessionId(backend, session) {
      if (backend === 'codex-app' && session.threadId) return session.threadId;
      return session.id || session.sessionId || '';
    }

    // 1. Live sessions
    for (const item of live) {
      if (!belongsToProject(item.cwd)) continue;
      const session = item.session;
      if (isCowork) {
        const hasContent = Number(session.turns || 0) > 0 ||
          !!String(session.title || '').trim() || !!String(session.lastText || '').trim();
        const actionable = session.status === 'running' || !!session.awaitingApproval || !!session.awaitingUserInput;
        if (session.restoredShell && !hasContent && !actionable) continue;
      }
      const nativeSessionId = getNativeSessionId(item.backend, session);
      const key = `${item.backend}:${nativeSessionId}`;
      unified.set(key, {
        backend: item.backend,
        backendLabel: item.backendLabel,
        sessionId: session.id,
        nativeSessionId,
        model: session.model || '',
        status: session.awaitingApproval || session.awaitingUserInput ? 'waiting' : (session.status || 'idle'),
        title: String(session.title || '').trim(),
        summary: sessionSummary(session),
        cwd: item.cwd,
        // Date.now() FALLBACK'I KALDIRILDI: canli oturumlarin cogu mtime
        // yollamiyor (yalniz codex lastActivity veriyor), bu yuzden o projeler
        // her istekte "az once aktifti" gorunuyordu. lastActivityAt istegin
        // anina esitlenince Merkez'deki "Son projeler" listesi her yenilemede
        // milisaniye farkiyla yeniden diziliyordu — kullanici "haftalardir
        // dokunmadigim proje en ustte" diye bildirdi (05.08.2026). Bilinmiyorsa
        // 0: uydurma zaman damgasi yerine "veri yok". Calisan oturumlar zaten
        // runningCount ile ust siraya cikiyor.
        mtime: session.mtime || session.lastActivity || session.lastUserAt || 0,
        live: true,
        pinned: false,
        archived: false,
        container: isCowork ? 'cowork' : 'direct',
        threadId: session.threadId || '',
      });
    }

    // 2. Disk sessions from providers
    for (const item of source.disk) {
      const { backend, session: s } = item;
      if (!s?.id || !belongsToProject(s.cwd)) continue;
      const nativeSessionId = getNativeSessionId(backend, s);
      const key = `${backend}:${nativeSessionId}`;
      const existing = unified.get(key);
      if (existing) {
        existing.pinned = !!s.pinned;
        existing.archived = !!s.archived;
        if (s.mtime) existing.mtime = s.mtime;
        if (s.title && !existing.title) existing.title = String(s.title).trim();
        if (s.lastText && !existing.summary) existing.summary = String(s.lastText).trim().slice(0, 240);
      } else {
        unified.set(key, {
          backend,
          backendLabel: item.backendLabel,
          sessionId: s.id,
          nativeSessionId,
          model: s.model || '',
          status: 'disk',
          title: String(s.title || '').trim(),
          summary: String(s.lastText || '').trim().slice(0, 240),
          cwd: path.resolve(s.cwd),
          mtime: s.mtime || 0,
          live: false,
          pinned: !!s.pinned,
          archived: !!s.archived,
          container: isCowork ? 'cowork' : 'direct',
          threadId: s.threadId || '',
        });
      }
    }

    // 3. Cowork metadata enrichment
    //
    // Provider envanteri yukarıda zaten tüm native disk + canlı oturumları
    // içeriyor. Burada listResolvedProjectSessions çağırmak aynı provider
    // depolarını her Cowork projesi için yeniden tarıyordu; bazı bozuk/stale
    // native kayıtlar yüzünden /projects 17–30+ saniye boyunca event loop'u
    // bloke edebiliyordu. Ham .cowork metadata'sını yalnız mevcut native kaydı
    // zenginleştirmek için kullan; provider karşılığı olmayan hayaleti ekleme.
    if (isCowork && coworkModule) {
      try {
        const coworkResult = await Promise.resolve(
          coworkModule.listProjectSessions?.({ projectPath: project.path })
        );
        const coworkSessions = coworkResult?.sessions || [];
        for (const cs of coworkSessions) {
          const backend = cs.provider;
          const candidateIds = [...new Set([cs.threadId, cs.sessionId].filter(Boolean))];
          const existing = candidateIds
            .map(id => unified.get(`${backend}:${id}`))
            .find(Boolean);
          if (existing) {
            existing.container = 'cowork';
            if (cs.threadId) existing.threadId = cs.threadId;
            if (cs.model && !existing.model) existing.model = cs.model;
            if (cs.lastUsedAt) {
              const t = Date.parse(cs.lastUsedAt);
              if (!isNaN(t) && t > existing.mtime) existing.mtime = t;
            }
          }
        }
      } catch (e) {
        // ignore
      }
    }

    const sessions = [...unified.values()];
    sessions.sort((a, b) => {
      if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
      const aIsWaiting = a.status === 'waiting';
      const bIsWaiting = b.status === 'waiting';
      if (aIsWaiting !== bIsWaiting) return aIsWaiting ? -1 : 1;
      if (a.live !== b.live) return a.live ? -1 : 1;
      return b.mtime - a.mtime;
    });

    if (cacheTtl > 0) {
      sessionCache.set(project.id, { timestamp: Date.now(), sessions });
    }
    return includeArchived ? sessions : sessions.filter(s => !s.archived);
  }

  function computeLastActivityAt(project, sessions, coworkProjects) {
    const sessionMax = sessions.length ? Math.max(...sessions.map(s => s.mtime || 0)) : 0;
    const outputMax = Math.max(...scanOutputs(project.path).map(o => o.mtime || 0), 0);
    const openedAtVal = project.lastOpenedAt ? (Date.parse(project.lastOpenedAt) || 0) : 0;
    let coworkUpdatedVal = 0;
    if (coworkProjects) {
      const cp = coworkProjects.find(item => samePath(item.path, project.path));
      if (cp && cp.updatedAt) {
        coworkUpdatedVal = Date.parse(cp.updatedAt) || 0;
      }
    }
    const maxEpoch = Math.max(sessionMax, outputMax, openedAtVal, coworkUpdatedVal);
    return maxEpoch > 0 ? new Date(maxEpoch).toISOString() : project.lastSeenAt || '';
  }

  function ensureProjectForPath(cwd) {
    const resolved = path.resolve(cwd);
    const id = projectId(resolved);
    let changed = false;
    if (!registry[id]) {
      registry[id] = {
        id,
        path: resolved,
        name: path.basename(resolved),
        label: '',
        createdAt: now().toISOString(),
        lastSeenAt: now().toISOString(),
        securityProfile: 'standard',
        pathPolicy: { readRoots: [resolved], writeRoots: [resolved] },
        audit: [],
        observedAuditKeys: [],
        customPolicy: { permissions: {}, readRoots: [resolved], writeRoots: [resolved] },
        mcpProfile: { provider: '', enabledNames: [] },
      };
      changed = true;
    }
    if (ensureProjectDefaults(registry[id])) changed = true;
    if (changed) persist();
    return registry[id];
  }

  function get(id) {
    refreshRegistry();
    return registry[id] || null;
  }

  async function applyPreferences(params = {}) {
    refreshRegistry();
    let id = params.id;
    if (!id && params.path) {
      const p = ensureProjectForPath(params.path);
      id = p.id;
    }
    const project = registry[id];
    if (!project) return { ok: false, error: 'project not found' };

    if (params.pinned !== undefined) {
      project.pinned = !!params.pinned;
      audit(project, 'project_pinned_changed', { pinned: project.pinned });
    }
    if (params.touch === true) {
      project.lastOpenedAt = now().toISOString();
      project.lastSeenAt = now().toISOString();
    }
    if (params.quickStart && typeof params.quickStart === 'object') {
      const qs = params.quickStart;
      if (!project.quickStart) ensureProjectDefaults(project);
      if (qs.provider !== undefined) project.quickStart.provider = String(qs.provider || '');

      const provider = project.quickStart.provider || '';
      if (provider) {
        if (qs.model !== undefined) project.quickStart.modelByProvider[provider] = String(qs.model || '');
        if (qs.permissionMode !== undefined) project.quickStart.permissionByProvider[provider] = String(qs.permissionMode || '');
        if (qs.effort !== undefined) project.quickStart.effortByProvider[provider] = String(qs.effort || '');
      }
      audit(project, 'project_quickstart_preferences_changed', { quickStart: project.quickStart });
    }

    invalidateCache();
    persist();

    const coworkProjects = coworkModule ? (coworkModule.listProjects()?.projects || []) : [];
    const sessions = await collectProjectSessions(project);
    const lastActivityAt = computeLastActivityAt(project, sessions, coworkProjects);

    const summary = {
      ...project,
      displayName: project.label || project.name,
      exists: fs.existsSync(project.path),
      sessionCount: sessions.length,
      runningCount: sessions.filter(s => s.live && (s.status === 'running' || s.status === 'waiting')).length,
      outputCount: scanOutputs(project.path).length,
      newOutputCount: scanOutputs(project.path).filter(output => !project.seenOutputs?.[output.name] || output.mtime > project.seenOutputs[output.name]).length,
      providers: [...new Set(sessions.map(s => s.backend))],
      lastActivityAt,
    };

    return { ok: true, project: summary };
  }

  async function list() {
    refreshRegistry();
    const coworkProjects = coworkModule ? (coworkModule.listProjects()?.projects || []) : [];
    const inventory = await getSessionInventory();
    const projects = [];
    for (const project of Object.values(registry)) {
      const sessions = await collectProjectSessions(project, { inventory, coworkProjects });
      const lastActivityAt = computeLastActivityAt(project, sessions, coworkProjects);
      projects.push({
        ...project,
        displayName: project.label || project.name,
        exists: fs.existsSync(project.path),
        sessionCount: sessions.length,
        runningCount: sessions.filter(s => s.live && (s.status === 'running' || s.status === 'waiting')).length,
        outputCount: scanOutputs(project.path).length,
        newOutputCount: scanOutputs(project.path).filter(output => !project.seenOutputs?.[output.name] || output.mtime > project.seenOutputs[output.name]).length,
        providers: [...new Set(sessions.map(s => s.backend))],
        lastActivityAt,
      });
    }
    projects.sort((a, b) => {
      if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
      const aIsActive = a.runningCount > 0;
      const bIsActive = b.runningCount > 0;
      if (aIsActive !== bIsActive) return aIsActive ? -1 : 1;
      const aAct = a.lastActivityAt || a.lastSeenAt || '';
      const bAct = b.lastActivityAt || b.lastSeenAt || '';
      if (aAct !== bAct) return bAct.localeCompare(aAct);
      return String(a.displayName || '').localeCompare(String(b.displayName || ''));
    });
    return { ok: true, projects };
  }

  async function detail(id) {
    refreshRegistry();
    const project = registry[id];
    if (!project) return { ok: false, error: 'project not found' };

    const coworkProjects = coworkModule ? (coworkModule.listProjects()?.projects || []) : [];
    const sessions = await collectProjectSessions(project);
    const liveSessionsList = sessions.filter(s => s.live);

    const changes = [];
    const commands = [];
    const plan = [];
    for (const item of liveSessionsList) {
      const mod = modules[item.backend];
      if (!mod) continue;
      try {
        for (const change of unwrapList(mod.getChanges?.(item.sessionId), 'changes')) {
          changes.push({ backend: item.backend, ...change });
          observe(project, 'agent_file_changed', { backend: item.backend, sessionId: item.sessionId, path: change.path || '', status: change.status || '' });
        }
      } catch {}
      try {
        for (const command of unwrapList(mod.getCommands?.(item.sessionId), 'commands')) {
          commands.push({ backend: item.backend, ...command });
          observe(project, 'agent_command_observed', { backend: item.backend, sessionId: item.sessionId, command: command.command || command.text || '', status: command.status || '' });
        }
      } catch {}
      try {
        for (const step of unwrapList(mod.getPlanItems?.(item.sessionId), 'plan')) plan.push({ backend: item.backend, ...step });
      } catch {}
    }
    try { persist(); } catch {}

    const lastActivityAt = computeLastActivityAt(project, sessions, coworkProjects);

    const summary = {
      ...project,
      displayName: project.label || project.name,
      exists: fs.existsSync(project.path),
      sessionCount: sessions.length,
      runningCount: sessions.filter(s => s.live && (s.status === 'running' || s.status === 'waiting')).length,
      outputCount: scanOutputs(project.path).length,
      newOutputCount: scanOutputs(project.path).filter(output => !project.seenOutputs?.[output.name] || output.mtime > project.seenOutputs[output.name]).length,
      providers: [...new Set(sessions.map(s => s.backend))],
      lastActivityAt,
    };

    return {
      ok: true,
      project: summary,
      sessions,
      outputs: scanOutputs(project.path).map(output => ({
        ...output,
        isNew: !project.seenOutputs?.[output.name] || output.mtime > project.seenOutputs[output.name],
      })),
      artifacts: { changes, commands, plan },
      security: {
        profile: project.securityProfile,
        readRoots: project.pathPolicy.readRoots,
        writeRoots: project.pathPolicy.writeRoots,
        customPolicy: project.customPolicy,
      },
      audit: [...project.audit].reverse(),
      mcpProfile: project.mcpProfile,
    };
  }

  function setLabel(id, label, projectPath) {
    invalidateCache();
    let project = registry[id];
    if (!project && projectPath) {
      project = ensureProjectForPath(projectPath);
    }
    if (!project) return { ok: false, error: 'project not found' };
    project.label = String(label || '').trim().slice(0, 120);
    audit(project, 'project_label_changed', { label: project.label });
    try { persist(); }
    catch (e) { return { ok: false, error: String(e.message || e) }; }
    return { ok: true, project: { ...project, displayName: project.label || project.name } };
  }

  function markOutputsSeen(id) {
    invalidateCache();
    const project = registry[id];
    if (!project) return { ok: false, error: 'project not found' };
    project.seenOutputs = Object.fromEntries(scanOutputs(project.path).map(output => [output.name, output.mtime]));
    try { persist(); }
    catch (e) { return { ok: false, error: String(e.message || e) }; }
    return { ok: true };
  }

  async function applySecurityProfile(id, requestedProfile, requestedPolicy = {}) {
    invalidateCache();
    refreshRegistry();
    const project = registry[id];
    if (!project) return { ok: false, error: 'project not found' };
    const profile = String(requestedProfile || '').trim();
    if (!SECURITY_PROFILES.has(profile)) return { ok: false, error: 'invalid security profile' };
    let customPolicy = project.customPolicy;
    if (profile === 'custom') {
      const readRoots = Array.isArray(requestedPolicy.readRoots) ? requestedPolicy.readRoots.map(String) : [];
      const writeRoots = Array.isArray(requestedPolicy.writeRoots) ? requestedPolicy.writeRoots.map(String) : [];
      if (![...readRoots, ...writeRoots].every(root => path.isAbsolute(root) && isWithin(project.path, root))) {
        return { ok: false, error: 'custom paths must remain inside project root' };
      }
      const permissions = requestedPolicy.permissions && typeof requestedPolicy.permissions === 'object' ? requestedPolicy.permissions : {};
      if (!Object.values(permissions).every(level => ['safe', 'standard', 'full'].includes(level))) {
        return { ok: false, error: 'invalid custom permission level' };
      }
      customPolicy = { permissions, readRoots: readRoots.length ? readRoots : [project.path], writeRoots: writeRoots.length ? writeRoots : [project.path] };
    }
    const results = [];
    const sessions = liveSessions().filter(item => projectId(item.cwd) === id);
    for (const item of sessions) {
      const customLevel = customPolicy.permissions[item.backend];
      const mode = profile === 'custom'
        ? (PERMISSION_MODES[customLevel]?.[item.backend] || customLevel)
        : PERMISSION_MODES[profile][item.backend];
      const setter = modules[item.backend]?.setPermissionMode;
      if (!mode || typeof setter !== 'function') {
        results.push({ backend: item.backend, sessionId: item.session.id, supported: false });
        continue;
      }
      try {
        const args = item.backend === 'claude-app'
          ? { sessionId: item.session.id, mode }
          : { sessionId: item.session.id, permissionMode: mode };
        const response = await Promise.resolve(setter(args));
        if (response?.ok === false) throw new Error(response.error || 'permission update failed');
        results.push({ backend: item.backend, sessionId: item.session.id, supported: true, mode, ok: true });
      } catch (error) {
        results.push({ backend: item.backend, sessionId: item.session.id, supported: true, mode, ok: false, error: String(error?.message || error) });
      }
    }
    project.securityProfile = profile;
    project.customPolicy = customPolicy;
    project.pathPolicy = profile === 'custom'
      ? { readRoots: customPolicy.readRoots, writeRoots: customPolicy.writeRoots }
      : { readRoots: [project.path], writeRoots: [project.path] };
    audit(project, 'security_profile_applied', { profile, results });
    try { persist(); }
    catch (e) { return { ok: false, error: String(e.message || e) }; }
    return { ok: true, profile, results };
  }

  function recordFileEvent(action, target, detail = {}) {
    invalidateCache();
    const absolute = path.resolve(String(target || ''));
    const project = Object.values(registry).find(item => isWithin(item.path, absolute));
    if (!project) return { ok: false, error: 'project not found for path' };
    audit(project, action, { path: absolute, ...detail });
    try { persist(); } catch (e) { return { ok: false, error: String(e.message || e) }; }
    return { ok: true, projectId: project.id };
  }

  async function listMcpServers(id, provider) {
    refreshRegistry();
    if (!registry[id]) return { ok: false, error: 'project not found' };
    const mod = mcpModules[provider];
    if (!mod?.listServers || !mod?.toggleServer) return { ok: false, error: 'provider does not support project MCP profiles' };
    const result = await Promise.resolve(mod.listServers());
    return result?.ok === false ? result : { ok: true, servers: result.servers || [], profile: registry[id].mcpProfile };
  }

  async function applyMcpProfile(id, provider, enabledNames, confirmGlobal) {
    invalidateCache();
    refreshRegistry();
    const project = registry[id];
    if (!project) return { ok: false, error: 'project not found' };
    if (confirmGlobal !== true) return { ok: false, error: 'global MCP impact confirmation required' };
    const mod = mcpModules[provider];
    if (!mod?.listServers || !mod?.toggleServer) return { ok: false, error: 'provider does not support project MCP profiles' };
    const listed = await Promise.resolve(mod.listServers());
    if (listed?.ok === false) return listed;
    const desired = new Set(Array.isArray(enabledNames) ? enabledNames.map(String) : []);
    const results = [];
    for (const server of listed.servers || []) {
      if (server.managed) continue;
      const enabled = desired.has(server.name);
      const result = await Promise.resolve(mod.toggleServer({ name: server.name, enabled }));
      results.push({ name: server.name, enabled, ok: result?.ok === true, error: result?.error || '' });
    }
    project.mcpProfile = { provider, enabledNames: [...desired] };
    audit(project, 'mcp_profile_applied', { provider, enabledNames: [...desired], globalConfig: true, results });
    try { persist(); } catch (e) { return { ok: false, error: String(e.message || e) }; }
    return { ok: results.every(item => item.ok), results, profile: project.mcpProfile, note: 'Provider MCP config is global and affects new sessions.' };
  }

  async function deleteSessionsForPath(projectPath, { includeNested = false, requireCompleteScan = false } = {}) {
    const results = [];
    for (const [backend, mod] of Object.entries(modules || {})) {
      const targets = new Set();
      const belongsToProject = cwd => !!String(cwd || '').trim() && (includeNested ? isWithin(projectPath, cwd) : samePath(cwd, projectPath));
      try {
        for (const s of unwrapList(mod.listSessions?.(), 'sessions')) {
          if (s?.id && belongsToProject(s.cwd)) targets.add(s.id);
        }
      } catch (e) {
        if (requireCompleteScan) results.push({ backend, id: '(live-session-scan)', ok: false, error: String(e.message || e) });
      }
      try {
        if (requireCompleteScan && typeof mod.listDiskSessions !== 'function') {
          results.push({ backend, id: '(disk-session-scan)', ok: false, error: 'backend disk session scan unsupported' });
        } else {
          const disk = await Promise.resolve(mod.listDiskSessions?.({ all: true }));
          if (requireCompleteScan && disk?.ok === false) {
            results.push({ backend, id: '(disk-session-scan)', ok: false, error: String(disk.error || 'disk session scan failed') });
          } else {
            for (const s of unwrapList(disk, 'sessions')) {
              if (s?.id && belongsToProject(s.cwd)) targets.add(s.id);
            }
          }
        }
      } catch (e) {
        if (requireCompleteScan) results.push({ backend, id: '(disk-session-scan)', ok: false, error: String(e.message || e) });
      }
      if (targets.size && typeof mod.deleteDiskSession !== 'function') {
        for (const sid of targets) results.push({ backend, id: sid, ok: false, error: 'backend session deletion unsupported' });
        continue;
      }
      for (const sid of targets) {
        try {
          const r = await Promise.resolve(mod.deleteDiskSession({ id: sid }));
          results.push({ backend, id: sid, ok: r?.ok === true });
        } catch (e) {
          results.push({ backend, id: sid, ok: false, error: String(e.message || e) });
        }
      }
    }
    return results;
  }

  function removeRegistryPaths(projectPath) {
    const removedIds = [];
    for (const [projectKey, item] of Object.entries(registry)) {
      if (samePath(item.path, projectPath) || (!!String(item.path || '').trim() && isWithin(projectPath, item.path))) {
        removedIds.push(projectKey);
        delete registry[projectKey];
      }
    }
    return removedIds;
  }

  function forgetProjectPath(projectPath) {
    invalidateCache();
    const clean = String(projectPath || '').trim();
    if (!clean || !path.isAbsolute(clean)) return { ok: false, error: 'absolute project path required' };
    const removedIds = removeRegistryPaths(path.resolve(clean));
    try { persist(); } catch (e) { return { ok: false, error: String(e.message || e) }; }
    return { ok: true, path: path.resolve(clean), removedIds };
  }

  async function bulkSessionAction({ id, action, sessions }) {
    invalidateCache();
    refreshRegistry();
    const project = registry[id];
    if (!project) return { ok: false, error: 'project not found' };
    const projectPath = project.path;

    const results = [];
    let succeeded = 0;
    let failed = 0;

    for (const session of (sessions || [])) {
      const { backend, sessionId, nativeSessionId, container } = session;
      const providerSessionId = nativeSessionId || sessionId;
      let verified = false;
      const mod = modules[backend];

      if (container === 'cowork') {
        try {
          if (coworkModule && typeof coworkModule.listProjectSessions === 'function') {
            const coworkSessions = coworkModule.listProjectSessions({ projectPath }).sessions || [];
            if (coworkSessions.some(s => s.sessionId === sessionId || s.threadId === sessionId)) {
              verified = true;
            }
          }
        } catch (e) {
          console.error('[Bulk] Cowork session verification error:', e);
        }
      } else if (mod) {
        try {
          // Check live
          const liveSessions = unwrapList(mod.listSessions?.(), 'sessions');
          const live = liveSessions.find(s => s.id === sessionId);
          if (live && (samePath(live.cwd, projectPath) || isWithin(projectPath, live.cwd))) {
            verified = true;
          }

          // Check disk
          if (!verified && typeof mod.listDiskSessions === 'function') {
            const disk = await Promise.resolve(mod.listDiskSessions({ all: true }));
            const diskSessions = unwrapList(disk, 'sessions');
            const diskSession = diskSessions.find(s => s.id === sessionId);
            if (diskSession && (samePath(diskSession.cwd, projectPath) || isWithin(projectPath, diskSession.cwd))) {
              verified = true;
            }
          }
        } catch (e) {
          console.error('[Bulk] Direct session verification error:', e);
        }
      }

      if (!verified) {
        results.push({ backend, sessionId, ok: false, error: 'Session does not belong to this project or is not found' });
        failed++;
        continue;
      }

      if (action === 'delete') {
        if (container === 'cowork') {
          try {
            if (coworkModule && typeof coworkModule.deleteSession === 'function') {
              const r = await coworkModule.deleteSession({ projectPath, sessionId });
              if (r.ok) {
                results.push({ backend, sessionId, ok: true });
                succeeded++;
              } else {
                results.push({ backend, sessionId, ok: false, error: r.error || 'Cowork session deletion failed' });
                failed++;
              }
            } else {
              results.push({ backend, sessionId, ok: false, error: 'Cowork session deletion unsupported' });
              failed++;
            }
          } catch (e) {
            results.push({ backend, sessionId, ok: false, error: String(e.message || e) });
            failed++;
          }
        } else {
          try {
            if (mod && typeof mod.deleteDiskSession === 'function') {
              const r = await Promise.resolve(mod.deleteDiskSession({ id: sessionId }));
              if (r && r.ok) {
                results.push({ backend, sessionId, ok: true });
                succeeded++;
              } else {
                results.push({ backend, sessionId, ok: false, error: r?.error || 'Direct session deletion failed' });
                failed++;
              }
            } else {
              results.push({ backend, sessionId, ok: false, error: 'Direct session deletion unsupported' });
              failed++;
            }
          } catch (e) {
            results.push({ backend, sessionId, ok: false, error: String(e.message || e) });
            failed++;
          }
        }
      } else if (action === 'archive') {
        if (container === 'cowork') {
          results.push({ backend, sessionId, ok: false, error: 'Archive action unsupported for Cowork sessions' });
          failed++;
        } else {
          try {
            if (mod && typeof mod.archiveThread === 'function') {
              const r = await Promise.resolve(mod.archiveThread({ id: providerSessionId }));
              if (r && r.ok) {
                results.push({ backend, sessionId, ok: true });
                succeeded++;
              } else {
                results.push({ backend, sessionId, ok: false, error: r?.error || 'Direct session archiving failed' });
                failed++;
              }
            } else {
              results.push({ backend, sessionId, ok: false, error: 'Archiving unsupported by this backend' });
              failed++;
            }
          } catch (e) {
            results.push({ backend, sessionId, ok: false, error: String(e.message || e) });
            failed++;
          }
        }
      } else if (action === 'unarchive') {
        if (container === 'cowork') {
          results.push({ backend, sessionId, ok: false, error: 'Unarchive action unsupported for Cowork sessions' });
          failed++;
        } else {
          try {
            if (mod && typeof mod.unarchiveThread === 'function') {
              const r = await Promise.resolve(mod.unarchiveThread({ id: providerSessionId }));
              if (r && r.ok) {
                results.push({ backend, sessionId, ok: true });
                succeeded++;
              } else {
                results.push({ backend, sessionId, ok: false, error: r?.error || 'Direct session unarchiving failed' });
                failed++;
              }
            } else {
              results.push({ backend, sessionId, ok: false, error: 'Unarchiving unsupported by this backend' });
              failed++;
            }
          } catch (e) {
            results.push({ backend, sessionId, ok: false, error: String(e.message || e) });
            failed++;
          }
        }
      } else {
        results.push({ backend, sessionId, ok: false, error: `Invalid action: ${action}` });
        failed++;
      }
    }

    return {
      ok: failed === 0 || succeeded > 0,
      action,
      succeeded,
      failed,
      results
    };
  }

  async function deleteProject(id) {
    invalidateCache();
    refreshRegistry();
    const project = registry[id];
    if (!project) return { ok: false, error: 'project not found' };
    const projectPath = project.path;
    const results = await deleteSessionsForPath(projectPath);
    delete registry[id];
    try { persist(); } catch (e) { return { ok: false, error: String(e.message || e) }; }
    return { ok: true, id, path: projectPath, deleted: results.filter(r => r.ok).length, results };
  }

  async function deleteProjectCompletely(id) {
    invalidateCache();
    refreshRegistry();
    const project = registry[id];
    if (!project) return { ok: false, error: 'project not found' };
    const rawProjectPath = String(project.path || '').trim();
    if (!rawProjectPath || !path.isAbsolute(rawProjectPath)) return { ok: false, error: 'absolute project path required' };
    const projectPath = path.resolve(rawProjectPath);
    const filesystemRoot = path.parse(projectPath).root;
    const home = path.resolve(os.homedir());
    if (samePath(projectPath, filesystemRoot) || isWithin(projectPath, home)) {
      return { ok: false, error: 'protected path cannot be deleted' };
    }
    if (fs.existsSync(projectPath)) {
      try {
        if (!fs.statSync(projectPath).isDirectory()) return { ok: false, error: 'project path is not a directory' };
      } catch (e) { return { ok: false, error: String(e.message || e) }; }
    }

    const results = await deleteSessionsForPath(projectPath, { includeNested: true, requireCompleteScan: true });
    const failed = results.filter(item => !item.ok);
    const succeeded = results.filter(item => item.ok);
    if (failed.length) {
      return {
        ok: false,
        error: `${failed.length} project session(s) could not be deleted; project folder was kept`,
        path: projectPath,
        projectId: id,
        phase: 'sessions',
        folderDeleted: false,
        registryDeleted: false,
        deletedSessionCount: succeeded.length,
        deletedSessionIds: succeeded.map(item => item.id),
        removedProjectIds: [],
        sessionResults: results.map(r => ({ backend: r.backend || '', sessionId: r.sessionId || r.id || '', ok: r.ok, error: r.error || '' })),
        retryable: true,
      };
    }
    let folderDeleted = false;
    try {
      fs.rmSync(projectPath, { recursive: true, force: true, maxRetries: 8, retryDelay: 150 });
      folderDeleted = true;
    } catch (e) {
      return {
        ok: false,
        error: 'project folder could not be deleted: ' + String(e.message || e),
        path: projectPath,
        projectId: id,
        phase: 'folder',
        folderDeleted: false,
        registryDeleted: false,
        deletedSessionCount: succeeded.length,
        deletedSessionIds: succeeded.map(item => item.id),
        removedProjectIds: [],
        sessionResults: succeeded.map(r => ({ backend: r.backend || '', sessionId: r.sessionId || r.id || '', ok: true, error: '' })),
        retryable: true,
      };
    }
    if (!folderDeleted && fs.existsSync(projectPath)) {
      return {
        ok: false,
        error: 'project folder still exists after deletion',
        path: projectPath,
        projectId: id,
        phase: 'folder',
        folderDeleted: false,
        registryDeleted: false,
        deletedSessionCount: succeeded.length,
        deletedSessionIds: succeeded.map(item => item.id),
        removedProjectIds: [],
        sessionResults: succeeded.map(r => ({ backend: r.backend || '', sessionId: r.sessionId || r.id || '', ok: true, error: '' })),
        retryable: true,
      };
    }
    const removedIds = removeRegistryPaths(projectPath);
    try { persist(); } catch (e) {
      return {
        ok: false,
        error: String(e.message || e),
        path: projectPath,
        projectId: id,
        phase: 'registry',
        folderDeleted: true,
        registryDeleted: false,
        deletedSessionCount: succeeded.length,
        deletedSessionIds: succeeded.map(item => item.id),
        removedProjectIds: removedIds,
        sessionResults: succeeded.map(r => ({ backend: r.backend || '', sessionId: r.sessionId || r.id || '', ok: true, error: '' })),
        retryable: true,
      };
    }
    return {
      ok: true,
      projectId: id,
      path: projectPath,
      phase: 'complete',
      folderDeleted: true,
      registryDeleted: true,
      deletedSessionCount: succeeded.length,
      deletedSessionIds: succeeded.map(item => item.id),
      removedProjectIds: removedIds,
      sessionResults: succeeded.map(r => ({ backend: r.backend || '', sessionId: r.sessionId || r.id || '', ok: true, error: '' })),
      retryable: false,
    };
  }

  return { list, detail, get, setLabel, markOutputsSeen, applySecurityProfile, recordFileEvent, listMcpServers, applyMcpProfile, deleteProject, deleteProjectCompletely, bulkSessionAction, forgetProjectPath, refreshRegistry, ensureProjectForPath, applyPreferences };
}
