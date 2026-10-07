import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createNotificationDelivery } from '../notification-delivery.mjs';
import { createReminders } from '../reminders.mjs';

const deviceId = 'phone-installation-1234';
const otherId = 'tablet-installation-1234';
const message = { kind: 'reminder', deliveryId: 'note-1:2026-09-09', noteId: 'notes/1', title: 'Test' };
function harness(options = {}) {
  const delivery = createNotificationDelivery(options);
  const register = (id = deviceId, model = '') => delivery.configure({ deviceId: id, model });
  return { delivery, register };
}

test('untargeted event is queued for every registered device', async () => {
  const { delivery, register } = harness();
  assert.equal(register().ok, true);
  register(otherId);
  const result = await delivery.send(message);
  assert.deepEqual(result, [
    { deviceId, ok: false, pending: true },
    { deviceId: otherId, ok: false, pending: true },
  ]);
  assert.equal(delivery.snapshot(deviceId).pushEvents[0].noteId, 'notes/1');
  assert.equal(delivery.snapshot(otherId).pushEvents.length, 1);
});

test('unregistered device sees no queue and cannot acknowledge', async () => {
  const { delivery, register } = harness(); register();
  await delivery.send(message);
  assert.deepEqual(delivery.snapshot('unknown-installation-1'), {});
  assert.equal(delivery.acknowledge('unknown-installation-1', []).ok, false);
});

test('no registered device means nothing is queued', async () => {
  const { delivery } = harness();
  assert.deepEqual(await delivery.send(message), []);
});

test('unknown explicit targets fall back to every registered device', async () => {
  const { delivery, register } = harness(); register(); register(otherId);
  assert.equal((await delivery.send(message, { targets: ['unknown'] })).length, 2);
});

test('targets restrict delivery to the named device', async () => {
  const { delivery, register } = harness(); register(); register(otherId);
  await delivery.send(message, { targets: [otherId] });
  assert.equal(delivery.snapshot(deviceId).pushEvents.length, 0);
  assert.equal(delivery.snapshot(otherId).pushEvents.length, 1);
});

test('model maps to registered devices', () => {
  const { delivery, register } = harness();
  register(deviceId, 'PTP-N49'); register(otherId, 'SM-X820');
  assert.deepEqual(delivery.deviceIdsForModel('SM-X820'), [otherId]);
  assert.deepEqual(delivery.deviceIdsForModel('X'), []);
});

test('rejects malformed device ids', () => {
  const { delivery } = harness();
  assert.equal(delivery.configure({ deviceId: 'short' }).ok, false);
  assert.equal(delivery.configure({}).ok, false);
});

test('retry before ACK returns the same event; ACK is device scoped and suppresses retries', async () => {
  const { delivery, register } = harness(); register(); register(otherId);
  await delivery.send(message, { targets: [deviceId] });
  await delivery.send(message, { targets: [deviceId] });
  const [event] = delivery.snapshot(deviceId).pushEvents;
  assert.equal(delivery.snapshot(deviceId).pushEvents.length, 1);
  delivery.acknowledge(otherId, [event.deliveryId]);
  assert.equal(delivery.snapshot(deviceId).pushEvents.length, 1);
  delivery.acknowledge(deviceId, [event.deliveryId]);
  assert.equal(delivery.snapshot(deviceId).pushEvents.length, 0);
  assert.equal((await delivery.send(message, { targets: [deviceId] }))[0].ok, true);
});

test('queue and registration survive bridge restart', async t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'notification-delivery-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const filePath = path.join(dir, 'state.json');
  const first = harness({ filePath }); first.register(deviceId, 'PTP-N49');
  await first.delivery.send(message);
  const second = harness({ filePath });
  assert.equal(second.delivery.snapshot(deviceId).pushEvents.length, 1);
  assert.deepEqual(second.delivery.deviceIdsForModel('PTP-N49'), [deviceId]);
});

