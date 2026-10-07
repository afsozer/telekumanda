import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

/** Winget Poppler paketindeki gerçek bin dizinini sürümden bağımsız bulur. */
export function findWingetPopplerBin({
  localAppData = process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local'),
} = {}) {
  const packages = path.join(localAppData, 'Microsoft', 'WinGet', 'Packages');
  let packageDirs = [];
  try {
    packageDirs = fs.readdirSync(packages, { withFileTypes: true })
      .filter(entry => entry.isDirectory() && entry.name.startsWith('oschwartz10612.Poppler_'))
      .map(entry => path.join(packages, entry.name));
  } catch {
    return '';
  }
  for (const packageDir of packageDirs) {
    let versions = [];
    try {
      versions = fs.readdirSync(packageDir, { withFileTypes: true })
        .filter(entry => entry.isDirectory())
        .map(entry => path.join(packageDir, entry.name));
    } catch {}
    for (const versionDir of versions) {
      const bin = path.join(versionDir, 'Library', 'bin');
      if (fs.existsSync(path.join(bin, 'pdftoppm.exe'))) return bin;
    }
  }
  return '';
}

/**
 * Bridge eski bir terminalden yeniden başlatılsa bile yeni Winget PATH yayılımını
 * beklemeden Claude/Codex/OpenCode child süreçlerine Poppler'i taşır.
 */
export function ensureDocumentToolPath(env = process.env) {
  if (process.platform !== 'win32') return { changed: false, bin: '' };
  const bin = findWingetPopplerBin({ localAppData: env.LOCALAPPDATA });
  if (!bin) return { changed: false, bin: '' };
  const key = Object.keys(env).find(name => name.toLowerCase() === 'path') || 'PATH';
  const entries = String(env[key] || '').split(path.delimiter).filter(Boolean);
  const normalized = bin.toLowerCase();
  if (entries.some(entry => path.resolve(entry).toLowerCase() === normalized)) {
    return { changed: false, bin };
  }
  env[key] = [bin, ...entries].join(path.delimiter);
  return { changed: true, bin };
}
