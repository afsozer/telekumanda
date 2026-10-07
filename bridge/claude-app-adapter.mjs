// Hatali bir `result` olayindaki insan okunur mesaji cikar.
//
// CLI hatayi HER ZAMAN `result` alaninda vermiyor: --resume basarisiz olunca
// {type:'result', subtype:'error_during_execution', is_error:true, errors:[...]}
// geliyor ve `result` alani HIC YOK. Eskiden yalniz `ev.result` okundugu icin
// boyle turlar sohbete tek satir bile dusurmeden "basariyla" bitiyordu —
// kullanici bos ekrana bakip "neden cevap vermedi" diyordu (06.08.2026 canli vaka).
export function errorTextFromResult(ev) {
  if (!ev) return '';
  const parts = [];
  const push = v => {
    if (!v) return;
    const t = String(v).trim();
    if (t && !parts.includes(t)) parts.push(t);
  };
  if (Array.isArray(ev.errors)) ev.errors.forEach(e => push(typeof e === 'string' ? e : (e && (e.message || e.error))));
  else push(ev.errors);
  push(ev.error);
  push(ev.result);
  push(typeof ev.message === 'string' ? ev.message : null);
  return parts.join(' · ');
}

export function createClaudeEventAdapter(deps) {
  const {
    capMessages,
    clearInterruptTimer,
    extractChoices,
    finalizeTurnIdle,
    hhmm,
    initFromEvent,
    logWarn,
    maxToolResultChars,
    normalizeApprovalQuestions,
    normalizePermissionMode,
    onAutonomousActivity = () => {},
    pushSnapshot,
    summarizeTool,
    textFromContent,
  } = deps;

  return function handleClaudeEvent(s, ev) {
    if ((ev.type === 'system' && ev.subtype === 'init') || ev.type === 'system/init') {
      s.init = initFromEvent(ev);
      if (s.init.permissionMode) s.permissionMode = normalizePermissionMode(s.init.permissionMode);
      s.resumeFromDisk = true;
      return;
    }

    // Claude'un kalici sureci, ana tur `result` ile kapandiktan sonra bir arka
    // plan gorevi tamamlaninca ayni stdin/stdout akisi uzerinden kendiliginden
    // yeni bir tur baslatabilir. Bu olaylar prompt() yolundan gecmedigi icin
    // oturum `idle` kalmasin; yasam dongusunu yeniden etkinlestirme kararini
    // uygulamaya birak.
    const userContent = ev.type === 'user' ? ev.message?.content : null;
    const isTaskNotification = typeof userContent === 'string'
      ? userContent.includes('<task-notification>')
      : Array.isArray(userContent) && userContent.some(c =>
          c?.type === 'text' && String(c.text || '').includes('<task-notification>'));
    if (
      (ev.type === 'assistant' && ev.message) ||
      isTaskNotification ||
      (ev.type === 'control_request' && ev.request?.subtype === 'can_use_tool') ||
      (ev.type === 'system' && ev.subtype === 'compact_boundary')
    ) {
      onAutonomousActivity(s, ev);
    }

    if (ev.type === 'control_request' && ev.request && ev.request.subtype === 'can_use_tool') {
      const req = ev.request;
      const tool = req.tool_name || req.display_name || 'tool';
      s.pendingApproval = {
        requestId: ev.request_id,
        kind: tool === 'AskUserQuestion' ? 'question' : 'tool',
        tool,
        input: req.input || {},
        summary: summarizeTool({ name: tool, input: req.input || {} }),
        description: req.description || '',
        decisionReason: req.decision_reason || '',
        permissionSuggestions: Array.isArray(req.permission_suggestions) ? req.permission_suggestions : [],
        options: [
          { id: 'allow', label: tool === 'AskUserQuestion' ? 'Answer' : 'Allow', consequence: 'once' },
          { id: 'deny', label: 'Deny', consequence: 'deny' },
        ],
        questions: tool === 'AskUserQuestion' ? normalizeApprovalQuestions(req.input || {}) : [],
      };
      pushSnapshot(s);
      return;
    }

    if (ev.type === 'assistant' && ev.message) {
      const u = ev.message.usage;
      if (u && (typeof u.input_tokens === 'number' || typeof u.cache_read_input_tokens === 'number')) {
        s.contextTokens = (u.input_tokens || 0) + (u.cache_creation_input_tokens || 0) + (u.cache_read_input_tokens || 0);
      }
    }
    if (ev.type === 'assistant' && ev.message && Array.isArray(ev.message.content)) {
      for (const c of ev.message.content) {
        if (c.type === 'text') {
          const last = s.messages[s.messages.length - 1];
          if (last && last.role === 'agent' && last._open) last.text += c.text;
          else s.messages.push({ role: 'agent', text: c.text, time: hhmm(), _open: true });
          s._turnHadAgent = true;
        } else if (c.type === 'thinking') {
          const idx = s.toolDetails.length;
          s.toolDetails.push(String(c.thinking || ''));
          s.messages.push({ role: 'thought', text: '', thoughtIndex: idx });
        } else if (c.type === 'tool_use') {
          const idx = s.toolDetails.length;
          s.toolDetails.push(JSON.stringify(c.input || {}, null, 2));
          if (c.id && s._toolUseIndex) s._toolUseIndex.set(c.id, idx);
          s.messages.push({ role: 'thought', text: summarizeTool(c), thoughtIndex: idx });
        }
      }
    } else if (ev.type === 'system' && ev.subtype === 'compact_boundary') {
      s.messages.push({ role: 'thought', text: 'Conversation compacted', thoughtIndex: -1 });
      s._turnHadAgent = true;
    } else if (ev.type === 'user' && ev.message && Array.isArray(ev.message.content)) {
      for (const c of ev.message.content) {
        if (c.type !== 'tool_result' || !c.tool_use_id) continue;
        const idx = s._toolUseIndex ? s._toolUseIndex.get(c.tool_use_id) : undefined;
        if (idx == null || s.toolDetails[idx] == null) continue;
        const raw = textFromContent(c.content);
        const txt = raw.length > maxToolResultChars
          ? raw.slice(0, maxToolResultChars) + '\n...[trimmed]'
          : raw;
        s.toolDetails[idx] += '\n\n' + (c.is_error ? '-- result (error) --\n' : '-- result --\n') + txt;
        const msg = s.messages.find(m => m && m.thoughtIndex === idx);
        if (msg) msg.hasResult = true;
      }
    } else if (ev.type === 'result') {
      s.lastCost = ev.total_cost_usd || s.lastCost;
      if (ev.usage) s.lastUsage = ev.usage;
      if (!s._turnHadAgent && ev.result && String(ev.result).trim()) {
        s.messages.push({ role: 'agent', text: String(ev.result).trim(), time: hhmm() });
      }

      const failed = ev.is_error === true || (ev.subtype && ev.subtype !== 'success');

      // Hatali tur SESSIZ bitmesin: ajan bu turda hic konusmadiysa ve yukarida
      // `result` metni de basilmadiysa hatayi sohbete dusur.
      if (failed && !s._turnHadAgent) {
        const errText = errorTextFromResult(ev);
        if (errText && !(ev.result && String(ev.result).trim())) {
          finalizeTurnIdle(s, { error: errText });
          capMessages(s);
          return;
        }
      }

      finalizeTurnIdle(s);
    }
    capMessages(s);
  };
}
