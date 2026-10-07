import { EventEmitter } from 'node:events';
import { spawn as nodeSpawn } from 'node:child_process';
import { TextDecoder } from 'node:util';

const DEFAULT_MAX_FRAME = 64 * 1024 * 1024;

/** Small process-backed client for OMP's newline-delimited RPC protocol. */
export class OmpRpcClient extends EventEmitter {
  constructor({
    command = 'omp', args = ['--mode', 'rpc'], cwd, env = process.env,
    startupTimeoutMs = 15_000, requestTimeoutMs = 20_000, closeTimeoutMs = 2_000,
    maxReassembledFrameBytes = DEFAULT_MAX_FRAME, spawnFn = nodeSpawn,
  } = {}) {
    super();
    this.command = command;
    this.args = [...args];
    this.cwd = cwd;
    this.env = env;
    this.startupTimeoutMs = startupTimeoutMs;
    this.requestTimeoutMs = requestTimeoutMs;
    this.closeTimeoutMs = closeTimeoutMs;
    this.maxReassembledFrameBytes = Math.min(DEFAULT_MAX_FRAME, Math.max(1, maxReassembledFrameBytes));
    this.spawnFn = spawnFn;
    this.child = null;
    this.protocolVersion = 1;
    this.ready = null;
    this.stderrTail = '';
    this._stdout = Buffer.alloc(0);
    this._pending = new Map();
    this._seq = 0;
    this._starting = null;
    this._closing = false;
    this._chunk = null;
  }

