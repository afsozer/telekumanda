import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createDeviceAuth } from '../device-auth.mjs';

test('pairing creates a device key and rotation immediately revokes the old key', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'device-auth-'));
  let time = Date.parse('2026-07-11T12:00:00Z');
  try {
    const auth = createDeviceAuth({ filePath: path.join(root, 'devices.json'), now: () => time });
    const pairing = auth.startPairing();
    const completed = auth.completePairing({ code: pairing.code, name: 'test phone' });
    assert.equal(completed.ok, true);
    assert.equal(auth.authenticate(completed.key).name, 'test phone');
    assert.equal(auth.completePairing({ code: pairing.code, name: 'replay' }).ok, false);
    const rotated = auth.rotate(completed.deviceId);
    assert.equal(auth.authenticate(completed.key), null);
    assert.equal(auth.authenticate(rotated.key).id, completed.deviceId);
    assert.equal(auth.list()[0].keyHash, undefined);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('pairing codes expire after five minutes', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'device-expiry-'));
  let time = 1000;
  try {
    const auth = createDeviceAuth({ filePath: path.join(root, 'devices.json'), now: () => time });
    const pairing = auth.startPairing();
    time += 5 * 60 * 1000 + 1;
    assert.equal(auth.completePairing({ code: pairing.code, name: 'late' }).ok, false);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});
