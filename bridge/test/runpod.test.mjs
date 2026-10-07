import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { createRunPodManager, friendlyRunPodMessage, parseRunPodProgressLine } from '../runpod.mjs';
import { isAgentBridgeOpencodeModel } from '../opencode-model-filter.mjs';

test('parseRunPodProgressLine parses operation progress', () => {
  assert.deepEqual(
    parseRunPodProgressLine('RUNPOD_PROGRESS|ssh|[3/6] Pod SSH bağlantısı bekleniyor...'),
    { type: 'progress', step: 'ssh', message: '[3/6] Pod SSH bağlantısı bekleniyor...' },
  );
});

test('parseRunPodProgressLine parses terminal states', () => {
  assert.deepEqual(parseRunPodProgressLine('RUNPOD_READY|RunPod hazır'), { type: 'ready', message: 'RunPod hazır' });
  assert.deepEqual(parseRunPodProgressLine('RUNPOD_STOPPED|Pod EXITED'), { type: 'stopped', message: 'Pod EXITED' });
  assert.deepEqual(parseRunPodProgressLine('RUNPOD_ERROR|SSH yok'), { type: 'error', message: 'SSH yok' });
  assert.equal(parseRunPodProgressLine('normal output'), null);
});

test('friendlyRunPodMessage explains RunPod GPU capacity errors', () => {
  assert.equal(
    friendlyRunPodMessage('RunPod GraphQL hatasi: There are not enough free GPUs on the host machine to start this pod.'),
    'RunPod hostunda yeterli boş GPU yok. Birkaç dakika sonra tekrar deneyin.',
  );
  assert.equal(friendlyRunPodMessage('SSH yok'), 'SSH yok');
});

test('failed start remains visible after physical pod status refresh', async () => {
  let call = 0;
  const fakeSpawn = () => {
    const child = new EventEmitter();
    child.stdout = new PassThrough();
    child.stderr = new PassThrough();
    child.kill = () => {};
    const thisCall = call++;
    queueMicrotask(() => {
      if (thisCall === 0) {
        child.stdout.write('RUNPOD_ERROR|There are not enough free GPUs on the host machine to start this pod.\n');
        child.emit('close', 1);
      } else {
        child.stdout.write('{"ok":true,"phase":"stopped","ready":false,"podStatus":"EXITED","tunnel":false}\n');
        child.emit('close', 0);
      }
    });
    return child;
  };
  const manager = createRunPodManager(
    { enabled: true, scriptDir: 'C:/runpod' },
    { spawn: fakeSpawn, existsSync: () => true, logger: { warn() {} } },
  );

  manager.start();
  await new Promise(resolve => setImmediate(resolve));
  const status = await manager.status({ force: true });

  assert.equal(status.operationActive, false);
  assert.equal(status.phase, 'error');
  assert.equal(status.podStatus, 'EXITED');
  assert.equal(status.message, 'RunPod hostunda yeterli boş GPU yok. Birkaç dakika sonra tekrar deneyin.');
});

test('parseRunPodProgressLine parses need_console', () => {
  assert.deepEqual(
    parseRunPodProgressLine('RUNPOD_NEED_CONSOLE|https://console.runpod.io/pods'),
    { type: 'need_console', url: 'https://console.runpod.io/pods' },
  );
});

test('pod down start surfaces console redirect, not an error', async () => {
  let call = 0;
  const fakeSpawn = () => {
    const child = new EventEmitter();
    child.stdout = new PassThrough();
    child.stderr = new PassThrough();
    child.kill = () => {};
    const thisCall = call++;
    queueMicrotask(() => {
      if (thisCall === 0) {
        child.stdout.write('RUNPOD_NEED_CONSOLE|https://console.runpod.io/pods\n');
        child.emit('close', 0);
      } else {
        child.stdout.write('{"ok":true,"phase":"stopped","ready":false,"podStatus":"EXITED","tunnel":false}\n');
        child.emit('close', 0);
      }
    });
    return child;
  };
  const manager = createRunPodManager(
    { enabled: true, scriptDir: 'C:/runpod' },
    { spawn: fakeSpawn, existsSync: () => true, logger: { warn() {} } },
  );

  manager.start();
  await new Promise(resolve => setImmediate(resolve));
  const status = await manager.status({ force: true });

  assert.equal(status.phase, 'need_console');
  assert.equal(status.consoleUrl, 'https://console.runpod.io/pods');
  assert.equal(status.ready, false);
  assert.notEqual(status.phase, 'error');
});

