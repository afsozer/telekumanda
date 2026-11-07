// ui-pins.mjs unit testleri. Köprünün CANLI ui-pins.json'una dokunmamak için
// AGENTBRIDGE_UI_PINS ile temp dosyaya yönlendirilir
// (bkz. ui-pins.mjs pinsFile).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'agentbridge-uipins-'));
process.env.AGENTBRIDGE_UI_PINS = path.join(tmp, 'pins.json');

const { readUiPins, writeUiPinScope } = await import('../ui-pins.mjs');

const fileContent = () => JSON.parse(fs.readFileSync(process.env.AGENTBRIDGE_UI_PINS, 'utf-8'));

test('dosya yoksa existed=false, liste boş', () => {
  const r = readUiPins();
  assert.equal(r.existed, false);
  assert.deepEqual(r.pins, {});
});

test('tek kapsam yazılır ve okunur', () => {
  writeUiPinScope('backend-model:claude-app', ['fable-5/opus', 'fable-5/sonnet']);
  const r = readUiPins();
  assert.equal(r.existed, true);
  assert.deepEqual(r.pins['backend-model:claude-app'], ['fable-5/opus', 'fable-5/sonnet']);
});

test('ikinci kapsam yazılınca ilkine dokunulmaz', () => {
  writeUiPinScope('backend-agent:codex-app', ['uygulayici']);
  const r = readUiPins();
  assert.deepEqual(r.pins['backend-model:claude-app'], ['fable-5/opus', 'fable-5/sonnet']);
  assert.deepEqual(r.pins['backend-agent:codex-app'], ['uygulayici']);
});

test('aynı kapsam tekrar yazılınca SON YAZAN KAZANIR', () => {
  writeUiPinScope('backend-model:claude-app', ['fable-5/haiku']);
  const r = readUiPins();
  assert.deepEqual(r.pins['backend-model:claude-app'], ['fable-5/haiku']);
  // Diğer kapsamdan haber yok:
  assert.deepEqual(r.pins['backend-agent:codex-app'], ['uygulayici']);
});

test('boş set göndermek kapsamı tamamen temizler', () => {
  writeUiPinScope('backend-agent:codex-app', []);
  const r = readUiPins();
  assert.equal(Object.hasOwn(r.pins, 'backend-agent:codex-app'), false);
  // Diğer kapsam yerinde kalır:
  assert.deepEqual(r.pins['backend-model:claude-app'], ['fable-5/haiku']);
});

test('boşluk ve duplikasyon temizlenir', () => {
  writeUiPinScope('backend-agent:codex-app', [' a/a ', 'a/a', '', '   ']);
  assert.deepEqual(readUiPins().pins['backend-agent:codex-app'], ['a/a']);
});

test('scope zorunlu: boş veya boşluktan ibaretse atar', () => {
  assert.throws(() => writeUiPinScope('', []), /scope gerekli/);
  assert.throws(() => writeUiPinScope('   ', []), /scope gerekli/);
});

test('düzgün JSON yazıldığı kanıtlanır', () => {
  const parsed = fileContent();
  assert.ok(parsed.scopes && typeof parsed.scopes === 'object');
});
