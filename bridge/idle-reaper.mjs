// Bosta kalan backend ALT SURECLERINI kapatir. Oturumu silmez.
//
// Sorun (16.09.2026'da olculdu): claude-app her sohbet icin kalici bir `claude`
// sureci dogruyor ve tur bitince onu KAPATMIYOR — sonraki mesaj hemen gitsin
// diye sicak tutuyor. Telefondan bir gunde acilan 9 sohbet, 9 canli surec =
// ~710 MB RAM + yanlarindaki MCP node'lari (~190 MB) ve hepsi bir gun boyunca
// bosta duruyor.
//
// Cozum: N dakikadir bosta duran sureci kapat. Bu GUVENLI cunku claude-app'te
// zaten var olan respawn deseni (setModel / setPermissionMode / setEffort)
// tam bunu yapiyor: idle'da child'i oldur, sonraki prompt `--resume <id>` ile
// ayni sohbeti geri acar. Kullanicinin gordugu tek fark: 30 dk sonra atilan
// ilk mesajin birkac saniye gec baslamasi (soguk acilis).
//
// Secim ile uygulama BILEREK ayri (session-prune.mjs ile ayni kalip):
// `selectIdleChildren` saf ve testlenebilir; backend yalniz sonucu uygular.

/**
 * Kapatilacak oturumlari secer. Girdi backend'in verdigi duz kayit:
 *   `{ id, hasChild, status, pendingApproval, lastActivity }`
 *
 * Kurallar:
 * - child yoksa zaten kapatacak bir sey yok (`noChild`).
 * - tur kosuyorsa (`status === 'running'`) DOKUNMA — kullaniciyi bekleten bir
 *   isi keseriz (`running`).
 * - onay bekliyorsa DOKUNMA — kullanici "evet/hayir" diyecek, surec olurse
 *   cevap bosa gider (`awaiting`).
 * - `lastActivity` bilinmiyorsa (0) surec YINE kapatilir: child ancak bir
 *   prompt'tan sonra dogar ve prompt lastActivity'yi yazar; zamani olmayan
 *   canli child anormaldir, sicak tutmanin gerekcesi yok (`unknown`).
 *   Disk budamasinin tersi bir secim: orada bilinmeyen yas SAKLAR cunku silme
 *   geri alinamaz; burada kapatma geri aliniyor (`--resume`).
 * - `maxIdleMs` sifir/negatif/gecersizse ozellik KAPALI, hicbir sey secilmez.
 */
export function selectIdleChildren({ sessions = [], maxIdleMs, now = Date.now() } = {}) {
  const sonuc = { idle: [], noChild: 0, running: 0, awaiting: 0, fresh: 0, unknown: 0 };
  if (!Number.isFinite(maxIdleMs) || maxIdleMs <= 0) return sonuc;
  for (const s of sessions) {
    if (!s || !s.id) continue;
    if (!s.hasChild) { sonuc.noChild += 1; continue; }
    if (s.status === 'running') { sonuc.running += 1; continue; }
    if (s.pendingApproval) { sonuc.awaiting += 1; continue; }
    const at = Number(s.lastActivity);
    if (!Number.isFinite(at) || at <= 0) { sonuc.unknown += 1; sonuc.idle.push(s); continue; }
    if (now - at < maxIdleMs) { sonuc.fresh += 1; continue; }
    sonuc.idle.push(s);
  }
  return sonuc;
}

/**
 * Secip kapatir. `kill(session)` backend'in kendi idle-kill yoluna baglanmali
 * (claude-app'te `killPersistentChild`: child'i oldurur VE disk imlecini dosya
 * sonuna sabitler — imleci sabitlemeden oldurmek son turu sohbete ikinci kez
 * yazdiriyordu, o yuzden elle killChildTree DEGIL).
 *
 * Tek tek hata yutulur ve sayilir: bir surecin kapanamamasi digerlerini
 * engellememeli.
 */
export function reapIdleChildren({ sessions = [], kill, maxIdleMs, now = Date.now(), log = () => {} } = {}) {
  const secim = selectIdleChildren({ sessions, maxIdleMs, now });
  const rapor = {
    killed: [], failed: 0,
    noChild: secim.noChild, running: secim.running, awaiting: secim.awaiting,
    fresh: secim.fresh, unknown: secim.unknown,
  };
  for (const s of secim.idle) {
    try {
      const r = kill(s);
      if (r && r.ok === false) { rapor.failed += 1; log(`bosta surec: ${s.id} kapatilamadi: ${String(r.error || '').slice(0, 120)}`); continue; }
      rapor.killed.push(s.id);
    } catch (e) {
      rapor.failed += 1;
      log(`bosta surec: ${s.id} kapatilamadi: ${String(e?.message || e).slice(0, 120)}`);
    }
  }
  return rapor;
}
