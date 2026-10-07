import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createProjectsStore } from '../projects.mjs';

test('projects store groups provider sessions by cwd and exposes artifacts', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-store-'));
  const outputs = path.join(root, 'outputs');
  fs.mkdirSync(outputs);
  fs.writeFileSync(path.join(outputs, 'report.docx'), 'report');
  const modules = {
    'codex-app': {
      listSessions: () => [{ id: 'c1', cwd: root, model: 'gpt-test', status: 'running', lastText: 'Implement feature' }],
      getChanges: () => ({ ok: true, changes: [{ path: 'src/app.kt', status: 'modified' }] }),
      getCommands: () => ({ ok: true, commands: [{ command: 'gradlew test' }] }),
      getPlanItems: () => ({ ok: true, plan: [{ text: 'Run tests', status: 'completed' }] }),
    },
    'claude-app': {
      listSessions: () => [{ id: 'a1', cwd: root, model: 'sonnet', status: 'idle' }],
    },
  };
  const filePath = path.join(root, 'registry.json');
  try {
    const store = createProjectsStore({ modules, labels: { 'codex-app': 'Codex', 'claude-app': 'Claude' }, filePath, cacheTtl: 0 });
    const listed = await store.list();
    assert.equal(listed.projects.length, 1);
    assert.equal(listed.projects[0].sessionCount, 2);
    assert.equal(listed.projects[0].runningCount, 1);
    assert.equal(listed.projects[0].outputCount, 1);
    assert.equal(listed.projects[0].newOutputCount, 1);

    const detail = await store.detail(listed.projects[0].id);
    assert.equal(detail.sessions.length, 2);
    assert.equal(detail.outputs[0].name, 'report.docx');
    assert.equal(detail.outputs[0].isNew, true);
    assert.equal(detail.artifacts.changes[0].path, 'src/app.kt');
    assert.equal(detail.artifacts.commands[0].command, 'gradlew test');
    assert.equal(detail.artifacts.plan[0].text, 'Run tests');

    assert.equal(store.setLabel(listed.projects[0].id, 'My Project').ok, true);
    assert.equal((await store.list()).projects[0].displayName, 'My Project');
    assert.equal(store.markOutputsSeen(listed.projects[0].id).ok, true);
    assert.equal((await store.list()).projects[0].newOutputCount, 0);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('projects registry remains visible after live sessions disappear', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-persist-'));
  let sessions = [{ id: 's1', cwd: root, status: 'idle' }];
  const store = createProjectsStore({
    modules: { agy: { listSessions: () => sessions } },
    filePath: path.join(root, 'registry.json'),
    cacheTtl: 0,
  });
  try {
    const id = (await store.list()).projects[0].id;
    sessions = [];
    const listed = await store.list();
    assert.equal(listed.projects[0].id, id);
    assert.equal(listed.projects[0].sessionCount, 0);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('project list builds each provider disk inventory only once', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-inventory-'));
  let scans = 0;
  try {
    const dirs = ['one', 'two', 'three'].map(name => {
      const dir = path.join(root, name);
      fs.mkdirSync(dir);
      return dir;
    });
    const store = createProjectsStore({
      modules: {
        'codex-app': {
          listSessions: () => [],
          listDiskSessions: () => {
            scans++;
            return { sessions: dirs.map((cwd, i) => ({ id: `disk-${i}`, cwd })) };
          },
        },
      },
      filePath: path.join(root, 'registry.json'),
      cacheTtl: 0,
    });
    for (const dir of dirs) store.ensureProjectForPath(dir);

    const listed = await store.list();
    assert.equal(listed.projects.length, 3);
    assert.equal(scans, 1, 'provider inventory must not be rescanned per project');
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('cowork project detail treats native cwd history as cowork and drops empty shells', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-cowork-native-'));
  let resolvedCalls = 0;
  try {
    const native = {
      id: 'native-codex-thread', cwd: root, title: 'Örnek görev başlığı',
      lastText: 'Kaynakları incele', mtime: 123,
    };
    const modules = {
      'codex-app': {
        listSessions: () => [{ id: 'ghost-shell', cwd: root, status: 'idle', turns: 0, title: '', lastText: '', restoredShell: true }],
        listDiskSessions: () => ({ ok: true, sessions: [native] }),
      },
    };
    const coworkModule = {
      listProjects: () => ({ ok: true, root: path.dirname(root), projects: [{ name: path.basename(root), path: root }] }),
      listProjectSessions: () => ({
        ok: true,
        sessions: [{ provider: 'codex-app', sessionId: 'old-bridge-shell', threadId: native.id, cwd: root, model: 'gpt-cowork' }],
      }),
      listResolvedProjectSessions: async () => {
        resolvedCalls++;
        throw new Error('project store must not rescan native provider histories');
      },
    };
    const store = createProjectsStore({
      modules,
      coworkModule,
      filePath: path.join(root, 'registry.json'),
      cacheTtl: 0,
    });
    const project = store.ensureProjectForPath(root);
    const detail = await store.detail(project.id);

    assert.equal(detail.sessions.length, 1);
    assert.equal(detail.sessions[0].sessionId, native.id);
    assert.equal(detail.sessions[0].title, 'Örnek görev başlığı');
    assert.equal(detail.sessions[0].container, 'cowork');
    assert.equal(detail.sessions[0].model, 'gpt-cowork');
    assert.equal(resolvedCalls, 0);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('cowork project detail includes a provider separate cowork disk inventory', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-cowork-split-inventory-'));
  let normalScans = 0;
  let coworkScans = 0;
  let resolvedCalls = 0;
  try {
    const native = {
      id: 'claude-cowork-session',
      cwd: root,
      title: 'Tahliye davası dava açma süresi',
      lastText: 'Dilekçe taslağı hazırlandı',
      mtime: 456,
    };
    const modules = {
      'claude-app': {
        listSessions: () => [],
        // Claude'un normal disk listesi Cowork cwd'lerini bilinçli olarak içermez.
        listDiskSessions: () => {
          normalScans++;
          return { ok: true, sessions: [] };
        },
        listCoworkDiskSessions: () => {
          coworkScans++;
          return { ok: true, sessions: [native] };
        },
      },
    };
    const coworkModule = {
      listProjects: () => ({
        ok: true,
        root: path.dirname(root),
        projects: [{ name: path.basename(root), path: root }],
      }),
      listProjectSessions: () => ({
        ok: true,
        sessions: [{
          provider: 'claude-app',
          sessionId: native.id,
          cwd: root,
          model: 'claude-opus-5',
        }],
      }),
      listResolvedProjectSessions: async () => {
        resolvedCalls++;
        throw new Error('project store must use the shared provider inventory');
      },
    };
    const store = createProjectsStore({
      modules,
      coworkModule,
      filePath: path.join(root, 'registry.json'),
      cacheTtl: 0,
    });
    const project = store.ensureProjectForPath(root);
    const detail = await store.detail(project.id);

    assert.equal(detail.sessions.length, 1);
    assert.equal(detail.sessions[0].backend, 'claude-app');
    assert.equal(detail.sessions[0].sessionId, native.id);
    assert.equal(detail.sessions[0].title, native.title);
    assert.equal(detail.sessions[0].container, 'cowork');
    assert.equal(detail.sessions[0].model, 'claude-opus-5');
    assert.equal(normalScans, 1, 'normal inventory scans once');
    assert.equal(coworkScans, 1, 'separate cowork inventory must scan only once');
    assert.equal(resolvedCalls, 0);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('security profiles are explicitly mapped per provider and audited', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-security-'));
  const calls = [];
  const session = backend => [{ id: `${backend}-1`, cwd: root, status: 'idle' }];
  const modules = Object.fromEntries(['claude-app', 'codex-app', 'opencode2-app'].map(backend => [backend, {
    listSessions: () => session(backend),
    setPermissionMode: args => { calls.push({ backend, args }); return { ok: true }; },
  }]));
  const filePath = path.join(root, 'registry.json');
  try {
    const store = createProjectsStore({ modules, filePath, now: () => new Date('2026-07-11T12:00:00.000Z'), cacheTtl: 0 });
    const id = (await store.list()).projects[0].id;
    const safe = await store.applySecurityProfile(id, 'safe');
    assert.equal(safe.ok, true);
    assert.deepEqual(calls.map(call => [call.backend, call.args.mode || call.args.permissionMode]), [
      ['claude-app', 'plan'], ['codex-app', 'untrusted'], ['opencode2-app', 'plan'],
    ]);

    calls.length = 0;
    await store.applySecurityProfile(id, 'standard');
    assert.deepEqual(calls.map(call => [call.backend, call.args.mode || call.args.permissionMode]), [
      ['claude-app', 'default'], ['codex-app', 'ask'], ['opencode2-app', 'ask'],
    ]);
    const detail = await store.detail(id);
    assert.equal(detail.security.profile, 'standard');
    assert.deepEqual(detail.security.readRoots, [root]);
    assert.equal(detail.audit[0].action, 'security_profile_applied');
    assert.equal(detail.audit.length, 2);

    const reloaded = createProjectsStore({ modules: {}, filePath, cacheTtl: 0 });
    assert.equal((await reloaded.detail(id)).security.profile, 'standard');
    assert.equal((await reloaded.detail(id)).audit.length, 2);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('custom policy stays inside project and audits agent and user file events once', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-custom-'));
  const nested = path.join(root, 'src');
  fs.mkdirSync(nested);
  const modules = { 'codex-app': {
    listSessions: () => [{ id: 'c1', cwd: root }],
    setPermissionMode: () => ({ ok: true }),
    getCommands: () => ({ commands: [{ command: 'npm test', status: 'completed' }] }),
    getChanges: () => ({ changes: [{ path: 'src/app.js', status: 'modified' }] }),
  } };
  try {
    const store = createProjectsStore({ modules, filePath: path.join(root, 'registry.json'), cacheTtl: 0 });
    const id = (await store.list()).projects[0].id;
    const applied = await store.applySecurityProfile(id, 'custom', {
      permissions: { 'codex-app': 'standard' }, readRoots: [root], writeRoots: [nested],
    });
    assert.equal(applied.ok, true);
    assert.deepEqual((await store.detail(id)).security.writeRoots, [nested]);
    await store.detail(id);
    assert.equal((await store.detail(id)).audit.filter(event => event.action === 'agent_command_observed').length, 1);
    assert.equal((await store.detail(id)).audit.filter(event => event.action === 'agent_file_changed').length, 1);
    assert.equal(store.recordFileEvent('user_file_uploaded', path.join(nested, 'x.txt')).ok, true);
    assert.equal((await store.detail(id)).audit[0].action, 'user_file_uploaded');
    const outside = await store.applySecurityProfile(id, 'custom', { readRoots: [os.tmpdir()], writeRoots: [root] });
    assert.equal(outside.ok, false);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('project MCP profile requires explicit global confirmation and persists selection', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-mcp-'));
  const toggles = [];
  try {
    const store = createProjectsStore({
      modules: { 'codex-app': { listSessions: () => [{ id: 's1', cwd: root }] } },
      mcpModules: { 'codex-app': {
        listServers: () => ({ ok: true, servers: [{ name: 'docs', enabled: false }, { name: 'search', enabled: true }] }),
        toggleServer: args => { toggles.push(args); return { ok: true }; },
      } },
      filePath: path.join(root, 'registry.json'),
      cacheTtl: 0,
    });
    const id = (await store.list()).projects[0].id;
    assert.equal((await store.applyMcpProfile(id, 'codex-app', ['docs'], false)).ok, false);
    const applied = await store.applyMcpProfile(id, 'codex-app', ['docs'], true);
    assert.equal(applied.ok, true);
    assert.deepEqual(toggles, [{ name: 'docs', enabled: true }, { name: 'search', enabled: false }]);
    assert.deepEqual((await store.detail(id)).mcpProfile.enabledNames, ['docs']);
    assert.equal((await store.detail(id)).audit[0].action, 'mcp_profile_applied');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('deleteProject removes session history across backends but never the folder', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-del-'));
  const other = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-other-'));
  const deleted = [];
  try {
    // deleteDiskSession canlı oturumu da kapatır → listSessions onu artık döndürmez.
    let claudeLive = [{ id: 'live1', cwd: root }];
    const claude = {
      listSessions: () => claudeLive,
      listDiskSessions: () => ({ sessions: [
        { id: 'disk1', cwd: root },
        { id: 'other1', cwd: other },   // başka projeye ait — silinMEmeli
      ] }),
      deleteDiskSession: ({ id }) => {
        deleted.push({ id });
        claudeLive = claudeLive.filter(s => s.id !== id);
        return { ok: true };
      },
    };
    // deleteDiskSession'ı olmayan backend atlanır, hata vermez.
    const noDelete = { listSessions: () => [] };
    const store = createProjectsStore({
      modules: { 'claude-app': claude, 'opencode2-app': noDelete },
      labels: { 'claude-app': 'Claude' },
      filePath: path.join(root, 'registry.json'),
      cacheTtl: 0,
    });
    const id = (await store.list()).projects[0].id;
    const result = await store.deleteProject(id);

    assert.equal(result.ok, true);
    // Canlı oturum + her iki hesabın disk oturumu (yalnız bu projeye ait) silindi.
    assert.deepEqual(deleted.map(d => d.id).sort(), ['disk1', 'live1']);
    assert.equal(deleted.some(d => d.id.startsWith('other')), false);
    // Proje izleme listesinden kalktı ama KLASÖR diskte duruyor.
    assert.equal((await store.list()).projects.length, 0);
    assert.equal(fs.existsSync(root), true);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
    fs.rmSync(other, { recursive: true, force: true });
  }
});

test('deleteProjectCompletely removes the project tree, nested sessions, and registry rows', async () => {
  const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-full-del-'));
  const project = path.join(sandbox, 'target-project');
  const nested = path.join(project, 'packages', 'child');
  const other = path.join(sandbox, 'other-project');
  fs.mkdirSync(nested, { recursive: true });
  fs.mkdirSync(other, { recursive: true });
  fs.writeFileSync(path.join(project, 'keep-no-more.txt'), 'delete me');
  let sessions = [
    { id: 'root-session', cwd: project },
    { id: 'nested-session', cwd: nested },
    { id: 'other-session', cwd: other },
  ];
  const deleted = [];
  try {
    const mod = {
      listSessions: () => sessions,
      listDiskSessions: () => ({ sessions }),
      deleteDiskSession: ({ id }) => {
        deleted.push(id);
        sessions = sessions.filter(item => item.id !== id);
        return { ok: true };
      },
    };
    const store = createProjectsStore({
      modules: { test: mod },
      filePath: path.join(sandbox, 'registry.json'),
      cacheTtl: 0,
    });
    const id = (await store.list()).projects.find(item => item.path === project).id;
    const result = await store.deleteProjectCompletely(id);

    assert.equal(result.ok, true);
    assert.deepEqual(deleted.sort(), ['nested-session', 'root-session']);
    assert.equal(fs.existsSync(project), false);
    assert.equal(fs.existsSync(other), true);
    assert.deepEqual((await store.list()).projects.map(item => item.path), [other]);
  } finally {
    fs.rmSync(sandbox, { recursive: true, force: true });
  }
});

test('deleteProjectCompletely refuses the filesystem root and user home', async () => {
  const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-protected-'));
  let sessions = [{ id: 'home-session', cwd: os.homedir() }];
  try {
    const store = createProjectsStore({
      modules: { test: { listSessions: () => sessions } },
      filePath: path.join(sandbox, 'registry.json'),
      cacheTtl: 0,
    });
    const homeId = (await store.list()).projects[0].id;
    const homeResult = await store.deleteProjectCompletely(homeId);
    assert.equal(homeResult.ok, false);
    assert.match(homeResult.error, /protected path/);

    sessions = [{ id: 'root-session', cwd: path.parse(os.homedir()).root }];
    const rootId = (await store.list()).projects.find(item => item.path === path.parse(os.homedir()).root).id;
    const rootResult = await store.deleteProjectCompletely(rootId);
    assert.equal(rootResult.ok, false);
    assert.match(rootResult.error, /protected path/);
  } finally {
    fs.rmSync(sandbox, { recursive: true, force: true });
  }
});

test('deleteProjectCompletely keeps the folder when any session cannot be deleted', async () => {
  const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-full-fail-'));
  const project = path.join(sandbox, 'target-project');
  fs.mkdirSync(project);
  try {
    const session = { id: 'locked-session', cwd: project };
    const store = createProjectsStore({
      modules: {
        test: {
          listSessions: () => [session],
          listDiskSessions: () => ({ sessions: [session] }),
          deleteDiskSession: () => ({ ok: false, error: 'locked' }),
        },
      },
      filePath: path.join(sandbox, 'registry.json'),
      cacheTtl: 0,
    });
    const id = (await store.list()).projects[0].id;
    const result = await store.deleteProjectCompletely(id);
    assert.equal(result.ok, false);
    assert.match(result.error, /folder was kept/);
    assert.equal(fs.existsSync(project), true);
    assert.equal((await store.list()).projects.some(item => item.id === id), true);
  } finally {
    fs.rmSync(sandbox, { recursive: true, force: true });
  }
});

test('bulkSessionAction verifies cwd, routes to correct delegate, and handles unsupported actions gracefully', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-bulk-'));
  const other = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-bulk-other-'));
  const deleted = [];
  const archived = [];
  const unarchived = [];
  const coworkDeleted = [];

  try {
    const claude = {
      listSessions: () => [
        { id: 's1', cwd: root },
        { id: 's2', cwd: other }, // outside
      ],
      listDiskSessions: () => ({ sessions: [{ id: 's1', cwd: root }] }),
      deleteDiskSession: ({ id }) => {
        deleted.push({ id });
        return { ok: true };
      },
      archiveThread: ({ id }) => {
        archived.push(id);
        return { ok: true };
      },
      unarchiveThread: ({ id }) => {
        unarchived.push(id);
        return { ok: true };
      },
    };

    const agy = {
      listSessions: () => [{ id: 's3', cwd: root }],
      listDiskSessions: () => ({ sessions: [{ id: 's3', cwd: root }] }),
      // deleteDiskSession and archive/unarchive are undefined
    };

    const coworkModule = {
      listProjects: () => ({ projects: [] }),
      listProjectSessions: ({ projectPath }) => ({
        ok: true,
        sessions: [{ sessionId: 'cw1', threadId: 'cw1' }]
      }),
      deleteSession: async ({ projectPath, sessionId }) => {
        coworkDeleted.push({ projectPath, sessionId });
        return { ok: true };
      }
    };

    const store = createProjectsStore({
      modules: { 'claude-app': claude, agy },
      coworkModule,
      filePath: path.join(root, 'registry.json'),
      cacheTtl: 0,
    });

    const projects = (await store.list()).projects;
    const project = projects.find(p => p.path === root);
    const projectId = project.id;

    // Test 1: Deleting verified sessions
    const r1 = await store.bulkSessionAction({
      id: projectId,
      action: 'delete',
      sessions: [
        { backend: 'claude-app', sessionId: 's1', nativeSessionId: 'native-s1', container: 'direct' }, // ok
        { backend: 'claude-app', sessionId: 's2', container: 'direct' }, // outside path -> fail
        { backend: 'agy', sessionId: 's3', container: 'direct' }, // deletion unsupported -> fail
        { backend: 'claude-app', sessionId: 'cw1', container: 'cowork' }, // cowork ok
      ]
    });

    assert.equal(r1.ok, true); // true since some succeeded
    assert.equal(r1.succeeded, 2);
    assert.equal(r1.failed, 2);
    assert.deepEqual(deleted, [{ id: 's1' }]);
    assert.deepEqual(coworkDeleted, [{ projectPath: root, sessionId: 'cw1' }]);

    const s2Result = r1.results.find(r => r.sessionId === 's2');
    assert.match(s2Result.error, /Session does not belong/);

    const s3Result = r1.results.find(r => r.sessionId === 's3');
    assert.match(s3Result.error, /deletion unsupported/);

    // Test 2: Archiving/unarchiving
    const r2 = await store.bulkSessionAction({
      id: projectId,
      action: 'archive',
      sessions: [
        { backend: 'claude-app', sessionId: 's1', nativeSessionId: 'native-s1', container: 'direct' }, // ok
        { backend: 'agy', sessionId: 's3', container: 'direct' }, // unsupported -> fail
      ]
    });
    assert.equal(r2.succeeded, 1);
    assert.equal(r2.failed, 1);
    assert.deepEqual(archived, ['native-s1']);

    const r3 = await store.bulkSessionAction({
      id: projectId,
      action: 'unarchive',
      sessions: [
        { backend: 'claude-app', sessionId: 's1', nativeSessionId: 'native-s1', container: 'direct' }, // ok
      ]
    });
    assert.equal(r3.succeeded, 1);
    assert.deepEqual(unarchived, ['native-s1']);

  } finally {
    fs.rmSync(root, { recursive: true, force: true });
    fs.rmSync(other, { recursive: true, force: true });
  }
});

test('setLabel creates a registry record when a Cowork-only path is renamed', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-label-path-'));
  try {
    const store = createProjectsStore({ modules: {}, filePath: path.join(root, 'registry.json'), cacheTtl: 0 });
    const result = store.setLabel('', 'Dava dosyası', root);

    assert.equal(result.ok, true);
    assert.equal(result.project.displayName, 'Dava dosyası');
    const listed = await store.list();
    assert.equal(listed.projects.length, 1);
    assert.equal(listed.projects[0].path, root);
    assert.equal(listed.projects[0].displayName, 'Dava dosyası');
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('cowork kökü altındaki silinmiş klasör kayıtları purge edilir', async () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-cowork-purge-'));
  const coworkRoot = path.join(tmp, 'CoworkSpaces');
  const wsPath = path.join(coworkRoot, 'ghost-ws');
  fs.mkdirSync(wsPath, { recursive: true });
  const otherPath = path.join(tmp, 'normal-proj');
  fs.mkdirSync(otherPath);
  let sessions = [
    { id: 's1', cwd: wsPath, status: 'idle' },
    { id: 's2', cwd: otherPath, status: 'idle' },
  ];
  const store = createProjectsStore({
    modules: { 'claude-app': { listSessions: () => sessions } },
    coworkModule: { listProjects: () => ({ ok: true, root: coworkRoot, projects: [] }) },
    filePath: path.join(tmp, 'registry.json'),
    cacheTtl: 0,
  });
  try {
    assert.equal((await store.list()).projects.length, 2);

    // Canlı oturumlar bitti, workspace klasörü silindi (app'ten "Tamamen sil"
    // veya elle) → cowork kaydı hayalet olarak kalmamalı. Kök dışındaki proje
    // klasörü silinse bile kaydı korunur (exists=false ile görünür).
    sessions = [];
    fs.rmSync(wsPath, { recursive: true, force: true });
    fs.rmSync(otherPath, { recursive: true, force: true });
    const listed = await store.list();
    assert.equal(listed.projects.length, 1);
    assert.equal(listed.projects[0].path, otherPath);
    assert.equal(listed.projects[0].exists, false);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('silinmiş test workspace kayıtları purge edilir, normal eksik proje korunur', async () => {
  const stateRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-test-ghost-state-'));
  const ghost = fs.mkdtempSync(path.join(os.tmpdir(), 'cowork-project-root-'));
  const ordinaryMissing = path.join(stateRoot, 'ordinary-missing-project');
  try {
    const store = createProjectsStore({
      modules: {},
      filePath: path.join(stateRoot, 'registry.json'),
      cacheTtl: 0,
    });
    store.ensureProjectForPath(ghost);
    store.ensureProjectForPath(ordinaryMissing);
    fs.rmSync(ghost, { recursive: true, force: true });

    const listed = await store.list();
    assert.equal(listed.projects.some(project => project.path === ghost), false);
    assert.equal(listed.projects.some(project => project.path === ordinaryMissing), true);
  } finally {
    fs.rmSync(ghost, { recursive: true, force: true });
    fs.rmSync(stateRoot, { recursive: true, force: true });
  }
});

// Canli hata (05.08.2026): mtime yollamayan canli oturumlar icin Date.now()
// kullaniliyordu, boylece o projeler HER istekte "az once aktifti" gorunuyor ve
// Merkez'deki liste milisaniye farkiyla yeniden diziliyordu — kullanici
// "haftalardir dokunmadigim proje en ustte, sira her seferinde degisiyor" dedi.
test('lastActivityAt zaman damgasi olmayan canli oturumda SABIT kalir', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-activity-'));
  const modules = {
    // Hicbir zaman alani yok: gercek dunyada claude/opencode boyle donuyor.
    'claude-app': { listSessions: () => [{ id: 'a1', cwd: root, status: 'idle' }] },
  };
  try {
    const store = createProjectsStore({
      modules, labels: {}, filePath: path.join(root, 'registry.json'), cacheTtl: 0,
    });
    const first = (await store.list()).projects.find(p => p.path === root);
    await new Promise(r => setTimeout(r, 30));
    const second = (await store.list()).projects.find(p => p.path === root);
    // Kullanicinin gordugu ozellik BU: iki yenileme arasinda deger oynamamali,
    // yoksa siralama her acilista degisiyor.
    assert.equal(second.lastActivityAt, first.lastActivityAt);
    // Ve deger kaydin kendi zamani olmali, istegin ani degil.
    assert.equal(second.lastActivityAt, second.lastSeenAt);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('canli oturumun bildirdigi zaman damgasi KULLANILIR', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'projects-activity2-'));
  const stamp = Date.parse('2026-03-04T05:06:07.000Z');
  const modules = {
    // codex-app lastActivity yolluyor; mtime yollamayan digerleri lastUserAt.
    'codex-app': { listSessions: () => [{ id: 'c1', cwd: root, status: 'idle', lastActivity: stamp }] },
    'claude-app': { listSessions: () => [{ id: 'a1', cwd: root, status: 'idle', lastUserAt: stamp - 1000 }] },
  };
  try {
    const store = createProjectsStore({
      modules, labels: {}, filePath: path.join(root, 'registry.json'), cacheTtl: 0,
    });
    const listed = await store.list();
    const project = listed.projects.find(p => p.path === root);
    assert.equal(Date.parse(project.lastActivityAt), stamp);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
