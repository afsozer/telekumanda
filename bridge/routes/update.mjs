import { json } from '../router.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Android APK güncelleme kanalı. (Masaüstü MSI kanalı 10.08.2026'da kaldırıldı:
// Compose Desktop istemcisinin yerini köprünün servis ettiği /ui arayüzü aldı.)
// latest.json: { versionCode, versionName, notes, apkPath }
export function register(router, { updateDir = path.join(__dirname, '..', 'update') } = {}) {
  router.get('/update/latest.json', (req, res) => {
    const fp = path.join(updateDir, 'latest.json');
    if (!fs.existsSync(fp)) return json(res, 404, { error: 'no update published' });
    json(res, 200, JSON.parse(fs.readFileSync(fp, 'utf-8')));
  });
  router.get('/update/app-latest.apk', (req, res) => {
    const fp = path.join(updateDir, 'app-latest.apk');
    if (!fs.existsSync(fp)) return json(res, 404, { error: 'no update published' });
    const stat = fs.statSync(fp);
    res.writeHead(200, { 'content-type': 'application/vnd.android.package-archive', 'content-length': stat.size });
    fs.createReadStream(fp).pipe(res);
  });
  router.get('/update/lite/latest.json', (req, res) => {
    const fp = path.join(updateDir, 'lite', 'latest.json');
    if (!fs.existsSync(fp)) return json(res, 404, { error: 'no Lite update published' });
    json(res, 200, JSON.parse(fs.readFileSync(fp, 'utf-8')));
  });
  router.get('/update/lite/app-latest.apk', (req, res) => {
    const fp = path.join(updateDir, 'lite', 'app-latest.apk');
    if (!fs.existsSync(fp)) return json(res, 404, { error: 'no Lite update published' });
    const stat = fs.statSync(fp);
    res.writeHead(200, { 'content-type': 'application/vnd.android.package-archive', 'content-length': stat.size });
    fs.createReadStream(fp).pipe(res);
  });
}
