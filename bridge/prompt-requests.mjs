import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

function read(filePath) { try { const value = JSON.parse(fs.readFileSync(filePath, 'utf8')); return Array.isArray(value) ? value : []; } catch { return []; } }
function write(filePath, records) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const tmp = filePath + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(records.slice(-1000), null, 2) + '\n');
  fs.renameSync(tmp, filePath);
}

export function createPromptRequests({ filePath = path.join(os.homedir(), '.agentbridge', 'prompt-requests.json'), now = () => new Date() } = {}) {
  let records = read(filePath);
  const persist = () => write(filePath, records);
  const find = requestId => records.find(item => item.requestId === requestId);

  function begin({ requestId, backend, sessionId, text }, getConversation) {
    if (!requestId) return { ok: false, error: 'requestId required' };
    const existing = find(requestId);
    if (existing) {
      if (existing.backend !== backend || existing.sessionId !== sessionId || existing.text !== text) return { ok: false, error: 'requestId payload mismatch' };
      if (existing.status === 'pending') {
        let messages = [];
        try { messages = getConversation?.(sessionId)?.messages || []; } catch {}
        const delivered = messages.some(message => message?.role === 'user' && String(message.text || '') === text);
        existing.status = delivered ? 'delivered' : 'interrupted';
        existing.reconciledAt = now().toISOString();
        persist();
      }
      return { ok: true, duplicate: true, record: existing };
    }
    const record = { requestId, backend, sessionId, text, status: 'pending', createdAt: now().toISOString() };
    records.push(record); persist();
    return { ok: true, duplicate: false, record };
  }

  function finish(requestId, response) {
    const record = find(requestId);
    if (!record) return;
    record.status = response?.ok === true ? 'delivered' : 'failed';
    record.response = response || null;
    record.finishedAt = now().toISOString();
    persist();
  }

  function list() { return records.map(item => ({ ...item, text: undefined })); }
  function reconcileAll(modules = {}) {
    let changed = false;
    for (const record of records.filter(item => item.status === 'pending')) {
      let messages = [];
      try { messages = modules[record.backend]?.getConversation?.(record.sessionId)?.messages || []; } catch {}
      record.status = messages.some(message => message?.role === 'user' && String(message.text || '') === record.text) ? 'delivered' : 'interrupted';
      record.reconciledAt = now().toISOString();
      changed = true;
    }
    if (changed) persist();
    return records.filter(item => item.reconciledAt).length;
  }
  return { begin, finish, list, reconcileAll };
}
