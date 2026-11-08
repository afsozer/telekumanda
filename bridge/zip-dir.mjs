// Bir klasörün İÇERİĞİNİ (klasörün kendisi değil) zip'e paketler.
// Windows'ta PowerShell Compress-Archive, diğer sistemlerde `zip` kullanılır;
// ikisi de ek npm bağımlılığı istemez. Yollar komut dizgisine gömülmez:
// PowerShell'e ortam değişkeniyle, zip'e argüman olarak geçer (PowerShell ‘ ’
// gibi Unicode tırnakları da tek tırnak sayar; "Ahmet’in dosyası" adlı klasör
// hem arşivlemeyi bozuyor hem -Command dizgisinden kaçmaya izin veriyordu).
import { spawn } from 'node:child_process';
import fs from 'node:fs';

const TIMEOUT_MS = 120_000;

export function zipDirectoryContents({ srcDir, zipPath, exclude = [], emptyError = 'klasor bos' }) {
  let names;
  try {
    names = fs.readdirSync(srcDir).filter((n) => !exclude.includes(n));
  } catch (e) {
    return Promise.resolve({ ok: false, error: String(e.message || e) });
  }
  if (names.length === 0) return Promise.resolve({ ok: false, error: emptyError });

  const [cmd, args, opts] = process.platform === 'win32'
    ? ['powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', [
      '$ErrorActionPreference = "Stop";',
      '$skip = $env:TK_ARCHIVE_EXCLUDE -split "\\|";',
      '$items = Get-ChildItem -LiteralPath $env:TK_ARCHIVE_SRC -Force | Where-Object { $skip -notcontains $_.Name };',
      'Compress-Archive -LiteralPath ($items | ForEach-Object { $_.FullName }) -DestinationPath $env:TK_ARCHIVE_DST -Force;',
    ].join(' ')], {
      env: { ...process.env, TK_ARCHIVE_SRC: srcDir, TK_ARCHIVE_DST: zipPath, TK_ARCHIVE_EXCLUDE: exclude.join('|') },
    }]
    // -y: sembolik bağı izlemeden bağ olarak sakla (klasör dışına taşmasın).
    : ['zip', ['-q', '-r', '-y', zipPath, '--', ...names], { cwd: srcDir }];

  return new Promise((resolve) => {
    let done = false;
    const finish = (r) => { if (!done) { done = true; resolve(r); } };
    const child = spawn(cmd, args, { windowsHide: true, ...opts });
    let err = '';
    child.stderr.on('data', (d) => { err += d; });
    const timer = setTimeout(() => { try { child.kill(); } catch {} finish({ ok: false, error: 'zip timeout' }); }, TIMEOUT_MS);
    child.on('close', (code) => {
      clearTimeout(timer);
      if (code === 0 && fs.existsSync(zipPath)) finish({ ok: true, size: fs.statSync(zipPath).size });
      else finish({ ok: false, error: (err || `${cmd} exit ${code}`).trim().slice(0, 300) });
    });
    child.on('error', (e) => { clearTimeout(timer); finish({ ok: false, error: e.message }); });
  });
}
