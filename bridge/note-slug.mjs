// Not dosya adı slug'ı — TEK kaynak. Hem cowork.mjs (not oluştur/yeniden
// adlandır) hem reminders.mjs (hatırlatıcı notu) buradan alır; iki ayrı kopya
// tutulursa biri değişip diğeri kalıyor ve aynı başlık iki farklı dosya adı
// üretiyor.
//
// Başlık ayrıca frontmatter/zarf içinde HAM haliyle korunur; slug yalnız dosya
// adı içindir. Boş sonuç dönebilir — çağıran kendi yedeğini uygular
// (reminders `hatirlatici` kullanır).
const TR_MAP = {
  'ç': 'c', 'Ç': 'c', 'ğ': 'g', 'Ğ': 'g', 'ı': 'i', 'İ': 'i',
  'ö': 'o', 'Ö': 'o', 'ş': 's', 'Ş': 's', 'ü': 'u', 'Ü': 'u',
};

export function slugifyNoteName(raw, maxLen = 80) {
  return String(raw || '')
    .replace(/[çÇğĞıİöÖşŞüÜ]/g, (m) => TR_MAP[m] || m)
    .toLowerCase().trim()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, maxLen);
}
