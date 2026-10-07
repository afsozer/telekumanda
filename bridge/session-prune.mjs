import path from 'node:path';

// Bir haftadan fazla inaktif kalmis backend oturumlarini budar.
//
// INAKTIFLIK OLCUTU her backend'in kendi liste kaydindaki `mtime`'dir ve uc
// backend'de de ayni seyi gosterir: son kullanici promptu ya da son ajan mesaji.
// - claude-app: transcript icindeki son mesajin zamani
// - codex-app: rollout dosyasinin son yazilma zamani
// - opencode-app: opencode.db'deki `time_updated`
//
// MUAFIYETLER (kullanici karari, 19.08.2026):
// - **Cowork / calisma alani oturumlari.** cwd cowork kokunun altindaysa
//   DOKUNULMAZ. Bunlar dosya teslimati olan is oturumlari, sohbet gecmisi degil;
//   bir dava dosyasi aylarca sessiz kalip sonra devam edebilir.
// - **Pinlenmis oturumlar.** Kullanici zaten "bunu sakla" demis.
//
// Secim ile silme BILEREK ayri: `selectStale` saf bir fonksiyon ve test
// edilebilir, `pruneSessions` yalnizca onun sonucunu uygular. Yanlis secim
// testte goze batar, geri alinamaz silme aninda degil.

/** `p` (dosya/klasor) `root` altinda mi? Kokun kendisi de altinda sayilir. */
export function isUnderRoot(p, root) {
  if (!p || !root) return false;
  // Ayirici normalize: opencode cwd'yi '/' ile veriyor, Windows yollari '\' ile.
  const norm = (x) => path.resolve(String(x).replace(/\//g, path.sep));
  let rel;
  try { rel = path.relative(norm(root), norm(p)); } catch { return false; }
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
}

/**
 * Budanacaklari secer. Girdi backend'in liste kaydi: `{ id, cwd, mtime, pinned }`.
 *
 * `mtime` yoksa ya da sayi degilse oturum SAKLANIR — bilinmeyen yas tek basina
 * silme gerekcesi degil. (Tersi "0 yasli = cok eski" demek olurdu ve zamani
 * okunamayan her oturum ilk turda ucardi.)
 *
 * TEK ISTISNA: yasi bilinmeyen VE ICI BOS oturum (`turns === 0`) budanir.
 * Kaybedilecek bir sey yok, oysa saklamak sonsuza dek saklamak demek — bu
 * kayitlarin hicbir zaman damgasi olmadigi icin yaslari da hicbir zaman
 * gelmez. 19.08.2026'da codex'te 32 tane olculdu (cogu birim testlerin gecici
 * klasorlerinden kalma kabuklar).
 *
 * `turns` alani OLMAYAN kayit bu istisnaya girmez (`=== 0` bilerek kati):
 * eksik alani "bos" saymak, tur sayisini bildirmeyen bir backend'in butun
 * gecmisini sessizce silerdi.
 */
export function selectStale({ sessions = [], maxAgeMs, now = Date.now(), coworkRoot = '' } = {}) {
  const sonuc = { stale: [], cowork: 0, pinned: 0, fresh: 0, unknown: 0, emptyUnknown: 0 };
  if (!Number.isFinite(maxAgeMs) || maxAgeMs <= 0) {
    sonuc.fresh = sessions.length;
    return sonuc;
  }
  for (const s of sessions) {
    if (!s || !s.id) continue;
    // `cowork` bayragi cwd kontrolunden AYRI: claude tarafinda ayni sohbetin
    // catal kopyalari gruplanıyor ve muafiyet grubun herhangi bir uyesinden
    // gelebiliyor. Yanlis yon bilerek "fazla saklamak".
    if (s.cowork === true || (coworkRoot && isUnderRoot(s.cwd, coworkRoot))) { sonuc.cowork += 1; continue; }
    if (s.pinned) { sonuc.pinned += 1; continue; }
    const at = Number(s.mtime);
    if (!Number.isFinite(at) || at <= 0) {
      if (s.turns === 0) { sonuc.emptyUnknown += 1; sonuc.stale.push(s); continue; }
      sonuc.unknown += 1;
      continue;
    }
    if (now - at <= maxAgeMs) { sonuc.fresh += 1; continue; }
    sonuc.stale.push(s);
  }
  return sonuc;
}

/**
 * Secip siler. `remove(session)` backend'in kendi `deleteDiskSession`'ina
 * baglanmali — her backend'in deposu farkli (jsonl, rollout, sqlite) ve neyin
 * silinecegini yalniz o modul biliyor. Kaydin TAMAMI verilir cunku claude'da bir
 * "oturum" birden fazla dosya olabiliyor (`ids`).
 *
 * Tek tek hata yutulur ve sayilir: bir oturumun kilitli olmasi digerlerini
 * engellememeli, budama kritik yol degil.
 */
export async function pruneSessions({
  sessions = [],
  remove,
  maxAgeMs,
  now = Date.now(),
  coworkRoot = '',
  log = () => {},
} = {}) {
  const secim = selectStale({ sessions, maxAgeMs, now, coworkRoot });
  const rapor = {
    removed: 0,
    failed: 0,
    cowork: secim.cowork,
    pinned: secim.pinned,
    fresh: secim.fresh,
    unknown: secim.unknown,
    emptyUnknown: secim.emptyUnknown,
  };
  for (const s of secim.stale) {
    try {
      const r = await remove(s);
      if (r && r.ok === false) {
        rapor.failed += 1;
        log(`oturum budama: ${s.id} silinemedi: ${String(r.error || '').slice(0, 120)}`);
        continue;
      }
      rapor.removed += 1;
    } catch (e) {
      rapor.failed += 1;
      log(`oturum budama: ${s.id} silinemedi: ${String(e?.message || e).slice(0, 120)}`);
    }
  }
  return rapor;
}
