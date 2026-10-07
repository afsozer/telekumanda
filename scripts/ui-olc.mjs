// Ekran ölçüm aracı — görme yeteneği olmayan ajanların arayüzü doğrulama kanalı.
//
// Neden var: "ekran görüntüsü al ve mockup'la karşılaştır" göremeyen bir modele
// verilemez; model ya körlemesine "benziyor" der ya da hiç bakmaz. İkisi de
// doğrulama değil. Bu araç göze sorulan soruyu SAYIYA çevirir: taşma var mı,
// düğüm nerede, dokunma hedefi kaç dp, cam bölgesi gerçekten bulanık mı, iki
// ekran görüntüsü arasındaki fark yüzde kaç.
//
// Tarayıcı tarafında aynı iş DOM ölçümüyle yapılmıştı (docs/mockups/*.html
// doğrulaması); bu onun Android karşılığı.
//
// Dış bağımlılık YOK — PNG çözümü Node'un kendi zlib'iyle elde yapılıyor.
// Bir npm paketi eklemek, ajanın doğrulama adımını "kurulum başarısız" diye
// atlamasına açık kapı bırakıyordu.
//
// Kullanım:
//   node scripts/ui-olc.mjs duzen  <ui.xml> [--yogunluk 480] [--bekle "Sohbet,Merkez"]
//   node scripts/ui-olc.mjs piksel <ekran.png> --bolge x,y,g,y [--bolge ...]
//   node scripts/ui-olc.mjs fark   <a.png> <b.png>
//
// Çıkış kodu: bulgusuz 0, bulgu varsa 1 (ajan bunu kapı olarak kullanır).

import { readFileSync } from 'node:fs';
import { inflateSync } from 'node:zlib';

// ---------------------------------------------------------------- PNG çözümü