test('old ADB-serial state file is migrated without crashing', async t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'notification-delivery-old-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const filePath = path.join(dir, 'state.json');
  fs.writeFileSync(filePath, JSON.stringify({
    clients: { '100.64.0.1:5555': { serial: '100.64.0.1:5555', deviceId, enabled: true } },
    messages: [
      { id: '100.64.0.1:5555:e1', serial: '100.64.0.1:5555', extras: { kind: 'note', deliveryId: 'e1' }, delivered: false, at: 1 },
      { id: '100.64.0.2:5555:e1', serial: '100.64.0.2:5555', extras: { kind: 'note', deliveryId: 'e1' }, delivered: false, at: 1 },
    ],
  }));
  const { delivery } = harness({ filePath });
  const events = delivery.snapshot(deviceId).pushEvents;
  assert.deepEqual(events.map(e => e.deliveryId), [`${deviceId}:e1`]);
  assert.equal(delivery.acknowledge(deviceId, [events[0].deliveryId]).ok, true);
});

test('corrupt state file starts empty', t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'notification-delivery-bad-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const filePath = path.join(dir, 'state.json');
  fs.writeFileSync(filePath, '{nope');
  const { delivery } = harness({ filePath });
  assert.deepEqual(delivery.snapshot(deviceId), {});
});

test('devices not seen for 30 days are retired with their queue', async () => {
  let clock = 1_000_000;
  const { delivery, register } = harness({ now: () => clock });
  register(); await delivery.send(message);
  clock += 31 * 24 * 60 * 60 * 1000;
  register(otherId);
  assert.deepEqual(delivery.snapshot(deviceId), {});
  assert.equal((await delivery.send({ ...message, deliveryId: 'x' })).length, 1);
});

test('deleted or rescheduled reminder is not offered', async () => {
  let valid = true;
  const { delivery, register } = harness({ isValid: () => valid }); register();
  await delivery.send(message);
  valid = false;
  assert.equal(delivery.snapshot(deviceId).pushEvents.length, 0);
  assert.equal((await delivery.send(message))[0].ok, true);
});

test('reminder does not burn attempts while offline, and becomes sent only after receipt', async t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'notification-reminder-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const { delivery, register } = harness(); register();
  const reminder = createReminders({ notesDir: dir, send: delivery.send, now: () => new Date('2026-09-09T10:00:00+03:00') });
  const note = reminder.create({ baslik: 'Test', tarihSaat: '2026-09-09T09:00:00+03:00', metin: 'Test' });
  for (let i = 0; i < 12; i++) await reminder.tick();
  assert.match(fs.readFileSync(note.path, 'utf8'), /reminder_state: pending/);
  assert.match(fs.readFileSync(note.path, 'utf8'), /reminder_attempts: 0/);
  const events = delivery.snapshot(deviceId).pushEvents;
  assert.equal(events.length, 1);
  delivery.acknowledge(deviceId, events.map(e => e.deliveryId));
  await reminder.tick();
  assert.match(fs.readFileSync(note.path, 'utf8'), /reminder_state: sent/);
});

// Kapsul (Android 16 Live Updates, 16.09.2026): 'started' de push'lanan bir
// kind oldu ve govdeye `title` + `startedAt` eklendi. Kuyruk bu alanlari poll
// yanitinda AYNEN tasimali — kapsulun kart basligi ve chronometer'i bunlardan besleniyor.
test('capsule fields survive the queue for a started event', async () => {
  const { delivery, register } = harness(); register();
  await delivery.send({
    kind: 'started',
    deliveryId: '2026-09-16T10:00:00.000Z-claude-app-s1-started',
    backend: 'claude-app',
    backendLabel: 'Claude',
    sessionId: 's1',
    summary: 'Kapsül işi',
    title: 'Kapsül işi',
    startedAt: '2026-09-16T10:00:00.000Z',
  });
  const [event] = delivery.snapshot(deviceId).pushEvents;
  assert.equal(event.kind, 'started');
  assert.equal(event.title, 'Kapsül işi');
  assert.equal(event.startedAt, '2026-09-16T10:00:00.000Z');
});
