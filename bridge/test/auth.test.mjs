import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { auth } from '../server.mjs';
import { maskToken } from '../logger.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const cfg = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'config.json'), 'utf-8'));

function fakeReq(url, headers = {}) {
  return { url, headers };
}

describe('auth()', () => {
  it('accepts valid Bearer token', () => {
    const req = fakeReq('/health', { authorization: `Bearer ${cfg.authToken}` });
    assert.equal(auth(req), true);
  });

  it('rejects missing Authorization header', () => {
    const req = fakeReq('/health', {});
    assert.equal(auth(req), false);
  });

  it('rejects wrong Bearer token', () => {
    const req = fakeReq('/health', { authorization: 'Bearer wrong-token-here' });
    assert.equal(auth(req), false);
  });

  it('rejects even a valid token in the ?token= query param (header only)', () => {
    const req = fakeReq(`/health?token=${cfg.authToken}`);
    assert.equal(auth(req), false);
  });

  it('rejects wrong token via ?token= query param', () => {
    const req = fakeReq('/health?token=bad-token');
    assert.equal(auth(req), false);
  });

  it('rejects Bearer with empty value', () => {
    const req = fakeReq('/health', { authorization: 'Bearer ' });
    assert.equal(auth(req), false);
  });

  it('accepts upgrade request with valid Bearer token', () => {
    const req = fakeReq('/claude-app/stream?session=123', { authorization: `Bearer ${cfg.authToken}` });
    assert.equal(auth(req), true);
  });

  it('rejects upgrade request with wrong Bearer token', () => {
    const req = fakeReq('/claude-app/stream?session=123', { authorization: 'Bearer wrong-token' });
    assert.equal(auth(req), false);
  });

  it('correctly masks tokens in string logs', () => {
    assert.equal(maskToken('url: http://localhost:3000/stream?token=xyz123'), 'url: http://localhost:3000/stream?token=***');
    assert.equal(maskToken('token=123&other=456'), 'token=***&other=456');
    assert.equal(maskToken('some random string without token'), 'some random string without token');
  });
});
