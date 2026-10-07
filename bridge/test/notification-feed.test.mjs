import test from 'node:test';
import assert from 'node:assert/strict';
import { buildNotificationFeedState, notificationFeedChanged } from '../notification-feed.mjs';

test('notification feed combines approval state with operation events', () => {
  const state = buildNotificationFeedState(
    { pending: true, backend: 'codex-app', sessionId: 's1', summary: 'İzin gerekli' },
    { events: [{ id: 'e2', kind: 'completed' }, { id: 'e1', kind: 'started' }] },
  );

  assert.equal(state.pending, true);
  assert.equal(state.backend, 'codex-app');
  assert.equal(state.eventId, 'e2');
  assert.equal(state.events.length, 2);
});

test('notification feed wakes only when approval or operation cursor changes', () => {
  const current = buildNotificationFeedState(
    { pending: false, sessionId: '' },
    { events: [{ id: 'e2' }] },
  );

  assert.equal(notificationFeedChanged(current, { pending: false, sessionId: '', eventId: 'e2' }), false);
  assert.equal(notificationFeedChanged(current, { pending: false, sessionId: '', eventId: 'e1' }), true);
  assert.equal(notificationFeedChanged({ ...current, pending: true }, { pending: false, sessionId: '', eventId: 'e2' }), true);
});

test('a queued note wakes the poll even when operation and approval cursors are unchanged', () => {
  const current = buildNotificationFeedState({}, {}, { pushEvents: [{ deliveryId: 'note1', kind: 'note' }] });
  assert.equal(notificationFeedChanged(current, { pending: false, sessionId: '', eventId: '' }), true);
  assert.equal(notificationFeedChanged({ ...current, pushEvents: [] }, { pending: false, sessionId: '', eventId: '' }), false);
});
