export function buildNotificationFeedState(approval = {}, operations = {}, delivery = {}) {
  const events = Array.isArray(operations.events) ? operations.events : [];
  return {
    pending: !!approval.pending,
    backend: String(approval.backend || ''),
    sessionId: String(approval.sessionId || ''),
    summary: String(approval.summary || ''),
    // Bekleyen onayın kimliği: bildirimdeki onay tuşu onu gönderir (bayat onay → 409).
    requestId: approval.requestId === null || approval.requestId === undefined ? '' : String(approval.requestId),
    eventId: String(events[0]?.id || ''),
    events,
    ...delivery,
  };
}

export function notificationFeedChanged(current, cursor = {}) {
  return (current.pushEvents?.length || 0) > 0 || current.pending !== !!cursor.pending ||
    current.sessionId !== String(cursor.sessionId || '') ||
    current.eventId !== String(cursor.eventId || '');
}
