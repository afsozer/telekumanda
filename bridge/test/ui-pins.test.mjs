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
  writeUiPinScope('backend-model:opencode-app', ['opencode-go/glm-5.3', 'opencode/go-deepseek']);
  const r = readUiPins();
  assert.equal(r.existed, true);
  assert.deepEqual(r.pins['backend-model:opencode-app'], ['opencode-go/glm-5.3', 'opencode/go-deepseek']);
});

test('ikinci kapsam yazılınca ilkine dokunulmaz', () => {
  writeUiPinScope('backend-model:claude-app', ['claude-opus']);
  const r = readUiPins();
  assert.deepEqual(r.pins['backend-model:opencode-app'], ['opencode-go/glm-5.3', 'opencode/go-deepseek']);
  assert.deepEqual(r.pins['backend-model:claude-app'], ['claude-opus']);
});

test('aynı kapsam tekrar yazılınca SON YAZAN KAZANIR', () => {
  writeUiPinScope('backend-model:opencode-app', ['opencode/go-nano-grok']);
  const r = readUiPins();
  assert.deepEqual(r.pins['backend-model:opencode-app'], ['opencode/go-nano-grok']);
  // Diğer kapsamdan haber yok:
  assert.deepEqual(r.pins['backend-model:claude-app'], ['claude-opus']);
});

test('boş set göndermek kapsamı tamamen temizler', () => {
  writeUiPinScope('backend-model:claude-app', []);
  const r = readUiPins();
  assert.equal(Object.hasOwn(r.pins, 'backend-model:claude-app'), false);
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
