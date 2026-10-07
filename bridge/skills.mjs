// Agent Skills envanteri — TEK okuyucu.
//
// Uc backend de (claude-app / codex-app / opencode-app) ayni disk formatini
// kullanir: <dizin>/<ad>/SKILL.md, YAML frontmatter'inda `name` ve
// `description`. Once her backend kendi listesini ayri ayri cikariyordu ve
// yalniz KLASOR ADINI donuyordu; cowork'teki Skill pill'i kisa aciklamayi da
// gosterdigi icin ayristirma buraya toplandi.

import fs from 'node:fs';
import path from 'node:path';

// Minik YAML: yalniz `key: value` ve devam eden girintili satirlar. Tam bir
// YAML ayristiricisi gerekmiyor — frontmatter'da duz skaler alanlar var.
export function parseSkillFrontmatter(text) {
  const out = {};
  if (typeof text !== 'string') return out;
  const lines = text.replace(/^﻿/, '').split(/\r?\n/);
  if (lines[0]?.trim() !== '---') return out;
  let key = null;
  for (let i = 1; i < lines.length; i++) {
    const line = lines[i];
    if (line.trim() === '---') break;
    // Girintili satir = onceki degerin devami (uzun description'lar boyle sarilir).
    if (key && /^\s+\S/.test(line) && !/^\s*[\w-]+\s*:/.test(line)) {
      out[key] += ' ' + line.trim();
      continue;
    }
    const m = /^([\w-]+)\s*:\s*(.*)$/.exec(line);
    if (!m) { key = null; continue; }
    key = m[1];
    out[key] = m[2].trim();
  }
  for (const k of Object.keys(out)) {
    out[k] = out[k].replace(/^["']|["']$/g, '').trim();
  }
  return out;
}

// Aciklamalar bazen tetikleyici kelime listesiyle sayfalarca surer (bkz.
// dataviz). Pill sheet'inde iki satir gosteriliyor; ilk cumleyi alip
// kalanini kirpmak listeyi okunur tutuyor.
export function shortenSkillDescription(desc, max = 180) {
  const s = (desc || '').replace(/\s+/g, ' ').trim();
  if (!s) return '';
  if (s.length <= max) return s;
  const cut = s.slice(0, max);
  const stop = Math.max(cut.lastIndexOf('. '), cut.lastIndexOf('; '));
  if (stop > max * 0.4) return cut.slice(0, stop + 1);
  const space = cut.lastIndexOf(' ');
  return (space > 0 ? cut.slice(0, space) : cut) + '…';
}

// Verilen dizinleri tarayip [{ name, description, group }] dondurur. Ayni ad
// birden cok dizinde varsa ILK dizin kazanir (cagiran onceligi siralamayla
// verir); nokta-onekli girdiler ve SKILL.md'siz klasorler atlanir.
//
// Girdi ya duz yol ya da { dir, group } olabilir. Bazi backend'ler skill'leri kategori
// klasorlerine bolunmustur (skills/<kategori>/<ad>/SKILL.md); kategori adi
// group olarak tasinir ve UI'da rozet olur.
export function readSkillCatalog(dirs = []) {
  const found = new Map();
  for (const entry of dirs) {
    const dir = typeof entry === 'string' ? entry : entry?.dir;
    const group = typeof entry === 'string' ? '' : (entry?.group || '');
    if (!dir) continue;
    let names;
    // withFileTypes KULLANMA: hesap havuzlarindaki skill'ler ~/.claude/skills'e
    // JUNCTION'dir ve Dirent.isDirectory() junction'da false doner (Windows'ta
    // isSymbolicLink true olur). Tur kapisi yuzunden 9 skill'in 8'i eleniyordu.
    // Olcut basit: <ad>/SKILL.md okunabiliyorsa skill'dir — readFileSync
    // junction'i da symlink'i de kendiliginden izler, duz dosyada hata verir.
    try { names = fs.readdirSync(dir); } catch { continue; }
    for (const name of names) {
      if (name.startsWith('.') || found.has(name)) continue;
      let raw;
      try { raw = fs.readFileSync(path.join(dir, name, 'SKILL.md'), 'utf-8'); } catch { continue; }
      const fm = parseSkillFrontmatter(raw);
      // Kanonik ad KLASOR adidir: CLI de skill'i boyle bildiriyor, describeSkills
      // eslesmesi buna dayaniyor. frontmatter'daki name farkliysa yok sayilir.
      found.set(name, {
        name,
        description: shortenSkillDescription(fm.description),
        group,
      });
    }
  }
  return [...found.values()].sort((a, b) => a.name.localeCompare(b.name));
}

// <kok>/<kategori>/<ad>/SKILL.md duzenini { dir, group } listesine cevirir.
// Kategori siralamasi onceligi belirler: onceliklendirilenler basa alinir
// (ayni skill birden cok kategoride bulunabiliyor — ornegin docx hem
// agentbridge-cowork hem productivity altinda).
export function groupedSkillDirs(root, preferred = []) {
  let names;
  try { names = fs.readdirSync(root); } catch { return []; }
  const groups = names
    .filter(n => !n.startsWith('.'))
    .sort((a, b) => {
      const ia = preferred.indexOf(a);
      const ib = preferred.indexOf(b);
      if (ia !== ib) return (ia < 0 ? Infinity : ia) - (ib < 0 ? Infinity : ib);
      return a.localeCompare(b);
    });
  return groups.map(g => ({ dir: path.join(root, g), group: g }));
}

// CLI'ın bildirdiği ad listesini diskteki açıklamalarla eşler. Claude CLI
// init event'i eklenti/yerleşik skill'leri de sayar; onlar diskteki kullanıcı
// dizinlerinde bulunmaz — açıklamasız ama LİSTEDE kalırlar, çünkü oturumda
// gerçekten yüklüdürler.
export function describeSkills(names, dirs = []) {
  const byName = new Map(readSkillCatalog(dirs).map(s => [s.name, s]));
  if (!Array.isArray(names) || !names.length) return [...byName.values()];
  return names
    .filter(n => typeof n === 'string' && n.trim())
    .map(n => byName.get(n) || { name: n, description: '', group: '' })
    .sort((a, b) => a.name.localeCompare(b.name));
}
