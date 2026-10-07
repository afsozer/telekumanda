// OpenCode model kataloğunun ORTAK süzgeci ve etiket kuralı.
//
// NEDEN AYRI MODÜL: v1 kataloğu `opencode models` CLI'sından, v2 kataloğu
// `GET /api/model`den geliyor — iki ayrı kaynak. Kural iki adaptörde ayrı ayrı
// yazıldığında listeler ayrışıyordu (26.09.2026 ölçümü: v1'de 27 model, v2'de
// 32; aradaki 7 fark Zen'in ÜCRETLİ modelleriydi, v2 süzmüyordu). Kullanıcı
// "iki liste birebir aynı olsun" dedi; tek kaynak burası.
//
// Kullanıcı kararı (16.08.2026): OpenCode Zen (`opencode/`) token başına
// ödeniyor, oradan YALNIZ bedava olanlar gelsin; başka sağlayıcıda süzme yok.
export const ZEN_PROVIDER = 'opencode';

// Bedava tespiti ÖNCELİKLE canlı fiyat kaydından yapılır (v1 bunu
// /config/providers'ten dolduruyor, bkz. setZenFreePrices). Kayıt yoksa ad
// kuralına düşülür: Zen'in bedava modelleri "-free" ekiyle biter.
const ZEN_FREE_NAME_RE = /-free$/;
// Ad kuralının bilinen istisnaları — YALNIZ fiyat kaydı yokken kullanılır,
// kayıt gelince EZİLİR. Rotasyondaki gizli modeller eksiz geliyor.
const ZEN_FREE_UNSUFFIXED = new Set(['big-pickle', 'grok-code']);
// Model değil SÜRÜM elemesi, sağlayıcı karası değil (kullanıcının masaüstü
// tercihlerinde de "hide").
const DEPRECATED_MODEL_RE = /^deepseek\/deepseek-(chat|reasoner)\b/;

let zenFreeById = null;   // Map<modelId, bool> | null (fiyat kaydı henüz yok)

/** Canlı fiyat kaydını yükler. Boş kayıt YAZILMAZ: eldekini silmesin. */
export function setZenFreePrices(map) {
  if (map && map.size) zenFreeById = map;
}

/** Yalnız test: modül düzeyi kaydı sıfırlar (ad kuralı yolunu sınamak için). */
export function __testResetZenFreePrices() { zenFreeById = null; }

export function isFreeZenModel(modelId) {
  if (zenFreeById && zenFreeById.has(modelId)) return zenFreeById.get(modelId);
  return ZEN_FREE_NAME_RE.test(modelId) || ZEN_FREE_UNSUFFIXED.has(modelId);
}

export function isAgentBridgeOpencodeModel(id) {
  const value = String(id || '');
  // DİKKAT: indexOf yoksa -1 döner ve slice(0, -1) son harfi kırpıp "sağlayıcı"
  // uydurur — sağlayıcısız kimlik sessizce geçerdi. Önce ayıraç var mı diye bak.
  const cut = value.indexOf('/');
  if (cut <= 0) return false;
  const provider = value.slice(0, cut);
  if (provider === ZEN_PROVIDER && !isFreeZenModel(value.slice(cut + 1))) return false;
  return !DEPRECATED_MODEL_RE.test(value);
}

/**
 * Telefonda görünen ad. Kimliğin KENDİSİ kullanılıyor, bilerek: arayüz
 * "saglayici/model" kimliğini ikiye ayırıp üst satıra modeli, alt satıra
 * sağlayıcıyı yazıyor (ui3ModelAdiAyir). Dostane bir ad etikete yazılırsa o
 * ayrım kayboluyor ve sağlayıcı satırı hiç çizilmiyor — v2'de tam bu oldu.
 * Dostane ad varsa `detail` alanında taşınır, etikette değil.
 */
export function opencodeModelLabel(id) {
  return id === 'runpod/runpod' ? 'Qwen3.8-27B · RunPod' : id;
}
