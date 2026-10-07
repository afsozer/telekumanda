#!/usr/bin/env node
// Usage: node bridge/release-lite.mjs <versionName> "<notes>"
// Only android/lite-version.properties and bridge/update/lite/ are versioned/published.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const bridgeDir = path.dirname(fileURLToPath(import.meta.url));
const androidDir = path.join(bridgeDir, '..', 'android');
const [versionName, notes] = process.argv.slice(2);
if (!versionName || !notes || !/^[\w.-]+-lite$/.test(versionName)) {
  console.error('Usage: node bridge/release-lite.mjs <versionName-lite> "<notes>"');
  process.exit(1);
}
const versionPath = path.join(androidDir, 'lite-version.properties');
const originalVersion = fs.readFileSync(versionPath, 'utf8');
const sourceCode = Number(originalVersion.match(/^versionCode=(\d+)$/m)?.[1]);
const updateDir = path.join(bridgeDir, 'update', 'lite');
const manifestPath = path.join(updateDir, 'latest.json');
const publishedCode = fs.existsSync(manifestPath)
  ? Number(JSON.parse(fs.readFileSync(manifestPath, 'utf8')).versionCode) : 0;
if (!Number.isInteger(sourceCode) || sourceCode < 1 || !Number.isInteger(publishedCode)) {
  throw new Error('Invalid Lite versionCode');
}
const versionCode = Math.max(sourceCode, publishedCode) + 1;
fs.writeFileSync(versionPath, `versionCode=${versionCode}\nversionName=${versionName}\n`);
try {
  const windows = process.platform === 'win32';
  const build = spawnSync(windows ? 'cmd.exe' : './gradlew',
    windows ? ['/c', 'gradlew.bat :app:assembleLite'] : [':app:assembleLite'],
    { cwd: androidDir, stdio: 'inherit' });
  if (build.error || build.status !== 0) throw build.error || new Error(`Gradle failed: ${build.status}`);
  const apkDir = path.join(androidDir, 'app', 'build', 'outputs', 'apk', 'lite');
  const metadata = JSON.parse(fs.readFileSync(path.join(apkDir, 'output-metadata.json'), 'utf8'));
  const output = metadata.elements[0];
  if (metadata.applicationId !== 'com.agent.bridge.lite' || output.versionCode !== versionCode || output.versionName !== versionName) {
    throw new Error('APK metadata does not match the Lite release');
  }
  const apk = fs.readFileSync(path.join(apkDir, output.outputFile));
  const sha256 = crypto.createHash('sha256').update(apk).digest('hex');
  fs.mkdirSync(updateDir, { recursive: true });
  const target = path.join(updateDir, 'app-latest.apk');
  fs.writeFileSync(`${target}.tmp`, apk);
  fs.renameSync(`${target}.tmp`, target);
  const manifest = { applicationId: metadata.applicationId, versionCode, versionName, notes,
    apkPath: '/update/lite/app-latest.apk', sha256 };
  fs.writeFileSync(`${manifestPath}.tmp`, JSON.stringify(manifest, null, 2) + '\n');
  fs.renameSync(`${manifestPath}.tmp`, manifestPath);
  console.log(`Lite published: ${versionName} (${versionCode}), ${apk.length} bytes, SHA-256 ${sha256}`);
} catch (error) {
  fs.writeFileSync(versionPath, originalVersion);
  throw error;
}