// screencap -p çıktısı: 8-bit, interlace yok, renk tipi 6 (RGBA) ya da 2 (RGB).
// Başka bir biçim gelirse sessizce yanlış sayı üretmektense patlıyoruz.
function pngOku(yol) {
  const buf = readFileSync(yol);
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error(`PNG değil: ${yol}`);

  let p = 8, gen = 0, yuk = 0, derinlik = 0, renkTipi = 0;
  const parcalar = [];
  while (p < buf.length) {
    const uzunluk = buf.readUInt32BE(p);
    const tip = buf.toString('ascii', p + 4, p + 8);
    const veri = buf.subarray(p + 8, p + 8 + uzunluk);
    if (tip === 'IHDR') {
      gen = veri.readUInt32BE(0);
      yuk = veri.readUInt32BE(4);
      derinlik = veri[8];
      renkTipi = veri[9];
      if (veri[12] !== 0) throw new Error('interlace destelenmiyor');
    } else if (tip === 'IDAT') parcalar.push(veri);
    else if (tip === 'IEND') break;
    p += 12 + uzunluk;
  }
  if (derinlik !== 8) throw new Error(`bit derinliği ${derinlik} destelenmiyor`);
  const kanal = renkTipi === 6 ? 4 : renkTipi === 2 ? 3 : 0;
  if (!kanal) throw new Error(`renk tipi ${renkTipi} destelenmiyor`);

  const ham = inflateSync(Buffer.concat(parcalar));
  const satirBayt = gen * kanal;
  const piksel = Buffer.alloc(yuk * satirBayt);

  // Satır filtreleri (PNG spec 9.2). Her satır kendinden önceki satıra ve
  // soldaki piksele göre kodlanmış; sırayla geri almak zorunlu.
  for (let s = 0; s < yuk; s++) {
    const filtre = ham[s * (satirBayt + 1)];
    const kaynak = ham.subarray(s * (satirBayt + 1) + 1, (s + 1) * (satirBayt + 1));
    const hedef = piksel.subarray(s * satirBayt, (s + 1) * satirBayt);
    const ust = s > 0 ? piksel.subarray((s - 1) * satirBayt, s * satirBayt) : null;
    for (let i = 0; i < satirBayt; i++) {
      const a = i >= kanal ? hedef[i - kanal] : 0;
      const b = ust ? ust[i] : 0;
      const c = ust && i >= kanal ? ust[i - kanal] : 0;
      let d = kaynak[i];
      if (filtre === 1) d += a;
      else if (filtre === 2) d += b;
      else if (filtre === 3) d += (a + b) >> 1;
      else if (filtre === 4) {
        const t = a + b - c;
        const pa = Math.abs(t - a), pb = Math.abs(t - b), pc = Math.abs(t - c);
        d += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      hedef[i] = d & 0xff;
    }
  }
  return { gen, yuk, kanal, piksel };
}

const parlaklik = (r, g, b) => 0.2126 * r + 0.7152 * g + 0.0722 * b;

// WCAG bağıl parlaklık — kontrast oranı için gama düzeltmesi şart, düz
// ortalama %20'ye varan yanlış oran veriyor.
function bagilParlaklik(r, g, b) {
  const f = (v) => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}

// ------------------------------------------------------------------- bölgeler

function bolgeOku(g, alan) {
  const [x0, y0, gen, yuk] = alan;
  const cikti = [];
  for (let y = y0; y < Math.min(y0 + yuk, g.yuk); y++) {
    for (let x = x0; x < Math.min(x0 + gen, g.gen); x++) {
      const i = (y * g.gen + x) * g.kanal;
      cikti.push([g.piksel[i], g.piksel[i + 1], g.piksel[i + 2]]);
    }
  }
  return cikti;
}

// Laplacian varyansı: keskinlik ölçüsü. Cam/bulanık bölgede DÜŞÜK, keskin metin
// ya da net kenar varsa YÜKSEK olur. "Blur gerçekten uygulandı mı" sorusunun
// göze bakmadan cevabı bu.
function keskinlik(g, alan) {
  const [x0, y0, gen, yuk] = alan;
  const degerler = [];
  for (let y = y0 + 1; y < Math.min(y0 + yuk - 1, g.yuk - 1); y++) {
    for (let x = x0 + 1; x < Math.min(x0 + gen - 1, g.gen - 1); x++) {
      const p = (xx, yy) => {
        const i = (yy * g.gen + xx) * g.kanal;
        return parlaklik(g.piksel[i], g.piksel[i + 1], g.piksel[i + 2]);
      };
      degerler.push(
        4 * p(x, y) - p(x - 1, y) - p(x + 1, y) - p(x, y - 1) - p(x, y + 1)
      );
    }
  }
  if (!degerler.length) return 0;
  const ort = degerler.reduce((a, b) => a + b, 0) / degerler.length;
  return degerler.reduce((a, b) => a + (b - ort) ** 2, 0) / degerler.length;
}

function pikselKomut(yol, bolgeler) {
  const g = pngOku(yol);
  console.log(`görsel: ${g.gen}x${g.yuk}`);
  let bulgu = 0;
  for (const alan of bolgeler) {
    const pikseller = bolgeOku(g, alan);
    if (!pikseller.length) {
      console.log(`  [${alan}] BULGU: bölge görselin dışında`);
      bulgu++;
      continue;
    }
    const ort = [0, 1, 2].map(
      (k) => Math.round(pikselleri(pikseller, k))
    );
    // Metin/zemin kontrastı: bölgedeki en açık %5 ile en koyu %5 karşılaştırılır.
    // Metnin tam koordinatını bilmeye gerek kalmıyor.
    const sirali = pikselleri.sirala(pikseller);
    const dilim = Math.max(1, Math.floor(sirali.length * 0.05));
    const acik = ortalamaRenk(sirali.slice(-dilim));
    const koyu = ortalamaRenk(sirali.slice(0, dilim));
    const l1 = bagilParlaklik(...acik) + 0.05;
    const l2 = bagilParlaklik(...koyu) + 0.05;
    const oran = (Math.max(l1, l2) / Math.min(l1, l2)).toFixed(2);
    const kes = keskinlik(g, alan).toFixed(1);
    console.log(
      `  [${alan}] ortalama=rgb(${ort}) kontrast=${oran}:1 keskinlik=${kes}`
    );
  }
  return bulgu;
}

function pikselleri(liste, kanal) {
  return liste.reduce((a, p) => a + p[kanal], 0) / liste.length;
}
pikselleri.sirala = (liste) =>
  [...liste].sort((a, b) => parlaklik(...a) - parlaklik(...b));

function ortalamaRenk(liste) {
  return [0, 1, 2].map((k) => pikselleri(liste, k));
}

// --------------------------------------------------------------- düzen (XML)

// uiautomator XML'i düz metin; tam bir XML çözümleyici gereksiz ama HİYERARŞİ
// gerekli: bir düğümün gerçekten küçük mü yoksa kabı tarafından kırpılmış mı
// olduğu ancak ebeveyniyle kıyaslanınca anlaşılıyor. Düz liste okurken
// kaydırma listesinin dibindeki yarım görünen kart "135x3dp" diye sahte ihlal
// üretiyordu (17.08.2026, canlıda).
//
// Etiketler sırayla taranır: `<node .../>` yaprak, `<node ...>` kap, `</node>`
// kapanış. Yığınla ebeveyn takip edilir.
function duzenOku(yol) {
  const x = readFileSync(yol, 'utf8');
  const dugumler = [];
  const yigin = [];
  const desen = /<node\b([^>]*?)(\/?)>|<\/node>/g;
  let m;
  while ((m = desen.exec(x)) !== null) {
    if (m[0] === '</node>') { yigin.pop(); continue; }
    const nitelik = (ad) => {
      const bul = m[1].match(new RegExp(`${ad}="([^"]*)"`));
      return bul ? bul[1] : '';
    };
    const b = nitelik('bounds').match(/\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/);
    const d = {
      sinif: nitelik('class').split('.').pop(),
      id: nitelik('resource-id'),
      metin: nitelik('text'),
      aciklama: nitelik('content-desc'),
      tiklanabilir: nitelik('clickable') === 'true',
      kutu: b ? { x1: +b[1], y1: +b[2], x2: +b[3], y2: +b[4] } : null,
      ebeveyn: yigin.length ? yigin[yigin.length - 1] : null,
    };
    dugumler.push(d);
    if (!m[2]) yigin.push(d); // öz-kapanan değilse kap: yığına gir
  }
  return dugumler;
}

// Düğüm kabının kenarına dayanmış mı? Dayanmışsa görünen boyutu gerçek boyutu
// değildir — ölçüm geçersizdir, ihlal sayılmaz.
function kirpikMi(d) {
  const k = d.kutu;
  for (let e = d.ebeveyn; e; e = e.ebeveyn) {
    if (!e.kutu) continue;
    if (k.x1 <= e.kutu.x1 || k.y1 <= e.kutu.y1 || k.x2 >= e.kutu.x2 || k.y2 >= e.kutu.y2) return true;
  }
  return false;
}

const kesisiyor = (a, b) =>
  a.x1 < b.x2 && b.x1 < a.x2 && a.y1 < b.y2 && b.y1 < a.y2;

function duzenKomut(yol, yogunluk, beklenen) {
  const dugumler = duzenOku(yol).filter((d) => d.kutu);
  if (!dugumler.length) {
    console.log('BULGU: hiç düğüm yok — dump boş ya da bozuk');
    return 1;
  }
  const kok = dugumler[0].kutu;
  const ekranG = kok.x2, ekranY = kok.y2;
  const dp = (px) => Math.round((px * 160) / yogunluk);
  console.log(`ekran: ${ekranG}x${ekranY} px @ ${yogunluk}dpi | düğüm: ${dugumler.length}`);

  let bulgu = 0;
  const yaz = (s) => { console.log('  BULGU: ' + s); bulgu++; };
  const ad = (d) => d.id || d.metin || d.aciklama || d.sinif;

  for (const d of dugumler) {
    const k = d.kutu;
    if (k.x1 < 0 || k.y1 < 0 || k.x2 > ekranG || k.y2 > ekranY)
      yaz(`ekran dışına taşıyor: ${ad(d)} [${k.x1},${k.y1}][${k.x2},${k.y2}]`);
    if (k.x2 <= k.x1 || k.y2 <= k.y1)
      yaz(`sıfır/negatif boyut: ${ad(d)}`);
    // 48dp Material'ın asgari dokunma hedefi; altına düşen düğüm parmakla
    // ıskalanıyor ve bu göze bakarak fark edilmiyor.
    //
    // KIRPILMIŞ DÜĞÜMÜ ÖLÇME: uiautomator ekrandan taşan düğümün yalnız GÖRÜNEN
    // kısmını raporluyor. Kaydırma listesinin altında yarısı görünen bir kart
    // "135x3dp" diye düşüyor ve bu sahte bir ihlal — gerçek boyutu 157x56.
    // Canlıda yaşandı (17.08.2026): araç bir ajanı olmayan hatanın peşine
    // düşürecekti. Ekran kenarına değen düğümün hedef ölçüsü ölçülemez.
    if (d.tiklanabilir) {
      const g = dp(k.x2 - k.x1), y = dp(k.y2 - k.y1);
      if (g < 48 || y < 48) {
        if (kirpikMi(d)) console.log(`  atlandı (kabına kırpık, ölçülemez): ${ad(d)} ${g}x${y}dp`);
        else yaz(`dokunma hedefi küçük: ${ad(d)} ${g}x${y}dp`);
      }
    }
  }

  // Metin çakışması: yalnız gerçekten metin taşıyan düğümler arasında bakılır,
  // kapsayıcıların iç içe geçmesi normal.
  const metinler = dugumler.filter((d) => d.metin);
  for (let i = 0; i < metinler.length; i++)
    for (let j = i + 1; j < metinler.length; j++)
      if (kesisiyor(metinler[i].kutu, metinler[j].kutu))
        yaz(`metin çakışması: "${metinler[i].metin}" ↔ "${metinler[j].metin}"`);

  for (const b of beklenen) {
    const var_mi = dugumler.some(
      (d) => d.id.endsWith(b) || d.metin === b || d.aciklama === b
    );
    if (!var_mi) yaz(`beklenen düğüm yok: ${b}`);
  }

  if (!bulgu) console.log('  bulgu yok');
  return bulgu;
}

// ------------------------------------------------------------------ fark

// İki ekran görüntüsü arasındaki fark. Onaylanmış bir ekranı temel alıp
// sonraki fazların onu bozmadığını kanıtlamak için — "gerileme oldu mu"
// sorusunun göze bakmayan cevabı.
function farkKomut(a, b) {
  const g1 = pngOku(a), g2 = pngOku(b);
  if (g1.gen !== g2.gen || g1.yuk !== g2.yuk) {
    console.log(`BULGU: boyutlar farklı ${g1.gen}x${g1.yuk} ≠ ${g2.gen}x${g2.yuk}`);
    return 1;
  }
  let farkli = 0;
  let x1 = g1.gen, y1 = g1.yuk, x2 = 0, y2 = 0;
  for (let y = 0; y < g1.yuk; y++) {
    for (let x = 0; x < g1.gen; x++) {
      const i = (y * g1.gen + x) * g1.kanal;
      const j = (y * g2.gen + x) * g2.kanal;
      const d =
        Math.abs(g1.piksel[i] - g2.piksel[j]) +
        Math.abs(g1.piksel[i + 1] - g2.piksel[j + 1]) +
        Math.abs(g1.piksel[i + 2] - g2.piksel[j + 2]);
      if (d > 24) {          // eşik: JPEG'siz PNG'de gürültü yok, 24 güvenli
        farkli++;
        if (x < x1) x1 = x; if (y < y1) y1 = y;
        if (x > x2) x2 = x; if (y > y2) y2 = y;
      }
    }
  }
  const yuzde = ((farkli / (g1.gen * g1.yuk)) * 100).toFixed(2);
  console.log(`farklı piksel: %${yuzde}`);
  if (farkli) console.log(`  değişen alan: [${x1},${y1}][${x2},${y2}]`);
  return 0;
}

// ------------------------------------------------------------------ giriş

const [komut, ...kalan] = process.argv.slice(2);
const bayrak = (ad, varsayilan) => {
  const i = kalan.indexOf('--' + ad);
  return i >= 0 ? kalan[i + 1] : varsayilan;
};
const dosyalar = kalan.filter((a, i) => !a.startsWith('--') && !kalan[i - 1]?.startsWith('--'));

try {
  let bulgu = 0;
  if (komut === 'duzen') {
    const beklenen = bayrak('bekle', '').split(',').filter(Boolean);
    bulgu = duzenKomut(dosyalar[0], +bayrak('yogunluk', 480), beklenen);
  } else if (komut === 'piksel') {
    const bolgeler = [];
    kalan.forEach((a, i) => {
      if (a === '--bolge') bolgeler.push(kalan[i + 1].split(',').map(Number));
    });
    if (!bolgeler.length) throw new Error('en az bir --bolge x,y,g,y gerekli');
    bulgu = pikselKomut(dosyalar[0], bolgeler);
  } else if (komut === 'fark') {
    bulgu = farkKomut(dosyalar[0], dosyalar[1]);
  } else {
    console.log('kullanım: ui-olc.mjs duzen|piksel|fark ... (başlıktaki örneklere bak)');
    process.exit(2);
  }
  process.exit(bulgu ? 1 : 0);
} catch (e) {
  console.error('HATA: ' + e.message);
  process.exit(2);
}
