// Not dosyasının frontmatter'ı ile gövdesini ayırır.
//
// Neden: not `.md`si başında YAML bloğu taşıyor (`title`, hatırlatıcılı notta
// ayrıca `reminder_at`, `reminder_state` …). Kullanıcı bunu düzenlemek
// istemiyor ama KAYBETMEK hiç istemiyor — `reminder_*` silinirse hatırlatıcı
// sessizce düşer. Bu yüzden editör yalnız gövdeyi gösterir, kaydederken blok
// olduğu gibi geri yapıştırılır.
//
// Ayırıcı kuralı köprüyle aynı (cowork.mjs parseFrontmatter): dosya `---` ile
// BAŞLAMALI ve kapanış `---` kendi satırında olmalı. Aksi halde frontmatter
// yoktur, dosyanın tamamı gövdedir.

export interface SplitNote {
  /** Kapanış ayıracı dahil ham blok; frontmatter yoksa boş. */
  frontmatter: string
  body: string
}

const OPEN = /^---\r?\n/
const CLOSE = /\r?\n---[ \t]*(\r?\n|$)/

export function splitFrontmatter(text: string): SplitNote {
  if (!OPEN.test(text)) return { frontmatter: '', body: text }
  const rest = text.slice(text.indexOf('\n') + 1)
  const close = CLOSE.exec(rest)
  if (!close) return { frontmatter: '', body: text }
  const end = close.index + close[0].length
  return {
    frontmatter: text.slice(0, text.indexOf('\n') + 1 + end),
    body: rest.slice(end),
  }
}

/** Düzenlenmiş gövdeyi frontmatter'la birleştirir. */
export function joinFrontmatter(frontmatter: string, body: string): string {
  if (!frontmatter) return body
  // Blok kendi satır sonunu taşıyor; araya fazladan satır koymak her kayıtta
  // dosyanın başında bir boş satır biriktirirdi.
  return frontmatter + body
}

/** Frontmatter'daki `title:` değeri; yoksa boş. */
export function frontmatterTitle(frontmatter: string): string {
  const match = /^title:[ \t]*(.*)$/m.exec(frontmatter)
  return match ? match[1].trim().replace(/^["']|["']$/g, '') : ''
}
