// Slash komutları.
//
// Kaynak `GET /slash?backend=<id>` (general.mjs). Oturumun kendi `init`i de
// komut taşıyabiliyor (akışta `meta.commands`); doluysa O tercih edilir,
// çünkü kurulu skill'leri ve projeye özel komutları içerir.

import { apiGet } from '../../lib/api'

export interface SlashCommand {
  name: string
  desc?: string
  hint?: string
}

interface SlashResponse {
  commands?: SlashCommand[]
}

export async function fetchSlashCommands(
  backend: string,
  signal?: AbortSignal,
): Promise<SlashCommand[]> {
  try {
    const data = await apiGet<SlashResponse>(
      `/slash?backend=${encodeURIComponent(backend)}`,
      signal,
    )
    return data.commands ?? []
  } catch {
    // Komut listesi kolaylıktır; alınamaması yazmayı engellememeli.
    return []
  }
}

/**
 * Metinden komut önekini çıkarır.
 *
 * Menü YALNIZCA metnin tamamı tek bir `/komut` parçasıysa açılır: satır
 * ortasındaki eğik çizgi (dosya yolu, tarih, "and/or") menüyü açmamalı.
 * Boşluktan sonra da kapanır — komut seçilmiş, artık argüman yazılıyordur.
 */
export function slashPrefix(text: string): string | null {
  if (!text.startsWith('/')) return null
  const rest = text.slice(1)
  if (/[\s/\\]/.test(rest)) return null
  return rest
}

/** Öneke uyanları sıralar: baştan eşleşenler önce, sonra içinde geçenler. */
export function matchCommands(commands: SlashCommand[], prefix: string): SlashCommand[] {
  const needle = prefix.toLocaleLowerCase('tr')
  if (!needle) return commands
  const starts: SlashCommand[] = []
  const contains: SlashCommand[] = []
  for (const command of commands) {
    const name = command.name.toLocaleLowerCase('tr')
    if (name.startsWith(needle)) starts.push(command)
    else if (name.includes(needle)) contains.push(command)
  }
  return [...starts, ...contains]
}
