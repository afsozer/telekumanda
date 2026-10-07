// Bitiş bildirimi PROMPT'U VEREN cihaza gitsin — kaynak defteri ve model→hedef
// eşlemesi. Gerekçe prompt-origin.mjs başında.
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createPromptOrigin } from '../prompt-origin.mjs';
import { createNotificationDelivery } from '../notification-delivery.mjs';

describe('prompt origin defteri', () => {
  it('turu başlatan cihazın modelini oturuma bağlar', () => {
    const origin = createPromptOrigin();

    origin.remember('omp', 'ses_1', 'SM-X820');

    assert.equal(origin.lookup('omp', 'ses_1'), 'SM-X820');
    // Backend farklıysa aynı oturum kimliği başka bir turdur.
    assert.equal(origin.lookup('claude-app', 'ses_1'), '');
  });

  // Modelsiz istek (web arayüzü, curl, eski sürüm) kayıt BIRAKMAMALI: çağıran
  // boş sonucu "bilmiyorum" diye okuyup tüm cihazlara yolluyor.
  it('model boşsa kayıt tutmaz', () => {
    const origin = createPromptOrigin();

    assert.equal(origin.remember('omp', 'ses_1', ''), false);
    assert.equal(origin.remember('omp', 'ses_1', '   '), false);
    assert.equal(origin.lookup('omp', 'ses_1'), '');
  });

  // Kabuk yeniden doğunca sessionId değişir, diskId kalır; olay ikisinden
  // biriyle gelebiliyor.
  it('kalıcı kimlikten de bulur', () => {
    const origin = createPromptOrigin();

    origin.remember('codex-app', 'thread-42', 'PTP-N49');

    assert.equal(origin.lookup('codex-app', 'yeni-kabuk', 'thread-42'), 'PTP-N49');
  });

  it('aynı oturuma başka cihazdan prompt gelirse son veren kazanır', () => {
    const origin = createPromptOrigin();

    origin.remember('omp', 'ses_1', 'PTP-N49');
    origin.remember('omp', 'ses_1', 'SM-X820');

    assert.equal(origin.lookup('omp', 'ses_1'), 'SM-X820');
    assert.equal(origin.size(), 1, 'aynı oturum tek kayıt olmalı');
  });

  it('süresi dolan kayıt unutulur', () => {
    let simdi = 1_000;
    const origin = createPromptOrigin({ ttlMs: 100, now: () => simdi });

    origin.remember('omp', 'ses_1', 'SM-X820');
    simdi += 101;

    assert.equal(origin.lookup('omp', 'ses_1'), '');
  });

  it('tavanı aşınca en eski kayıt düşer', () => {
    const origin = createPromptOrigin({ max: 2 });

    origin.remember('omp', 'a', 'M1');
    origin.remember('omp', 'b', 'M2');
    origin.remember('omp', 'c', 'M3');

    assert.equal(origin.lookup('omp', 'a'), '');
    assert.equal(origin.lookup('omp', 'c'), 'M3');
  });
});

const TELEFON = 'telefon-kurulum-0001';
const TABLET = 'tablet-kurulum-00001';

function kayitliTeslim() {
  const delivery = createNotificationDelivery({});
  delivery.configure({ deviceId: TELEFON, model: 'PTP-N49' });
  delivery.configure({ deviceId: TABLET, model: 'SM-X820' });
  return delivery;
}

describe('model → kayıtlı cihaz eşlemesi', () => {
  it('modeli kayıttaki cihaz kimliğine çevirir', () => {
    const delivery = kayitliTeslim();
    assert.deepEqual(delivery.deviceIdsForModel('SM-X820'), [TABLET]);
    assert.deepEqual(delivery.deviceIdsForModel('PTP-N49'), [TELEFON]);
  });

  // Bilinmeyen model BOŞ dizi döner; çağıran o durumda hedefsiz gönderip eski
  // davranışa (tüm cihazlar) düşüyor — susturmaktansa fazladan ötmek.
  it('bilinmeyen model boş döner', () => {
    const delivery = kayitliTeslim();
    assert.deepEqual(delivery.deviceIdsForModel('BILINMEYEN'), []);
    assert.deepEqual(delivery.deviceIdsForModel(''), []);
  });

  it('hedef verilince yalnız o cihazın kuyruğuna girer', async () => {
    const delivery = kayitliTeslim();
    const sonuc = await delivery.send({ kind: 'completed', deliveryId: 'e1' }, { targets: delivery.deviceIdsForModel('SM-X820') });
    assert.deepEqual(sonuc, [{ deviceId: TABLET, ok: false, pending: true }]);
    assert.equal(delivery.snapshot(TABLET).pushEvents.length, 1);
    assert.equal(delivery.snapshot(TELEFON).pushEvents.length, 0, 'telefona gitmemeli');
  });
});
