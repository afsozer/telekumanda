// İstemciden gelen yollar için ortak koruyucu.
//
// Dosya API'si bilerek köprü kullanıcısının bütün hesabını kapsar (bkz. SECURITY.md);
// burada kök sınırı uygulanmaz. Reddedilen, yerel dosya olmayan Windows yollarıdır:
// - UNC ve aygıt yolları (`\\sunucu\pay`, `//sunucu`, `\\?\`, `\\.\`): yalnız
//   `existsSync` bile SMB bağlantısı açar ve köprüyü çalıştıran hesabın NTLM
//   özetini o sunucuya gönderir.
// - NTFS alternatif veri akışları (`dosya.txt:gizli`): görünmeyen içerik yazar.

export const PATH_KEYS = new Set(['path', 'paths', 'root', 'dir', 'destDir', 'target', 'filePath', 'projectPath', 'cwd', 'source', 'sources', 'src', 'dest', 'from', 'to']);

export function unsafePathReason(raw) {
  if (typeof raw !== 'string' || !raw) return null;
  let p = raw.trim();
  try { p = decodeURIComponent(p); } catch { /* ham hâliyle denetle */ }
  p = p.replace(/^agfile:\/*/i, '');
  const withoutSlashes = p.replace(/^\/+/, '');
  if (/^[\\/]{2}/.test(p) || withoutSlashes.startsWith('\\')) return 'UNC ve aygıt yolları kabul edilmiyor';
  const afterDrive = /^[a-zA-Z]:/.test(withoutSlashes) ? withoutSlashes.slice(2) : withoutSlashes;
  if (afterDrive.includes(':')) return 'NTFS veri akışı (ADS) içeren yol kabul edilmiyor';
  return null;
}

export class UnsafePathError extends Error {
  constructor(reason) { super(reason); this.status = 400; }
}

/** Nesnenin üst düzey yol alanlarını (dizi değerleri dahil) denetler; ilk sorunda fırlatır. */
export function assertSafePathFields(obj) {
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return;
  for (const [key, value] of Object.entries(obj)) {
    if (!PATH_KEYS.has(key)) continue;
    for (const v of Array.isArray(value) ? value : [value]) {
      const reason = unsafePathReason(v);
      if (reason) throw new UnsafePathError(reason);
    }
  }
}
