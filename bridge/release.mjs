#!/usr/bin/env node
// release.mjs — Build APK, bump version, publish to update/.
// Usage: node release.mjs <versionName> "<notes>"
//   e.g. node release.mjs 1.1 "Yeni ozellikler"

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ── Args ────────────────────────────────────────────────────────────────────
const [versionName, notes] = process.argv.slice(2);
if (!versionName || !notes) {
  console.error('Usage: node release.mjs <versionName> "<notes>"');
  process.exit(1);
}

// ── 1. Read current versionCode ─────────────────────────────────────────────
const latestPath = path.join(__dirname, 'update', 'latest.json');
let currentCode = 1;
if (fs.existsSync(latestPath)) {
  try {
    currentCode = JSON.parse(fs.readFileSync(latestPath, 'utf-8')).versionCode || 1;
  } catch { /* keep default */ }
}
// 17.09.2026 dersi: yalniz latest.json'a bakmak yetmiyor. Baska bir oturum
// build.gradle.kts'yi 399'a yukseltip APK'yi YAYIMLAMADAN cihaza kurmustu;
// latest.json 397'de kaldigi icin sonraki yayin da 399 oldu ve telefon ayni
// versionCode'u gorup "guncelsiniz" dedi. Gradle'daki kod da tabana giriyor.
const gradlePeek = fs.existsSync(path.join(__dirname, '..', 'android', 'app', 'build.gradle.kts'))
  ? fs.readFileSync(path.join(__dirname, '..', 'android', 'app', 'build.gradle.kts'), 'utf-8').match(/versionCode\s*=\s*(\d+)/)
  : null;
const gradleCode = gradlePeek ? Number(gradlePeek[1]) : 0;
if (gradleCode > currentCode) console.log(`build.gradle.kts versionCode ${gradleCode} > latest.json ${currentCode}; taban gradle`);
const newCode = Math.max(currentCode, gradleCode) + 1;
console.log(`Bumping versionCode ${currentCode} → ${newCode}, versionName → ${versionName}`);

// ── 2. Edit build.gradle.kts ───────────────────────────────────────────────
const gradlePath = path.join(__dirname, '..', 'android', 'app', 'build.gradle.kts');
if (!fs.existsSync(gradlePath)) {
  console.error(`ERROR: build.gradle.kts not found at ${gradlePath}`);
  process.exit(1);
}
let gradle = fs.readFileSync(gradlePath, 'utf-8');
gradle = gradle.replace(/versionCode\s*=\s*\d+/, `versionCode = ${newCode}`);
gradle = gradle.replace(/versionName\s*=\s*"[^"]*"/, `versionName = "${versionName}"`);
fs.writeFileSync(gradlePath, gradle, 'utf-8');
console.log('Updated build.gradle.kts');

// ── 3. Build APK ───────────────────────────────────────────────────────────
const androidDir = path.join(__dirname, '..', 'android');
console.log('Building APK (gradlew assembleRelease)…');
const build = spawnSync(
  'cmd',
  ['/c', '.\\gradlew.bat assembleRelease'],
  { cwd: androidDir, stdio: 'inherit', shell: true, env: process.env }
);
if (build.status !== 0) {
  console.error(`ERROR: Gradle build failed with exit code ${build.status}`);
  process.exit(build.status ?? 1);
}
console.log('Build succeeded.');

// ── 4. Copy APK → update/app-latest.apk ────────────────────────────────────
// 14.09.2026: debug -> release. Imza DEGISMEDI: release build type debug
// anahtariyla imzalaniyor (android/app/build.gradle.kts), yoksa OTA
// guncellemesi cihazdaki kurulumun uzerine gelemezdi.
const srcApk = path.join(androidDir, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
const dstApk = path.join(__dirname, 'update', 'app-latest.apk');
if (!fs.existsSync(srcApk)) {
  console.error(`ERROR: Built APK not found at ${srcApk}`);
  process.exit(1);
}
fs.mkdirSync(path.dirname(dstApk), { recursive: true });
fs.copyFileSync(srcApk, dstApk);
const apkSize = fs.statSync(dstApk).size;
// Uygulama indirdigi paketin SHA-256'sini bununla karsilastirir; ozet yoksa ya da
// tutmuyorsa kurmaz (UpdateIntegrity.kt).
const sha256 = crypto.createHash('sha256').update(fs.readFileSync(dstApk)).digest('hex');

// ── 5. Write latest.json ───────────────────────────────────────────────────
const latest = {
  versionCode: newCode,
  versionName,
  notes,
  apkPath: '/update/app-latest.apk',
  sha256,
};
fs.writeFileSync(latestPath, JSON.stringify(latest, null, 2) + '\n', 'utf-8');

// ── 6. Summary ─────────────────────────────────────────────────────────────
console.log('');
console.log('═══════════════════════════════════════════');
console.log('  Release published successfully!');
console.log(`  versionCode : ${newCode}`);
console.log(`  versionName : ${versionName}`);
console.log(`  notes       : ${notes}`);
console.log(`  APK size    : ${(apkSize / 1024 / 1024).toFixed(2)} MB`);
console.log(`  output      : ${dstApk}`);
console.log('═══════════════════════════════════════════');
