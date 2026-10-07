// skills.mjs — Agent Skills envanteri (frontmatter ayrıştırma + katalog).
// Disk erişimi geçici bir dizinle test edilir; gerçek kullanıcı skill'lerine
// bakılmaz, böylece test makineden bağımsızdır.
import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import { parseSkillFrontmatter, shortenSkillDescription, readSkillCatalog, describeSkills, groupedSkillDirs } from '../skills.mjs';

let root;
let dirA;
let dirB;

function writeSkill(dir, name, body) {
  fs.mkdirSync(path.join(dir, name), { recursive: true });
  fs.writeFileSync(path.join(dir, name, 'SKILL.md'), body, 'utf-8');
}

before(() => {
  root = fs.mkdtempSync(path.join(os.tmpdir(), 'skills-test-'));
  dirA = path.join(root, 'a');
  dirB = path.join(root, 'b');
  writeSkill(dirA, 'alfa', '---\nname: alfa\ndescription: Alfa işini yapar.\n---\n\ngövde\n');
  writeSkill(dirA, 'delta', '---\nname: delta\ndescription: "Tırnaklı açıklama"\nmetadata:\n---\n');
  // Aynı ad iki dizinde: ilk dizin kazanmalı.
  writeSkill(dirB, 'alfa', '---\nname: alfa\ndescription: Golgede kalan.\n---\n');
  writeSkill(dirB, 'beta', '---\nname: beta\ndescription: Beta işini yapar.\n---\n');
  // SKILL.md'siz klasör ve nokta-önekli girdi listelenmemeli.
  fs.mkdirSync(path.join(dirB, 'bos-klasor'), { recursive: true });
  writeSkill(dirB, '.gizli', '---\nname: gizli\ndescription: x\n---\n');
});

after(() => { try { fs.rmSync(root, { recursive: true, force: true }); } catch {} });

describe('parseSkillFrontmatter', () => {
  it('name/description okur ve tırnakları soyar', () => {
    const fm = parseSkillFrontmatter('---\nname: x\ndescription: "Bir şey"\n---\ngövde');
    assert.equal(fm.name, 'x');
    assert.equal(fm.description, 'Bir şey');
  });

  it('girintili devam satırlarını aynı değere ekler', () => {
    const fm = parseSkillFrontmatter('---\ndescription: birinci\n  ikinci satir\n---\n');
    assert.equal(fm.description, 'birinci ikinci satir');
  });

  it('frontmatter yoksa boş döner', () => {
    assert.deepEqual(parseSkillFrontmatter('# başlık\nmetin'), {});
  });
});

describe('shortenSkillDescription', () => {
  it('kısa metni olduğu gibi bırakır', () => {
    assert.equal(shortenSkillDescription('kısa'), 'kısa');
  });

  it('uzun metni cümle sınırında keser', () => {
    const first = 'Bu skill Word belgeleri oluşturur, okur ve düzenler; şablonlarla da çalışır.';
    assert.equal(shortenSkillDescription(first + ' ' + 'x'.repeat(300)), first);
  });

  it('ilk cümle çok kısaysa onu değil kelime sınırını kullanır', () => {
    // Aksi halde "Tetikleyiciler: ..." gibi girişlerde açıklamanın tamamı gider.
    const out = shortenSkillDescription('Kısa giriş. ' + 'kelime '.repeat(60));
    assert.ok(out.length > 100, `beklenen dolu özet, gelen: ${out}`);
    assert.ok(out.endsWith('…'));
  });

  it('cümle sınırı yoksa kelime sınırında kırpar', () => {
    const out = shortenSkillDescription(('kelime '.repeat(60)).trim());
    assert.ok(out.length <= 181, `beklenen kısalma, gelen ${out.length}`);
    assert.ok(out.endsWith('…'));
  });
});