test('status exposes RunPod balance without leaking credentials', async () => {
  const fakeSpawn = () => {
    const child = new EventEmitter();
    child.stdout = new PassThrough();
    child.stderr = new PassThrough();
    child.kill = () => {};
    queueMicrotask(() => {
      child.stdout.write('{"ok":true,"phase":"ready","ready":true,"podStatus":"RUNNING","tunnel":true,"billingAvailable":true,"clientBalance":12.34,"currentSpendPerHr":0.47,"spendLimit":80}\n');
      child.emit('close', 0);
    });
    return child;
  };
  const manager = createRunPodManager(
    { enabled: true, scriptDir: 'C:/runpod' },
    { spawn: fakeSpawn, existsSync: () => true },
  );

  const status = await manager.status({ force: true });

  assert.equal(status.billingAvailable, true);
  assert.equal(status.clientBalance, 12.34);
  assert.equal(status.currentSpendPerHr, 0.47);
  assert.equal(status.spendLimit, 80);
  assert.equal(Object.hasOwn(status, 'apiKey'), false);
});

test('OpenCode model catalog: esikler disinda kalan her saglayici gecer', () => {
  assert.equal(isAgentBridgeOpencodeModel('runpod/runpod'), true);
  assert.equal(isAgentBridgeOpencodeModel('deepseek/deepseek-flash'), true);
  // OpenCode Go duz abonelik: hicbir model elenmez.
  assert.equal(isAgentBridgeOpencodeModel('opencode-go/deepseek-v4-pro'), true);
  assert.equal(isAgentBridgeOpencodeModel('opencode-go/qwen3.8-max'), true);
  // OpenCode Zen token basina odenir: yalniz bedava modeller gecer. Fiyat
  // kaydi yokken ad kurali + bilinen eksiz istisnalar karar verir.
  assert.equal(isAgentBridgeOpencodeModel('opencode/big-pickle'), true);
  assert.equal(isAgentBridgeOpencodeModel('opencode/deepseek-flash-free'), true);
  assert.equal(isAgentBridgeOpencodeModel('opencode/deepseek-flash'), false);
  assert.equal(isAgentBridgeOpencodeModel('opencode/claude-opus-5'), false);
  assert.equal(isAgentBridgeOpencodeModel('opencode/gpt-5.5-pro'), false);
  // "-free" eki yalniz Zen'de anlamli; Go'da eleme yapmaz.
  assert.equal(isAgentBridgeOpencodeModel('opencode-go/glm-5.3'), true);
  // Surum elemesi: DEPRECATED iki deepseek modeli.
  assert.equal(isAgentBridgeOpencodeModel('deepseek/deepseek-chat'), false);
  assert.equal(isAgentBridgeOpencodeModel('deepseek/deepseek-reasoner'), false);
  // Saglayici karasi KALDIRILDI (27.08.2026): openrouter/openai artik gecer —
  // kalabalik yildiz + aramayla yonetilir, sabit kara listeyle degil.
  assert.equal(isAgentBridgeOpencodeModel('openrouter/anthropic/claude-opus'), true);
  assert.equal(isAgentBridgeOpencodeModel('openai/gpt-5.6-terra'), true);
  // Saglayicisiz kimlik kabul edilmez.
  assert.equal(isAgentBridgeOpencodeModel('gpt-5.6'), false);
  assert.equal(isAgentBridgeOpencodeModel(''), false);
});
