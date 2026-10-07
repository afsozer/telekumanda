import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { once } from 'node:events';
import { OmpRpcClient } from '../omp-rpc-client.mjs';

const fixture = path.join(path.dirname(fileURLToPath(import.meta.url)), 'fixtures', 'fake-omp-rpc.mjs');
function client(scenario = 'normal', options = {}) {
  return new OmpRpcClient({ command: process.execPath, args: [fixture, scenario], startupTimeoutMs: 2_000, requestTimeoutMs: 1_000, ...options });
}

describe('OmpRpcClient', () => {
  it('handles fragmented CRLF ready frames and negotiates protocol v2', async () => {
    const rpc = client();
    try {
      const ready = await rpc.start();
      assert.deepEqual(ready.supportedProtocolVersions, [1, 2]);
      assert.equal(rpc.protocolVersion, 2);
    } finally { await rpc.close(); }
  });

  it('correlates out-of-order responses and emits ordinary events', async () => {
    const rpc = client();
    try {
      await rpc.start();
      const notice = once(rpc, 'notice');
      const [slow, fast] = await Promise.all([
        rpc.request('echo', { value: 'slow', delay: 25 }),
        rpc.request('echo', { value: 'fast', delay: 1 }),
      ]);
      assert.equal(slow.value, 'slow'); assert.equal(fast.value, 'fast');
      assert.equal((await notice)[0].message, 'fixture-ready');
    } finally { await rpc.close(); }
  });

  it('reassembles protocol-v2 chunk frames into one logical event', async () => {
    const rpc = client('chunk');
    try {
      await rpc.start(); const notice = once(rpc, 'notice');
      await rpc.request('emit_chunk');
      assert.equal((await notice)[0].message, 'chunked-event');
    } finally { await rpc.close(); }
  });

  it('rejects timed-out requests without poisoning later correlation', async () => {
    const rpc = client('normal', { requestTimeoutMs: 40 });
    try {
      await rpc.start();
      await assert.rejects(rpc.request('timeout'), /timeout/);
      assert.equal((await rpc.request('echo', { value: 'alive' }, { timeoutMs: 500 })).value, 'alive');
    } finally { await rpc.close(); }
  });

  it('rejects interleaved chunks as a protocol error', async () => {
    const rpc = client('bad');
    try {
      await rpc.start(); const error = once(rpc, 'protocol_error');
      rpc.send({ id: 'manual', type: 'bad_chunk' });
      assert.match((await error)[0].message, /interleaved|out-of-order/);
    } finally { await rpc.close(); }
  });

  it('rejects pending requests when the child exits', async () => {
    const rpc = client('exit');
    await rpc.start();
    await assert.rejects(rpc.request('exit_now'), /exited with code 7/);
  });
});
