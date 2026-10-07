import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { body, rawBody, BodyTooLargeError } from '../router.mjs';
import { CMD_UNSAFE_ARG, isSafeModelId, isSafeSessionId, isStaleApproval } from '../session-utils.mjs';
import { maskToken } from '../logger.mjs';

function fakeReq(chunks, headers = {}) {
  const r = Readable.from(chunks.map(c => Buffer.from(c)));
  r.headers = headers;
  return r;
}

describe('gövde sınırı', () => {
  it('sınırı aşan gövde 413 hatasıyla kesilir', async () => {
    await assert.rejects(body(fakeReq(['{"a":"', 'x'.repeat(100), '"}']), { maxBytes: 50 }), BodyTooLargeError);
  });
  it('content-length sınırı aşıyorsa okumadan reddedilir', async () => {
    await assert.rejects(rawBody(fakeReq([], { 'content-length': '999999' }), { maxBytes: 10 }), BodyTooLargeError);
  });
  it('sınır içindeki gövde ayrıştırılır', async () => {
    assert.deepEqual(await body(fakeReq(['{"code":"123456"}']), { maxBytes: 4096 }), { code: '123456' });
  });
});

describe('onay isteği eşleşmesi', () => {
  it('farklı kimlik bayattır, aynı ya da gönderilmemiş kimlik değil', () => {
    assert.equal(isStaleApproval('r2', 'r1'), true);
    assert.equal(isStaleApproval('r1', 'r1'), false);
    assert.equal(isStaleApproval(7, '7'), false);
    assert.equal(isStaleApproval(undefined, 'r1'), false);
    assert.equal(isStaleApproval('', 'r1'), false);
  });
});

describe('kimlik ve model doğrulaması', () => {
  it('oturum kimliği: UUID ve tek parça kimlikler geçer, yol ve metakarakter geçmez', () => {
    assert.ok(isSafeSessionId('3f0c2a8e-1b2c-4d5e-8f90-a1b2c3d4e5f6'));
    assert.ok(isSafeSessionId('ses_01JABCdef'));
    for (const bad of ['a&calc/../3f0c2a8e', '../x', 'a/b', 'a\\b', 'x|y', '', 'a..b', null]) assert.equal(isSafeSessionId(bad), false, String(bad));
  });
  it('model adı: bilinen biçimler geçer, cmd metakarakterleri geçmez', () => {
    for (const ok of ['claude-opus-5-5', 'claude-opus-5-5[1m]', 'openrouter/anthropic/claude-opus', 'qwen3.8:27b', 'gpt-5.6@high']) assert.ok(isSafeModelId(ok), ok);
    for (const bad of ['opus&calc', 'opus|x', 'a b', 'x"y', '', 'a%PATH%']) assert.equal(isSafeModelId(bad), false, bad);
  });
  it('.cmd sarmalayıcısına giden metakarakterli argüman yakalanır', () => {
    for (const bad of ['a&b', 'a|b', 'a>b', 'a^b', '%x%', 'a!b', 'a"b', '(a)', 'a\nb']) assert.ok(CMD_UNSAFE_ARG.test(bad), bad);
    for (const ok of ['--model', 'claude-opus-5-5[1m]', '--resume', '3f0c2a8e-1b2c']) assert.equal(CMD_UNSAFE_ARG.test(ok), false, ok);
  });
});

describe('günlük maskelemesi', () => {
  it('bilet, Bearer ve cihaz anahtarı maskelenir', () => {
    assert.equal(maskToken('GET /x?ticket=tkt_abc&s=1'), 'GET /x?ticket=***&s=1');
    assert.equal(maskToken('authorization: Bearer abc.def-123'), 'authorization: Bearer ***');
    assert.equal(maskToken('key abk_' + 'A'.repeat(43)), 'key abk_***');
  });
});
