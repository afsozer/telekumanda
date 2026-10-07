import readline from 'node:readline';

const scenario = process.argv[2] || 'normal';
let writes = Promise.resolve();
function write(frame, fragmented = false) {
  const line = JSON.stringify(frame) + '\r\n';
  writes = writes.then(async () => {
    if (!fragmented) { process.stdout.write(line); return; }
    process.stdout.write(line.slice(0, 5));
    await new Promise(resolve => setTimeout(resolve, 2));
    process.stdout.write(line.slice(5, 13));
    await new Promise(resolve => setTimeout(resolve, 2));
    process.stdout.write(line.slice(13));
  });
  return writes;
}

write({ type: 'ready', protocolVersion: 1, supportedProtocolVersions: [1, 2], maxFrameBytes: 1048576, maxReassembledFrameBytes: 67108864 }, true);

const rl = readline.createInterface({ input: process.stdin });
rl.on('line', line => {
  if (!line.trim()) return;
  const command = JSON.parse(line);
  if (command.type === 'negotiate_protocol') {
    write({ id: command.id, type: 'response', command: command.type, success: true, data: { protocolVersion: 2 } }, true);
    if (scenario === 'normal') setTimeout(() => write({ type: 'notice', message: 'fixture-ready' }, true), 6);
    return;
  }
  if (command.type === 'echo') {
    setTimeout(() => write({ id: command.id, type: 'response', command: 'echo', success: true, data: { value: command.value } }, true), command.delay || 0);
    return;
  }
  if (command.type === 'timeout') return;
  if (command.type === 'exit_now') return process.exit(7);
  if (command.type === 'emit_chunk') {
    write({ id: command.id, type: 'response', command: command.type, success: true });
    const raw = Buffer.from(JSON.stringify({ type: 'notice', message: 'chunked-event' }), 'utf8');
    const split = Math.ceil(raw.length / 2);
    const parts = [raw.subarray(0, split), raw.subarray(split)];
    parts.forEach((part, index) => write({ type: 'rpc_chunk', chunkId: 'chunk-1', index, count: 2, byteLength: raw.length, data: part.toString('base64') }, true));
    return;
  }
  if (command.type === 'bad_chunk') {
    const raw = Buffer.from('{}');
    write({ type: 'rpc_chunk', chunkId: 'a', index: 0, count: 2, byteLength: raw.length, data: raw.subarray(0, 1).toString('base64') });
    write({ type: 'rpc_chunk', chunkId: 'b', index: 1, count: 2, byteLength: raw.length, data: raw.subarray(1).toString('base64') });
  }
});
