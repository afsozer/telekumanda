// server.mjs açılışta ayar dosyasını okur. Testler canlı bridge/config.json'a
// (gerçek token, git dışı, Mac'te hiç yok) bağlı kalmasın diye örnek ayardan
// geçici bir kopya üretilir ve TELEKUMANDA_CONFIG ile gösterilir. server.mjs'yi
// import eden her testte bu modül ondan ÖNCE import edilmeli (ESM sırası).
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

if (!process.env.TELEKUMANDA_CONFIG) {
  const example = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'config.example.json');
  const cfg = JSON.parse(fs.readFileSync(example, 'utf8'));
  cfg.authToken = 'test-token-0123456789abcdef0123456789';
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tk-config-'));
  const file = path.join(dir, 'config.json');
  fs.writeFileSync(file, JSON.stringify(cfg, null, 2));
  process.env.TELEKUMANDA_CONFIG = file;
}

export const TEST_CONFIG_PATH = process.env.TELEKUMANDA_CONFIG;
