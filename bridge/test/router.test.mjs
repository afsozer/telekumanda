import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createRouter, json, body, rawBody } from '../router.mjs';

describe('router.mjs', () => {
  it('createRouter returns router with get, post, match', () => {
    const r = createRouter();
    assert.equal(typeof r.get, 'function');
    assert.equal(typeof r.post, 'function');
    assert.equal(typeof r.match, 'function');
  });

  it('get registers a GET route that match resolves', () => {
    const r = createRouter();
    const handler = () => 'ok';
    r.get('/test', handler);
    const match = r.match('GET', '/test');
    assert.ok(match);
    assert.equal(match.method, 'GET');
    assert.equal(match.pattern, '/test');
    assert.equal(match.handler, handler);
  });

  it('post registers a POST route that match resolves', () => {
    const r = createRouter();
    const handler = () => 'posted';
    r.post('/submit', handler);
    const match = r.match('POST', '/submit');
    assert.ok(match);
    assert.equal(match.method, 'POST');
  });

  it('match returns null for unknown path', () => {
    const r = createRouter();
    assert.equal(r.match('GET', '/nonexistent'), null);
  });

  it('match returns null for wrong method', () => {
    const r = createRouter();
    r.get('/only-get', () => {});
    assert.equal(r.match('POST', '/only-get'), null);
  });

  it('match returns null for wrong path', () => {
    const r = createRouter();
    r.get('/exact', () => {});
    assert.equal(r.match('GET', '/exact/extra'), null);
  });

  it('routes property exposes all registered routes', () => {
    const r = createRouter();
    r.get('/a', () => {});
    r.post('/b', () => {});
    assert.equal(r.routes.length, 2);
  });

  it('multiple routes with different paths are independently matchable', () => {
    const r = createRouter();
    let called = '';
    r.get('/foo', () => { called = 'foo'; });
    r.get('/bar', () => { called = 'bar'; });
    r.match('GET', '/foo').handler();
    assert.equal(called, 'foo');
    r.match('GET', '/bar').handler();
    assert.equal(called, 'bar');
  });
});

describe('json(), body(), rawBody()', () => {
  it('json sets content-type and calls end with JSON string', () => {
    const res = { headers: null, data: null, writeHead(code, headers) { this.headers = headers; }, end(d) { this.data = d; } };
    json(res, 200, { ok: true });
    assert.deepEqual(res.headers, { 'content-type': 'application/json', 'x-content-type-options': 'nosniff' });
    assert.equal(res.data, JSON.stringify({ ok: true }));
  });

  it('body parses JSON from request', async () => {
    const req = { [Symbol.asyncIterator]() { let done = false; return { next() { if (!done) { done = true; return Promise.resolve({ value: '{"key":"val"}', done: false }); } return Promise.resolve({ done: true }); } }; } };
    const result = await body(req);
    assert.deepEqual(result, { key: 'val' });
  });

  it('body returns empty object for empty request', async () => {
    const req = { [Symbol.asyncIterator]() { return { next() { return Promise.resolve({ done: true }); } }; } };
    const result = await body(req);
    assert.deepEqual(result, {});
  });

  it('rawBody returns Buffer', async () => {
    const req = { [Symbol.asyncIterator]() { let done = false; return { next() { if (!done) { done = true; return Promise.resolve({ value: Buffer.from('hello'), done: false }); } return Promise.resolve({ done: true }); } }; } };
    const result = await rawBody(req);
    assert.ok(result instanceof Buffer);
    assert.equal(result.toString(), 'hello');
  });
});
