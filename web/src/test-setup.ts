// jest-dom eşleştiricileri yalnızca DOM ortamında anlamlı. Saf mantık testleri
// `environment: 'node'` altında koşuyor (bkz. vite.config.ts) — orada import
// etmek çöker, bu yüzden koşullu.
if (typeof document !== 'undefined') {
  await import('@testing-library/jest-dom/vitest')
}
