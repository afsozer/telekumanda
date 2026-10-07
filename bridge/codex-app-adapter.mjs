export function dispatchCodexNotification(message, session, handlers) {
  const params = message.params || {};
  switch (message.method) {
    case 'error': if (session) handlers.onTurnError(session, params); break;
    case 'warning': break;
    case 'turn/started': if (session) handlers.onTurnStarted(session, params); break;
    case 'turn/completed': if (session) handlers.onTurnCompleted(session, params); break;
    case 'thread/started': if (session) handlers.onThreadStarted(session, params); break;
    case 'thread/status/changed': if (session) handlers.onThreadStatusChanged(session, params); break;
    case 'thread/tokenUsage/updated': if (session) handlers.onTokenUsage(session, params); break;
    case 'thread/name/updated': if (session) session._name = params.name || session._name; break;
    case 'item/started': if (session) handlers.onItemStarted(session, params); break;
    case 'item/completed': if (session) handlers.onItemCompleted(session, params); break;
    case 'item/agentMessage/delta': if (session) handlers.onAgentMessageDelta(session, params); break;
    case 'item/plan/delta': if (session) handlers.onPlanDelta(session, params); break;
    case 'item/reasoning/textDelta': if (session) handlers.onReasoningDelta(session, params, 'content'); break;
    case 'item/reasoning/summaryTextDelta': if (session) handlers.onReasoningDelta(session, params, 'summary'); break;
    case 'item/commandExecution/outputDelta': if (session) handlers.onStreamDelta(session, params, 'cmd'); break;
    case 'item/fileChange/outputDelta': if (session) handlers.onStreamDelta(session, params, 'file'); break;
    case 'item/fileChange/patchUpdated': break;
    case 'turn/plan/updated': if (session) handlers.onTurnPlanUpdated(session, params); break;
    case 'thread/compacted': if (session) handlers.onContextCompacted(session, params); break;
    case 'thread/goal/updated': if (session) handlers.onGoalUpdated(session, params); break;
    case 'thread/goal/cleared': if (session) handlers.onGoalCleared(session, params); break;
    case 'serverRequest/resolved': if (session) handlers.onServerRequestResolved(session, params); break;
    case 'thread/closed': if (session) handlers.onThreadClosed(session, params); break;
    case 'remoteControl/status/changed': break;
    case 'mcpServer/startupStatus/updated': break;
    default: break;
  }
}
