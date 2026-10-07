// v1 ve v2 model listeleri AYNI kuralla süzülüyor mu — tek kaynak bekçisi.
//
// Kullanıcı isteği (26.09.2026): "model listesini opencode ile birebir aynı
// yap". O gün canlı ölçüm v1'de 27, v2'de 32 model gösterdi; fark v2'nin
// Zen'in ÜCRETLİ modellerini süzmemesiydi. Aşağıdaki iki liste O ÖLÇÜMÜN
// kendisi (köprüden alındı) ve süzgeç ikisini aynı kümeye indirmeli.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  isAgentBridgeOpencodeModel,
  opencodeModelLabel,
  isFreeZenModel,
  setZenFreePrices,
  __testResetZenFreePrices,
} from '../opencode-model-filter.mjs';

// v2 sunucusunun HAM kataloğu (GET /api/model), süzgeçten önce.
const V2_HAM = [
  'deepseek/deepseek-flash',
  'deepseek/deepseek-v4-pro',
  'nanogpt/abliteration-ai/abliterated-model',
  'nanogpt/abliteration-ai/abliterated-model-large',
  'nanogpt/abliteration-ai/abliterated-model-large-v2',
  'nanogpt/google/gemma-4-26b-a4b-it-cybersecurity',
  'nanogpt/moonshotai/kimi-k3',
  'nanogpt/qwen/qwen3.8-27b-cybersecurity',
  'nanogpt/qwen/qwen3.8-27b-uncensored:thinking',
  'nanogpt/qwen3.8-27b:thinking',
  'nanogpt/qwen3.8-max:thinking',
  'nanogpt/xiaomi/mimo-v2.6-flash',
  'nanogpt/xiaomi/mimo-v2.6-pro',
  'nanogpt/z-ai/glm-5.3-flash',
  'nanogpt/z-ai/glm-5.3-flash-cybersecurity',
  'nanogpt/z-ai/glm-5.3-flash-uncensored',
  'opencode/big-pickle',
  'opencode/claude-opus-5-5',
  'opencode/deepseek-v4.1-flash',
  'opencode/gpt-6-luna',
  'opencode/gpt-6-sol',
  'opencode/grok-4.7',
  'opencode/ling-3.0-flash-fin-free',
  'opencode/longcat-2.5-preview-free',
  'opencode/mimo-v2.6-flash-free',
  'opencode/muse-spark-1.3-contributor-free',
  'opencode/nemotron-3-ultra-free',
  'opencode/nemotron-3.5-lightning-free',
  'opencode/qwen3.8-flash',
  'opencode/qwen3.8-max',
  'opencode/space-bunny-free',
  'runpod/runpod',
];

// v1 kataloğu (opencode models CLI + süzgeç) — köprüden ölçülen liste.
const V1_SUZULMUS = [
  'deepseek/deepseek-flash',
  'deepseek/deepseek-v4-pro',
  'evren/glm-5.3',
  'evren/qwen3.8-flash-next',
  'nanogpt/abliteration-ai/abliterated-model',
  'nanogpt/abliteration-ai/abliterated-model-large',
  'nanogpt/abliteration-ai/abliterated-model-large-v2',
  'nanogpt/google/gemma-4-26b-a4b-it-cybersecurity',
  'nanogpt/moonshotai/kimi-k3',
  'nanogpt/qwen/qwen3.8-27b-cybersecurity',
  'nanogpt/qwen/qwen3.8-27b-uncensored:thinking',
  'nanogpt/qwen3.8-27b:thinking',
  'nanogpt/qwen3.8-max:thinking',
  'nanogpt/xiaomi/mimo-v2.6-flash',
  'nanogpt/xiaomi/mimo-v2.6-pro',
  'nanogpt/z-ai/glm-5.3-flash',
  'nanogpt/z-ai/glm-5.3-flash-cybersecurity',
  'nanogpt/z-ai/glm-5.3-flash-uncensored',
  'opencode/big-pickle',
  'opencode/ling-3.0-flash-fin-free',
  'opencode/longcat-2.5-preview-free',
  'opencode/mimo-v2.6-flash-free',
  'opencode/muse-spark-1.3-contributor-free',
  'opencode/nemotron-3-ultra-free',
  'opencode/nemotron-3.5-lightning-free',
  'opencode/space-bunny-free',
  'runpod/runpod',
];

