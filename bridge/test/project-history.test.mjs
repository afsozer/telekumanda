import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createProjectsStore } from '../projects.mjs';

test('project history: default preferences migration', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'project-history-'));
  const filePath = path.join(root, 'registry.json');
  // Write an old format registry.json
  const oldRegistry = {
    'proj-1': {
      id: 'proj-1',
      path: root,
      name: 'old-proj',
      securityProfile: 'standard'
    }
  };
  fs.writeFileSync(filePath, JSON.stringify(oldRegistry));

  try {
    const store = createProjectsStore({ modules: {}, filePath, cacheTtl: 0 });
    const listed = await store.list();
    const p = listed.projects[0];
    assert.equal(p.pinned, false);
    assert.equal(p.lastOpenedAt, '');
    assert.ok(p.quickStart);
    assert.equal(p.quickStart.provider, '');
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('project history: deduping live & disk sessions using nativeSessionId / threadId', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'project-history-dedupe-'));
  const filePath = path.join(root, 'registry.json');

  const modules = {
    'codex-app': {
      // Live session has threadId and its own bridge id
      listSessions: () => [{ id: 'live-uuid', cwd: root, model: 'gpt-test', status: 'running', threadId: 'thread-123' }],
      // Disk session has its id matching the threadId
      listDiskSessions: () => ({
        sessions: [
          { id: 'thread-123', cwd: root, title: 'Disk Title', lastText: 'Disk Text', mtime: 1000 }
        ]
      })
    }
  };

  try {
    const store = createProjectsStore({ modules, filePath, cacheTtl: 0 });
    const listed = await store.list();
    const projectId = listed.projects.find(p => p.path === root || p.path.toLowerCase() === root.toLowerCase()).id;

    const projDetail = await store.detail(projectId);
    // Since threadId matches, they must deduplicate into 1 session!
    assert.equal(projDetail.sessions.length, 1);
    const s = projDetail.sessions[0];
    assert.equal(s.sessionId, 'live-uuid'); // sessionId is the live one
    assert.equal(s.nativeSessionId, 'thread-123'); // nativeSessionId is threadId
    assert.equal(s.status, 'running'); // status is the live one
    assert.equal(s.title, 'Disk Title'); // title is merged from disk
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('project history: path matching inside cowork vs direct projects', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'project-history-path-'));
  const filePath = path.join(root, 'registry.json');
  const childPath = path.join(root, 'child');
  fs.mkdirSync(childPath);

  // We mock a coworkModule that lists root as a cowork project
  const coworkModule = {
    listProjects: () => ({
      ok: true,
      projects: [{ name: 'cowork-proj', path: root }]
    }),
    listProjectSessions: () => ({ ok: true, sessions: [] })
  };

  const modules = {
    'claude-app': {
      // Live session is in the child path of cowork
      listSessions: () => [
        { id: 'sess-direct', cwd: root, status: 'idle' },
        { id: 'sess-child', cwd: childPath, status: 'idle' }
      ]
    }
  };

  try {
    // 1. Direct project (no coworkModule passed)
    const storeDirect = createProjectsStore({ modules, filePath, cacheTtl: 0 });
    // First touch/register the project path
    storeDirect.ensureProjectForPath(root);
    const listedDirect = await storeDirect.list();
    const proj = listedDirect.projects.find(p => p.path.toLowerCase() === root.toLowerCase() || p.path === root);
    const projId = proj.id;
    const detailDirect = await storeDirect.detail(projId);
    // Direct project only matches exact CWD! So it should only have 1 session
    assert.equal(detailDirect.sessions.length, 1);
    assert.equal(detailDirect.sessions[0].sessionId, 'sess-direct');

    // Clear cache/registry
    fs.writeFileSync(filePath, '{}');

    // 2. Cowork project (coworkModule passed, matches path)
    const storeCowork = createProjectsStore({ modules, coworkModule, filePath, cacheTtl: 0 });
    storeCowork.ensureProjectForPath(root);
    const listedCowork = await storeCowork.list();
    const projCowork = listedCowork.projects.find(p => p.path.toLowerCase() === root.toLowerCase() || p.path === root);
    const projIdCowork = projCowork.id;
    const detailCowork = await storeCowork.detail(projIdCowork);
    // Cowork matches CWD and child CWDs! So it should have 2 sessions
    assert.equal(detailCowork.sessions.length, 2);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('project history: sorting logic', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'project-history-sort-'));
  const filePath = path.join(root, 'registry.json');

  const modules = {
    'claude-app': {
      listSessions: () => [
        { id: 'live-normal', cwd: root, status: 'idle', mtime: 1000 },
        { id: 'live-waiting', cwd: root, status: 'waiting', awaitingApproval: true, mtime: 500 }
      ],
      listDiskSessions: () => ({
        sessions: [
          { id: 'disk-pinned', cwd: root, pinned: true, mtime: 200 }
        ]
      })
    }
  };

  try {
    const store = createProjectsStore({ modules, filePath, cacheTtl: 0 });
    store.ensureProjectForPath(root);
    const listed = await store.list();
    const proj = listed.projects.find(p => p.path.toLowerCase() === root.toLowerCase() || p.path === root);
    const detail = await store.detail(proj.id);

    // Expected order:
    // 1. pinned: disk-pinned
    // 2. live/waiting: live-waiting (status === 'waiting')
    // 3. live-normal
    assert.equal(detail.sessions[0].sessionId, 'disk-pinned');
    assert.equal(detail.sessions[1].sessionId, 'live-waiting');
    assert.equal(detail.sessions[2].sessionId, 'live-normal');
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('project history: preference saving and touching', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'project-history-prefs-'));
  const filePath = path.join(root, 'registry.json');

  try {
    const store = createProjectsStore({ modules: {}, filePath, cacheTtl: 0 });
    const p = store.ensureProjectForPath(root);

    // Apply pinned and touch
    const res = await store.applyPreferences({
      id: p.id,
      pinned: true,
      touch: true,
      quickStart: {
        provider: 'claude-app',
        model: 'claude-3-opus',
        permissionMode: 'ask',
        effort: 'high'
      }
    });

    assert.equal(res.ok, true);
    assert.equal(res.project.pinned, true);
    assert.ok(res.project.lastOpenedAt);

    // Re-read registry and check if it persisted
    const store2 = createProjectsStore({ modules: {}, filePath, cacheTtl: 0 });
    const listed2 = await store2.list();
    const p2 = listed2.projects[0];
    assert.equal(p2.pinned, true);
    assert.equal(p2.quickStart.provider, 'claude-app');
    assert.equal(p2.quickStart.modelByProvider['claude-app'], 'claude-3-opus');
    assert.equal(p2.quickStart.permissionByProvider['claude-app'], 'ask');
    assert.equal(p2.quickStart.effortByProvider['claude-app'], 'high');
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
