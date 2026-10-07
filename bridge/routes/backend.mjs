import { json, body } from '../router.mjs';
import { createPromptRequests } from '../prompt-requests.mjs';
import { createPromptOrigin } from '../prompt-origin.mjs';

const promptRequests = createPromptRequests();
export function reconcilePromptRequests(modules) { return promptRequests.reconcileAll(modules); }

// Turu baslatan cihaz — bitis bildirimi yalniz oraya gitsin diye. Gerekce ve
// neden model adiyla eslestigi prompt-origin.mjs basinda.
const promptOrigin = createPromptOrigin();
export function promptOriginModel(backend, sessionId, diskId = '') {
  return promptOrigin.lookup(backend, sessionId, diskId);
}

export function registerBackend(router, name, mod, opts = {}) {
  const pfx = '/' + name;

  router.get(pfx + '/models', async (req, res) => {
    const models = opts.getModels ? await opts.getModels(req) : mod.MODELS || [];
    // defaultModel tek kaynaktan (madde 7): Android buradan okur, string gömmez.
    const defaultModel = opts.defaultModel ? opts.defaultModel() : (mod.defaultModel ? mod.defaultModel() : (models[0]?.id || ''));
    json(res, 200, { models, defaultModel });
  });

  router.get(pfx + '/sessions', (req, res) => json(res, 200, { sessions: mod.listSessions() }));

  router.post(pfx + '/new', async (req, res) => {
    const b = await body(req);
    // home: yalnız agy'de anlamlı (cli/ide paylaşımlı depo); diğer backend'ler yok sayar.
    const r = mod.newSession({ cwd: b.cwd, model: b.model, permissionMode: b.permissionMode, effort: b.effort, cowork: b.cowork, home: b.home });
    json(res, r.ok ? 200 : 400, r);
  });

  router.post(pfx + '/prompt', async (req, res) => {
    const b = await body(req);
    // Kaynak cihaz HEADER'DAN okunur: istemci onu `buildRequest` icinde her
    // istege koyuyor, yani alti ayri prompt cagri yerinin hicbirine dokunmak
    // gerekmedi. Basliksiz istek (web arayuzu, curl, eski surum) kayit
    // birakmaz ve bildirim eski davranisa duser.
    const liteNoPush = req.headers?.['x-agentbridge-edition'] === 'lite';
    promptOrigin.remember(name, b.sessionId, liteNoPush ? '__agentbridge_lite_no_push__' : req.headers?.['x-device-model']);
    const started = promptRequests.begin({ requestId: b.requestId, backend: name, sessionId: b.sessionId, text: String(b.text || '') }, mod.getConversation);
    if (!started.ok) return json(res, 400, started);
    if (started.duplicate && started.record.status === 'delivered') return json(res, 200, { ok: true, duplicate: true, requestId: b.requestId });
    if (started.duplicate) return json(res, 409, { ok: false, duplicate: true, status: started.record.status, error: 'prompt was not safely delivered; send as a new request' });
    if (opts.beforePrompt) {
      const guard = await opts.beforePrompt(b);
      if (!guard?.ok) {
        promptRequests.finish(b.requestId, guard || { ok: false, error: 'prompt blocked' });
        return json(res, guard?.status || 409, guard || { ok: false, error: 'prompt blocked' });
      }
    }
    // agent: yalnız OpenCode telefonu gövdede gönderir; diğer backend'lerde
    // undefined kalır ve prompt() destructure'ında yok sayılır. OpenCode
    // prompt()'u `agent !== undefined` ise oturum ajanını buna çeker → seçici
    // her turda otoriter olur (setAgent desync'i biter).
    const r = await mod.prompt({ sessionId: b.sessionId, text: b.text, model: b.model, permissionMode: b.permissionMode, images: b.images, variant: b.variant, agent: b.agent });
    promptRequests.finish(b.requestId, r);
    // v1 söküldü (30.09.2026): bu "gövdede görünüyorsa aslında teslim edilmiştir"
    // kurtarması ÖLÇÜLDÜĞÜ backend'lerde kalıyor. opencode-app düştü; v2 için
    // aynı davranış ölçülmedi, ölçülmeden eklenmiyor.
    if (name === 'codex-app' && (!r || r.ok !== true)) {
      const c = mod.getConversation?.(b.sessionId) || {};
      const text = String(b.text || '');
      const delivered = Array.isArray(c.messages) && c.messages.some(m => m && m.role === 'user' && m.text === text);
      if (delivered && (c.running || c.awaitingFirstOutput)) {
        promptRequests.finish(b.requestId, { ok: true, sessionId: b.sessionId, warning: r?.error || 'prompt accepted by app-server' });
        return json(res, 200, { ok: true, sessionId: b.sessionId, warning: r?.error || 'prompt accepted by app-server' });
      }
    }
    json(res, r.ok ? 200 : 400, r);
  });

  router.get(pfx + '/conversation', (req, res) => {
    const params = new URL(req.url, 'http://x').searchParams;
    const sid = params.get('sessionId') || '';
    json(res, 200, mod.getConversation(sid, { before: params.get('before') || '', limit: parseInt(params.get('limit') || '0', 10) || 0 }));
  });

  router.get(pfx + '/disk-sessions', async (req, res) => {
    json(res, 200, await mod.listDiskSessions({}));
  });

  router.post(pfx + '/adopt', async (req, res) => {
    const b = await body(req);
    // cowork bayrağı yalnız claude-app'te anlamlı; home yalnız agy'de (cli/ide);
    // diğer backend'ler destructure'da yok sayar.
    const r = await mod.adoptSession({ id: b.id, cwd: b.cwd, cowork: !!b.cowork, home: b.home });
    json(res, r.ok ? 200 : 400, r);
  });

  router.post(pfx + '/stop', async (req, res) => {
    const b = await body(req);
    const r = await mod.stop(b.sessionId || '');
    json(res, r.ok ? 200 : 404, r);
  });

  // Oturumu app listesinden ve DİSKTEN tamamen sil. Yalnızca yerel dosya/db
  // tabanlı backend'ler deleteDiskSession'i uygular; app-server/uzak-web tabanlı
  // olanlar desteklemez → 400 'not supported'.
  router.post(pfx + '/delete-session', async (req, res) => {
    if (typeof mod.deleteDiskSession !== 'function') {
      return json(res, 400, { ok: false, error: 'delete not supported for ' + name });
    }
    const b = await body(req);
    // Yaygın çağrı hatası: gövdede `sessionId` gönderilir, uç `id` bekler ve
    // sebebi söylemeden {ok:false,'id required'} döner. Neyin yanlış olduğunu söyle.
    if (!b.id && b.sessionId) {
      return json(res, 400, { ok: false, error: "bu uç 'id' bekler, 'sessionId' değil" });
    }
    const r = await mod.deleteDiskSession({ id: b.id });
    json(res, r && r.ok ? 200 : 400, r || { ok: false, error: 'delete failed' });
  });

  // Extra per-backend routes (thought, open, usage, etc.)
  if (opts.extras) {
    for (const { method, pattern, handler } of opts.extras) {
      router[method.toLowerCase()]?.(pfx + pattern, handler);
    }
  }
}
