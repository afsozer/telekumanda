import type { Session } from './sessionsApi'

/** Oturum listesi araması. Eski/0 turlu kayıtlar artık sessizce elenmez. */

function haystack(session: Session): string {
  return [session.title, session.cwd, session.lastText, session.model, session.id]
    .filter(Boolean)
    .join(' ')
    .toLocaleLowerCase('tr')
}

export interface FilterOptions {
  query?: string
}

export interface FilterResult<T = Session> {
  visible: T[]
}

// Jenerik: birleşik listede satırlar TaggedSession (backend etiketli) ve o
// alanların elemeden geçerken düşmemesi gerekiyor.
export function filterSessions<T extends Session>(
  sessions: T[],
  { query = '' }: FilterOptions = {},
): FilterResult<T> {
  const needle = query.trim().toLocaleLowerCase('tr')
  const visible = sessions.filter((session) => !needle || haystack(session).includes(needle))

  return {
    visible: visible.sort((a, b) => Number(!!b.pinned) - Number(!!a.pinned) || b.lastUserAt - a.lastUserAt),
  }
}