describe('readSkillCatalog', () => {
  it('SKILL.md olan klasörleri ada göre sıralı döner', () => {
    const cat = readSkillCatalog([dirA, dirB]);
    assert.deepEqual(cat.map(s => s.name), ['alfa', 'beta', 'delta']);
    assert.equal(cat[0].description, 'Alfa işini yapar.');
    assert.equal(cat[2].description, 'Tırnaklı açıklama');
  });

  it('ilk dizin aynı adı gölgeler', () => {
    assert.equal(readSkillCatalog([dirB, dirA])[0].description, 'Golgede kalan.');
  });

  it('okunamayan dizini atlar', () => {
    assert.deepEqual(readSkillCatalog([path.join(root, 'yok')]), []);
  });

  it('SKILL.md olmayan klasörü ve düz dosyayı listelemez', () => {
    assert.ok(!readSkillCatalog([dirB]).some(s => s.name === 'bos-klasor'));
    const withFile = path.join(root, 'duz');
    fs.mkdirSync(withFile, { recursive: true });
    fs.writeFileSync(path.join(withFile, 'okuma.md'), 'x', 'utf-8');
    assert.deepEqual(readSkillCatalog([withFile]), []);
  });

  // Ek hesap dizinlerindeki (<hesap>/skills/*) skill'ler ~/.claude/skills'e
  // junction'dir; Dirent.isDirectory() junction'da false dondugu icin tur
  // kapisi 9 skill'in 8'ini eliyordu (canli hata, 29.07.2026).
  it('junction/symlink ile bağlanmış skilli de okur', (t) => {
    const linkRoot = path.join(root, 'linkli');
    fs.mkdirSync(linkRoot, { recursive: true });
    const link = path.join(linkRoot, 'alfa');
    try {
      fs.symlinkSync(path.join(dirA, 'alfa'), link, 'junction');
    } catch {
      t.skip('symlink/junction oluşturulamadı (yetki yok)');
      return;
    }
    const cat = readSkillCatalog([linkRoot]);
    assert.deepEqual(cat.map(s => s.name), ['alfa']);
    assert.equal(cat[0].description, 'Alfa işini yapar.');
  });
});

// Kategorili düzen: <kök>/<kategori>/<ad>/SKILL.md
describe('groupedSkillDirs + kategorili katalog', () => {
  let nested;
  before(() => {
    nested = path.join(root, 'nested');
    writeSkill(path.join(nested, 'productivity'), 'docx', '---\nname: docx\ndescription: Productivity surumu.\n---\n');
    writeSkill(path.join(nested, 'agentbridge-cowork'), 'docx', '---\nname: docx\ndescription: Cowork surumu.\n---\n');
    writeSkill(path.join(nested, 'apple'), 'notes', '---\nname: notes\ndescription: Not al.\n---\n');
  });

  it('kategorileri { dir, group } olarak döner, öncelikliyi başa alır', () => {
    const dirs = groupedSkillDirs(nested, ['agentbridge-cowork']);
    assert.deepEqual(dirs.map(d => d.group), ['agentbridge-cowork', 'apple', 'productivity']);
  });

  it('katalog kategoriyi group alanında taşır', () => {
    const cat = readSkillCatalog(groupedSkillDirs(nested, ['agentbridge-cowork']));
    assert.deepEqual(cat.map(s => s.name), ['docx', 'notes']);
    assert.equal(cat[1].group, 'apple');
  });

  it('aynı skill iki kategorideyse öncelikli kategori kazanır', () => {
    const cat = readSkillCatalog(groupedSkillDirs(nested, ['agentbridge-cowork']));
    const docx = cat.find(s => s.name === 'docx');
    assert.equal(docx.group, 'agentbridge-cowork');
    assert.equal(docx.description, 'Cowork surumu.');
  });

  it('kök yoksa boş döner', () => {
    assert.deepEqual(groupedSkillDirs(path.join(root, 'yok-boyle-bir-kok')), []);
  });
});

describe('describeSkills', () => {
  it('CLI adlarını diskteki açıklamalarla eşler', () => {
    const out = describeSkills(['beta', 'alfa'], [dirA, dirB]);
    assert.deepEqual(out.map(s => s.name), ['alfa', 'beta']);
    assert.equal(out[1].description, 'Beta işini yapar.');
  });

  it('diskte olmayan adı açıklamasız ama listede tutar', () => {
    const out = describeSkills(['eklenti-skilli'], [dirA]);
    assert.deepEqual(out, [{ name: 'eklenti-skilli', description: '', group: '' }]);
  });

  it('ad listesi boşsa diskteki envanterin tamamını döner', () => {
    assert.deepEqual(describeSkills([], [dirA]).map(s => s.name), ['alfa', 'delta']);
  });
});
