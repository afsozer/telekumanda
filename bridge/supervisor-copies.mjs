// run-bridge.cmd her açılışta kendini %TEMP%\telekumanda-supervisor-<rastgele>.cmd
// olarak kopyalayıp oradan çalışır; taskkill ile öldürülen süpervizörün kopyası
// geride kalır. Köprü portu dinlemeye başladıktan sonra çağrılır: o an yaşayan
// tek süpervizör bu köprünün ebeveynidir (adını TELEKUMANDA_SUPERVISOR_NAME ile
// miras verir) ve onun kopyası korunur. Port bizdeyken başka bir süpervizör
// yaşayamaz (bir sonraki kontrolünde çıkar), kalan kopyaların hepsi ölüdür.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const COPY_RE = /^telekumanda-supervisor-\d+\.cmd$/i;

export function cleanupSupervisorCopies({
  dir = os.tmpdir(),
  ownName = process.env.TELEKUMANDA_SUPERVISOR_NAME || '',
} = {}) {
  const removed = [];
  let names;
  try { names = fs.readdirSync(dir); } catch { return { removed }; }
  for (const name of names) {
    if (!COPY_RE.test(name) || name.toLowerCase() === ownName.toLowerCase()) continue;
    try { fs.unlinkSync(path.join(dir, name)); removed.push(name); } catch {}
  }
  return { removed };
}
