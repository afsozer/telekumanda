import { json, body } from '../router.mjs';

export function register(router, { mcp }) {
  router.get('/mcp/servers', (req, res) => json(res, 200, mcp.listServers()));
  router.post('/mcp/server', async (req, res) => {
    const b = await body(req);
    const r = mcp.saveServer(b);
    json(res, r.ok ? 200 : 400, r);
  });
  router.post('/mcp/remove', async (req, res) => {
    const b = await body(req);
    const r = mcp.removeServer(b.name);
    json(res, r.ok ? 200 : 404, r);
  });
  router.post('/mcp/toggle', async (req, res) => {
    const b = await body(req);
    const r = mcp.toggleServer(b.name, !!b.enabled);
    json(res, r.ok ? 200 : 404, r);
  });
  router.post('/mcp/refresh', (req, res) => json(res, 200, { ok: true, note: 'MCP config will be re-read on next Antigravity launch.' }));
}