describe('opencode model süzgeci — v1/v2 tek kaynak', () => {
  it('v2 ham kataloğu süzülünce v1 listesine iner (evren farkı hariç)', () => {
    __testResetZenFreePrices();
    const v2Suzulmus = V2_HAM.filter(isAgentBridgeOpencodeModel).sort();
    // evren/* v2'nin İZOLE config'inde yoktu; sağlayıcı bloğu 26.09.2026'da
    // kopyalandı ama bu fixture ondan ÖNCE ölçüldü. Küme karşılaştırması o
    // yüzden evren'i dışarıda bırakıyor.
    const v1Beklenen = V1_SUZULMUS.filter(id => !id.startsWith('evren/')).sort();
    assert.deepEqual(v2Suzulmus, v1Beklenen);
  });

  it('süzgeç Zen ücretlileri eler, bedavaları ve diğer sağlayıcıları tutar', () => {
    __testResetZenFreePrices();
    assert.equal(isAgentBridgeOpencodeModel('opencode/gpt-6-sol'), false);
    assert.equal(isAgentBridgeOpencodeModel('opencode/claude-opus-5-5'), false);
    assert.equal(isAgentBridgeOpencodeModel('opencode/space-bunny-free'), true);
    // Rotasyondaki eksiz bedava: ad kuralı yakalamaz, istisna kümesi yakalar.
    assert.equal(isAgentBridgeOpencodeModel('opencode/big-pickle'), true);
    assert.equal(isAgentBridgeOpencodeModel('nanogpt/z-ai/glm-5.3-flash'), true);
    assert.equal(isAgentBridgeOpencodeModel('deepseek/deepseek-reasoner'), false);
  });

  it('canlı fiyat kaydı ad kuralını EZER', () => {
    __testResetZenFreePrices();
    assert.equal(isFreeZenModel('gizli-model'), false);
    setZenFreePrices(new Map([['gizli-model', true], ['sahte-free', false]]));
    assert.equal(isFreeZenModel('gizli-model'), true);
    // "-free" ekli ama fiyat kaydı ücretli diyorsa kayıt kazanır.
    assert.equal(isFreeZenModel('sahte-free'), false);
    __testResetZenFreePrices();
  });

  // Katalog doğrulaması 30.09.2026'da v1'den KOPARILDI: v1 backend'i söküldü,
  // kaynak artık v2'nin kendi `opencode models` CLI'si (cliCatalogIds). Bu test
  // "doğrulama tümden kalktı" regresyonunu yakalar: server.mjs v1'i ARAMAMALI,
  // adaptör de CLI yolunu KAYBETMEMELİ.
  it('v2 kataloğu kendi CLI listesiyle doğrulanıyor, v1 kancası kalmadı', async () => {
    const fs = await import('node:fs');
    const path = await import('node:path');
    const url = await import('node:url');
    const dir = path.dirname(url.fileURLToPath(import.meta.url));
    const server = fs.readFileSync(path.join(dir, '..', 'server.mjs'), 'utf-8');
    assert.doesNotMatch(server, /opencodeApp/, 'server.mjs artık v1 modülünü tanımamalı');
    const adapter = fs.readFileSync(path.join(dir, '..', 'opencode2-app.mjs'), 'utf-8');
    assert.match(adapter, /async function cliCatalogIds\(/);
    assert.match(adapter, /spawnAsync\(OC2_EXE, \['models'\]/);
    // peerIdSet kancasız durumda CLI'ye DÜŞMELİ; `peerCatalog ? ... : ...` üçlüsü
    // silinirse doğrulama sessizce kapanırdı (v2 açılışta yanlış katalog veriyor).
    assert.match(adapter, /peerCatalog \? await peerCatalog\(\) : await cliCatalogIds\(\)/);
  });

  it('etiket kimliğin KENDİSİ (arayüz sağlayıcıyı oradan ayırıyor)', () => {
    assert.equal(opencodeModelLabel('nanogpt/z-ai/glm-5.3-flash'), 'nanogpt/z-ai/glm-5.3-flash');
    assert.equal(opencodeModelLabel('runpod/runpod'), 'Qwen3.8-27B · RunPod');
  });
});
