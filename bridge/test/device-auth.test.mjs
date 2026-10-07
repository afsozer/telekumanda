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

test('revoked device key stops working and revoke is persisted', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'device-revoke-'));
  const filePath = path.join(root, 'devices.json');
  try {
    const auth = createDeviceAuth({ filePath });
    const done = auth.completePairing({ code: auth.startPairing().code, name: 'phone' });
    assert.equal(auth.revoke(done.deviceId).ok, true);
    assert.equal(auth.authenticate(done.key), null);
    assert.equal(auth.rotate(done.deviceId).ok, false);
    assert.ok(auth.list()[0].revokedAt);
    assert.equal(createDeviceAuth({ filePath }).authenticate(done.key), null);
    assert.equal(auth.revoke('missing').ok, false);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('wrong codes from one address do not lock out the owner', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'device-limit-'));
  try {
    const auth = createDeviceAuth({ filePath: path.join(root, 'devices.json') });
    const { code } = auth.startPairing();
    for (let i = 0; i < 12; i++) auth.completePairing({ code: 'x' + i, address: '100.64.0.9' });
    assert.equal(auth.completePairing({ code, address: '100.64.0.9' }).ok, false);
    assert.equal(auth.completePairing({ code, address: '100.64.0.2' }).ok, true);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('a distributed guessing burst invalidates all open codes', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'device-burst-'));
  try {
    const auth = createDeviceAuth({ filePath: path.join(root, 'devices.json') });
    const { code } = auth.startPairing();
    for (let i = 0; i < 100; i++) auth.completePairing({ code: 'y' + i, address: `10.0.0.${i}` });
    assert.equal(auth.completePairing({ code, address: '100.64.0.2' }).ok, false);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});
