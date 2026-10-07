import type { StreamRow } from '../../lib/stream/protocol'

/**
 * Sohbet satırlarını çizim gruplarına ayırır.
 *
 * Ardışık düşünce satırları TEK kutuda toplanır. Ölçüm (10.08.2026, canlı
 * oturum): 220 satırlık bir sohbette 191 düşünce satırı vardı ve her biri
 * ayrı katlanabilir kutu olarak çiziliyordu — iki ajan mesajının arasına
 * dört tane "DÜŞÜNCE göster" satırı giriyor, asıl konuşma kayboluyordu.
 */

export type RowGroup =
  | { kind: 'row'; row: StreamRow }
  | { kind: 'thoughts'; key: string; rows: StreamRow[] }

/** Düşünce satırı mı? Köprü iki biçimde işaretliyor: thoughtIndex ve rol. */
export function isThought(row: StreamRow): boolean {
  return row.thoughtIndex >= 0 || row.role === 'thought'
}

export function groupRows(rows: StreamRow[]): RowGroup[] {
  const groups: RowGroup[] = []
  for (const row of rows) {
    if (!isThought(row)) {
      groups.push({ kind: 'row', row })
      continue
    }
    const last = groups[groups.length - 1]
    if (last?.kind === 'thoughts') last.rows.push(row)
    else groups.push({ kind: 'thoughts', key: row.rowId, rows: [row] })
  }
  return groups
}
