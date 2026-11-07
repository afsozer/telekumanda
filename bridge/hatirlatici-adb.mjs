// Hatırlatma listesini ADB ile cihaza iter (21.10.2026). Android uygulaması
// hatırlatmayı kendi alarmıyla çalıyor; listeyi açılışta köprüden çekiyor.
// Bu modül aradaki boşluğu kapatır: PC'de hatırlatma kurulduğunda (not skill'i,
// uygulama, ekran görüntüsünden not) uygulama açılmasa da alarm kurulsun.
//
// - Telefona yük yok: explicit broadcast süreci kısa süre uyandırır, uygulama
//   listeyi yerelde alarma çevirir. Ağ isteği yok.
// - Oturum KALICI DEĞİL: her itme `adb connect` ile başlar, `disconnect` ile
//   biter. Sürekli açık ADB oturumu telefonu sık uyandırıyordu (26-27.07.2026:
//   saatte ~700 uyanma, %3,5/saat pil; eski adb-push.mjs notu).
// - Liste değişmediyse itilmez (cihaz başına son başarılı yük tutulur).
// - Ulaşılamazsa sessizce vazgeçilir: uygulama sonraki açılışta yine çeker.
// - Alıcı `android.permission.DUMP` ister; adb shell bu izne sahip, üçüncü
//   taraf uygulamalar değil.
import { execFile } from 'node:child_process';
import fs from 'node:fs';

const ALICI = 'com.agent.bridge/.HatirlaticiAdbReceiver';
const EYLEM = 'com.agent.bridge.HATIRLATICI_LISTESI';

export function createHatirlaticiAdb({
  adb = '',
  devices = [],
  liste,                       // () => upcoming() dizisi
  log = () => {},
  runCommand = null,           // testler için: (args) => {err, out}
  gecikmeMs = 3000,
} = {}) {
  const enjekte = typeof runCommand === 'function';
  const etkin = (enjekte || (adb && fs.existsSync(adb))) && devices.length > 0 && typeof liste === 'function';
  if (!etkin) return { etkin: false, planla: () => {}, it: async () => [] };

  const run = enjekte
    ? (args) => Promise.resolve(runCommand(args))
    : (args) => new Promise((resolve) => {
      execFile(adb, args, { timeout: 10000, windowsHide: true }, (err, stdout, stderr) => {
        resolve({ err, out: String(stdout || '') + String(stderr || '') });
      });
    });

  const sonYuk = new Map();     // cihaz -> son başarılı base64 yük
  let zamanlayici = null;
  let calisiyor = Promise.resolve();

  async function cihazaIt(seri, yuk) {
    const komut = `am broadcast -n ${ALICI} -a ${EYLEM} --es kayitlar '${yuk}'`;
    const tamam = (r) => !r.err && /Broadcast completed/i.test(r.out);
    await run(['connect', seri]);
    let r = await run(['-s', seri, 'shell', komut]);
    if (!tamam(r)) {
      // Yarım açık kalmış oturum: "already connected" deyip ilk komutta
      // çöküyor (eski adb-push.mjs'te canlı görüldü). Tek tekrar.
      await run(['disconnect', seri]);
      await run(['connect', seri]);
      r = await run(['-s', seri, 'shell', komut]);
    }
    await run(['disconnect', seri]);
    if (!tamam(r)) log(`hatirlatici-adb ${seri} ulasilamadi: ${String(r.err?.message || r.out).slice(0, 160)}`);
    return tamam(r);
  }

  // Listeyi hemen iter; cihaz başına {seri, ok, atlandi?} döner, throw etmez.
  async function it() {
    let yuk;
    try {
      // base64: Türkçe karakter ve tırnak kaçışı derdi yok, sh tek tırnağına
      // güvenle girer ([A-Za-z0-9+/=]).
      yuk = Buffer.from(JSON.stringify(liste() || []), 'utf8').toString('base64');
    } catch (err) {
      log(`hatirlatici-adb liste alinamadi: ${String(err?.message || err).slice(0, 160)}`);
      return [];
    }
    const sonuc = [];
    for (const seri of devices) {
      if (sonYuk.get(seri) === yuk) { sonuc.push({ seri, ok: true, atlandi: true }); continue; }
      const ok = await cihazaIt(seri, yuk).catch(() => false);
      if (ok) sonYuk.set(seri, yuk);
      sonuc.push({ seri, ok });
    }
    return sonuc;
  }

  // Değişiklikten sonra çağrılır; art arda gelenler tek itmede birleşir.
  function planla() {
    if (zamanlayici) clearTimeout(zamanlayici);
    zamanlayici = setTimeout(() => {
      zamanlayici = null;
      calisiyor = calisiyor.then(it, it);
    }, gecikmeMs);
    zamanlayici.unref?.();
  }

  return { etkin: true, planla, it };
}