  async start() {
    if (this.ready && this.child && this.child.exitCode == null) return this.ready;
    if (this._starting) return this._starting;
    this._starting = new Promise((resolve, reject) => {
      let settled = false;
      const done = (err, value) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        this.off('_startup-ready', onReady);
        this.off('_startup-error', onError);
        err ? reject(err) : resolve(value);
      };
      const onReady = value => done(null, value);
      const onError = error => done(error);
      const timer = setTimeout(() => done(new Error(`OMP RPC ready timeout after ${this.startupTimeoutMs}ms${this._stderrSuffix()}`)), this.startupTimeoutMs);
      timer.unref?.();
      this.once('_startup-ready', onReady);
      this.once('_startup-error', onError);
      try {
        this.child = this.spawnFn(this.command, this.args, {
          cwd: this.cwd,
          env: this.env,
          windowsHide: true,
          stdio: ['pipe', 'pipe', 'pipe'],
        });
        this.child.stdout.on('data', chunk => this._onStdout(chunk));
        this.child.stderr.on('data', chunk => this._onStderr(chunk));
        this.child.once('error', error => this._onExit(null, null, error));
        this.child.once('exit', (code, signal) => this._onExit(code, signal));
      } catch (error) {
        done(error);
      }
    }).finally(() => { this._starting = null; });
    return this._starting;
  }

  async request(type, payload = {}, { timeoutMs = this.requestTimeoutMs } = {}) {
    await this.start();
    return this._requestNow(type, payload, timeoutMs);
  }

  send(frame) {
    if (!this.child?.stdin?.writable || this.child.exitCode != null) throw new Error(`OMP RPC process is not writable${this._stderrSuffix()}`);
    this.child.stdin.write(JSON.stringify(frame) + '\n');
  }

  abort() { return this.request('abort'); }

  async close() {
    if (!this.child || this.child.exitCode != null) return;
    this._closing = true;
    const child = this.child;
    const exited = new Promise(resolve => child.once('exit', resolve));
    try { child.stdin.end(); } catch {}
    await Promise.race([
      exited,
      new Promise(resolve => {
        const timer = setTimeout(resolve, this.closeTimeoutMs);
        timer.unref?.();
      }),
    ]);
    if (child.exitCode == null) {
      try { child.kill(); } catch {}
      await Promise.race([exited, new Promise(resolve => setTimeout(resolve, 250))]);
    }
  }

  _requestNow(type, payload, timeoutMs) {
    const id = `omp_${++this._seq}`;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this._pending.delete(id);
        reject(new Error(`OMP RPC ${type} timeout after ${timeoutMs}ms`));
      }, timeoutMs);
      timer.unref?.();
      this._pending.set(id, { type, resolve, reject, timer });
      try { this.send({ id, type, ...payload }); }
      catch (error) { clearTimeout(timer); this._pending.delete(id); reject(error); }
    });
  }

  _onStderr(chunk) {
    this.stderrTail = (this.stderrTail + Buffer.from(chunk).toString('utf8')).slice(-8_192);
  }

  _onStdout(chunk) {
    this._stdout = Buffer.concat([this._stdout, Buffer.from(chunk)]);
    for (;;) {
      const nl = this._stdout.indexOf(0x0a);
      if (nl < 0) break;
      let line = this._stdout.subarray(0, nl);
      this._stdout = this._stdout.subarray(nl + 1);
      if (line.at(-1) === 0x0d) line = line.subarray(0, -1);
      if (!line.length) continue;
      try {
        const text = new TextDecoder('utf-8', { fatal: true }).decode(line);
        this._acceptFrame(JSON.parse(text));
      } catch (error) {
        this._protocolError(new Error(`invalid OMP RPC JSON frame: ${error.message}`));
        return;
      }
    }
  }

  _acceptFrame(frame) {
    if (!frame || typeof frame !== 'object' || Array.isArray(frame)) return this._protocolError(new Error('invalid OMP RPC frame'));
    if (frame.type === 'rpc_chunk') return this._acceptChunk(frame);
    if (this._chunk) return this._protocolError(new Error('OMP RPC chunk sequence was interrupted'));
    this._dispatch(frame);
  }

  _acceptChunk(frame) {
    const { chunkId, index, count, byteLength, data } = frame;
    if (typeof chunkId !== 'string' || !chunkId || !Number.isInteger(index) || !Number.isInteger(count) || count < 1 || index < 0 || index >= count ||
        !Number.isInteger(byteLength) || byteLength < 0 || byteLength > this.maxReassembledFrameBytes || typeof data !== 'string') {
      return this._protocolError(new Error('invalid OMP RPC chunk metadata'));
    }
    if (!this._chunk) {
      if (index !== 0) return this._protocolError(new Error('OMP RPC chunk sequence must start at index 0'));
      this._chunk = { chunkId, count, byteLength, next: 0, parts: [], size: 0 };
    }
    const active = this._chunk;
    if (active.chunkId !== chunkId || active.count !== count || active.byteLength !== byteLength || index !== active.next) {
      return this._protocolError(new Error('interleaved or out-of-order OMP RPC chunks'));
    }
    let decoded;
    try {
      if (!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(data)) throw new Error('bad base64');
      decoded = Buffer.from(data, 'base64');
    } catch { return this._protocolError(new Error('invalid OMP RPC chunk base64')); }
    active.parts.push(decoded); active.size += decoded.length; active.next++;
    if (active.size > active.byteLength || active.size > this.maxReassembledFrameBytes) return this._protocolError(new Error('OMP RPC chunk payload exceeds declared limit'));
    if (active.next !== active.count) return;
    this._chunk = null;
    if (active.size !== active.byteLength) return this._protocolError(new Error('OMP RPC chunk byteLength mismatch'));
    try {
      const logical = new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(active.parts));
      this._dispatch(JSON.parse(logical));
    } catch (error) { this._protocolError(new Error(`invalid reassembled OMP RPC frame: ${error.message}`)); }
  }

  _dispatch(frame) {
    if (frame.type === 'ready') {
      this.ready = frame;
      this.emit('frame', frame); this.emit('ready', frame);
      const versions = Array.isArray(frame.supportedProtocolVersions) ? frame.supportedProtocolVersions : [frame.protocolVersion || 1];
      if (versions.includes(2)) {
        this._requestNow('negotiate_protocol', { protocolVersion: 2 }, this.requestTimeoutMs)
          .then(() => { this.protocolVersion = 2; this.emit('_startup-ready', frame); })
          .catch(error => this.emit('_startup-error', error));
      } else {
        this.protocolVersion = 1;
        this.emit('_startup-ready', frame);
      }
      return;
    }
    if (frame.type === 'response' && typeof frame.id === 'string' && this._pending.has(frame.id)) {
      const pending = this._pending.get(frame.id);
      this._pending.delete(frame.id); clearTimeout(pending.timer);
      if (frame.success === false) pending.reject(Object.assign(new Error(frame.error || `OMP RPC ${pending.type} failed`), { code: frame.code, response: frame }));
      else pending.resolve(frame.data ?? frame);
      return;
    }
    this.emit('frame', frame);
    if (typeof frame.type === 'string') this.emit(frame.type, frame);
  }

  _protocolError(error) {
    this.emit('protocol_error', error);
    this._rejectAll(error);
    if (!this.ready) this.emit('_startup-error', error);
    try { this.child?.kill(); } catch {}
  }

  _onExit(code, signal, cause = null) {
    const error = cause || new Error(`OMP RPC exited${code == null ? '' : ` with code ${code}`}${signal ? ` (${signal})` : ''}${this._stderrSuffix()}`);
    if (!this._closing) this.emit('exit', { code, signal, error });
    this._rejectAll(error);
    if (!this.ready) this.emit('_startup-error', error);
    this.child = null;
    this.ready = null;
  }

  _rejectAll(error) {
    for (const pending of this._pending.values()) { clearTimeout(pending.timer); pending.reject(error); }
    this._pending.clear();
  }

  _stderrSuffix() {
    const tail = this.stderrTail.trim().slice(-500);
    return tail ? ` (stderr: ${tail})` : '';
  }
}

export default OmpRpcClient;
