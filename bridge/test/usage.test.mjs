import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  __testReadJsonlTail,
  arrangeUsageGroups,
  fetchDeepSeekUsage,
  fetchNanoGptUsage,
  fetchOpenCodeGoUsage,
  fetchOpenRouterUsage,
} from '../usage.mjs';

describe('bounded usage transcript reader', () => {
  it('reads complete records from the tail without parsing the oversized prefix', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'usage-tail-'));
    const file = path.join(dir, 'usage.jsonl');
    try {
      fs.writeFileSync(file, [
        JSON.stringify({ kind: 'huge', output: 'x'.repeat(500_000) }),
        JSON.stringify({ kind: 'usage', value: 42 }),
        '',
      ].join('\n'));
      const seen = [];
      __testReadJsonlTail(file, value => seen.push(value), 16 * 1024);
      assert.deepEqual(seen, [{ kind: 'usage', value: 42 }]);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });
});

describe('OpenRouter credit usage', () => {
  it('returns a zero-balance card instead of hiding an unfunded account', async () => {
    const group = await fetchOpenRouterUsage({
      apiKey: 'test-key',
      fetchImpl: async () => ({
        ok: true,
        json: async () => ({ data: { total_credits: 0, total_usage: 0 } }),
      }),
    });

    assert.equal(group.name, 'OpenRouter');
    assert.equal(group.source, 'openrouter');
    assert.equal(group.buckets[0].value, '$0.00');
    assert.equal(group.buckets[0].metered, true);
    assert.equal(group.buckets[0].remainingFraction, 0);
    assert.equal(group.buckets[0].creditUsd, 0);
    assert.match(group.buckets[0].description, /Toplam \$0\.00/);
  });

  it('derives remaining credit and never exposes the API key', async () => {
    const group = await fetchOpenRouterUsage({
      apiKey: 'super-secret',
      fetchImpl: async (_url, options) => {
        assert.equal(options.headers.Authorization, 'Bearer super-secret');
        return {
          ok: true,
          json: async () => ({ data: { total_credits: 12.5, total_usage: 2.25 } }),
        };
      },
    });

    assert.equal(group.buckets[0].value, '$10.25');
    // 10 $ üstü: çubuk dolu kalır, istemci creditUsd'den "bol" rengini seçer.
    assert.equal(group.buckets[0].remainingFraction, 1);
    assert.equal(group.buckets[0].creditUsd, 10.25);
    assert.doesNotMatch(JSON.stringify(group), /super-secret/);
  });
});

describe('Nano-GPT balance usage', () => {
  it('reads the official USD balance and never exposes the API key', async () => {
    const seen = [];
    const group = await fetchNanoGptUsage({
      apiKey: 'nano-secret',
      // 02:30 TSİ = önceki UTC günü: uç UTC kutuyla sayıyor.
      now: new Date('2026-10-04T23:30:00Z'),
      fetchImpl: async (url, options) => {
        seen.push(url);
        if (url.startsWith('https://nano-gpt.com/api/v1/usage')) {
          assert.equal(options.headers.Authorization, 'Bearer nano-secret');
          return {
            ok: true,
            json: async () => ({ totals: { requests: 39, costUsd: 0.05, netCostUsd: 0.04690731 } }),
          };
        }
        assert.equal(url, 'https://nano-gpt.com/api/check-balance');
        assert.equal(options.method, 'POST');
        assert.equal(options.headers['x-api-key'], 'nano-secret');
        return {
          ok: true,
          json: async () => ({ usd_balance: '9.54827636', nano_balance: '0' }),
        };
      },
    });

    assert.ok(seen.includes('https://nano-gpt.com/api/v1/usage?group_by=model&from=2026-10-04&to=2026-10-04'));
    assert.equal(group.name, 'Nano-GPT');
    assert.equal(group.source, 'nanogpt');
    assert.equal(group.buckets[0].value, '$9.55');
    // 10 $ = dolu çubuk; 9.55 $ → %95.
    assert.equal(group.buckets[0].metered, true);
    assert.equal(group.buckets[0].remainingFraction, 0.9548);
    assert.equal(group.buckets[0].creditUsd, 9.55);
    // Bugün: indirimli net tutar, istek sayısı.
    assert.equal(group.buckets[0].todaySpendUsd, 0.0469);
    assert.equal(group.buckets[0].todayRequests, 39);
    assert.equal(group.buckets[0].description, 'Bugün $0.05 • 39 istek');
    assert.doesNotMatch(group.description, /kota değil/);
    assert.doesNotMatch(JSON.stringify(group), /nano-secret/);
  });

  it('keeps the balance when the usage endpoint fails, without inventing a zero', async () => {
    const group = await fetchNanoGptUsage({
      apiKey: 'k',
      fetchImpl: async url => (url.includes('/api/v1/usage')
        ? { ok: false, json: async () => ({}) }
        : { ok: true, json: async () => ({ usd_balance: '3.74' }) }),
    });
    assert.equal(group.buckets[0].value, '$3.74');
    assert.equal('todaySpendUsd' in group.buckets[0], false);
    assert.equal('todayRequests' in group.buckets[0], false);
    assert.equal(group.buckets[0].description, '');
  });

  it('keeps a valid zero balance visible and skips invalid responses', async () => {
    const zero = await fetchNanoGptUsage({
      apiKey: 'k',
      fetchImpl: async () => ({ ok: true, json: async () => ({ usd_balance: '0' }) }),
    });
    assert.equal(zero.buckets[0].value, '$0.00');
    assert.equal(zero.buckets[0].remainingFraction, 0);

    const invalid = await fetchNanoGptUsage({
      apiKey: 'k',
      fetchImpl: async () => ({ ok: true, json: async () => ({ usd_balance: 'nope' }) }),
    });
    assert.equal(invalid, null);
  });

  it('does not call the endpoint without a key', async () => {
    assert.equal(await fetchNanoGptUsage({
      apiKey: '',
      fetchImpl: async () => { throw new Error('çağrılmamalı'); },
    }), null);
  });
});

describe('usage card ordering', () => {
  it('puts Claude first, then Codex, then balance cards', () => {
    const named = name => ({ name, buckets: [] });
    const groups = arrangeUsageGroups({
      claude: named('Claude Code'),
      codex: named('Codex'),
      openRouter: named('OpenRouter'),
      nanoGpt: named('Nano-GPT'),
      deepSeek: named('DeepSeek'),
    });

    assert.deepEqual(groups.map(g => g.name), [
      'Claude Code', 'Codex', 'OpenRouter', 'Nano-GPT', 'DeepSeek',
    ]);
    assert.equal(groups[0].source, 'claude');
    assert.equal(groups[1].source, 'codex');
  });
});

describe('DeepSeek balance usage', () => {
  const respond = payload => async (_url, options) => ({
    ok: true,
    json: async () => {
      assert.equal(options.headers.Authorization, 'Bearer ds-secret');
      return payload;
    },
  });

  it('reads the USD balance and never exposes the API key', async () => {
    const group = await fetchDeepSeekUsage({
      apiKey: 'ds-secret',
      fetchImpl: respond({
        is_available: true,
        balance_infos: [{
          currency: 'USD', total_balance: '11.41',
          granted_balance: '0.00', topped_up_balance: '11.41',
        }],
      }),
    });

    assert.equal(group.name, 'DeepSeek');
    assert.equal(group.source, 'deepseek');
    assert.equal(group.buckets[0].value, '$11.41');
    assert.equal(group.buckets[0].metered, true);
    assert.equal(group.buckets[0].remainingFraction, 1);
    assert.equal(group.buckets[0].creditUsd, 11.41);
    assert.doesNotMatch(group.description, /kota değil/);
    // Hediye kredi 0 iken satırı kalabalık etmemeli.
    assert.equal(group.buckets[0].description, 'Yüklenen $11.41');
    assert.doesNotMatch(JSON.stringify(group), /ds-secret/);
  });

  it('prefers the USD row when the account holds several currencies', async () => {
    const group = await fetchDeepSeekUsage({
      apiKey: 'ds-secret',
      fetchImpl: respond({
        is_available: true,
        balance_infos: [
          { currency: 'CNY', total_balance: '80.00', granted_balance: '5.00', topped_up_balance: '75.00' },
          { currency: 'USD', total_balance: '3.50', granted_balance: '1.50', topped_up_balance: '2.00' },
        ],
      }),
    });

    assert.equal(group.buckets[0].value, '$3.50');
    assert.equal(group.buckets[0].remainingFraction, 0.35);
    assert.equal(group.buckets[0].description, 'Yüklenen $2.00 • Hediye $1.50');
  });

  it('marks an exhausted balance instead of hiding the card', async () => {
    const group = await fetchDeepSeekUsage({
      apiKey: 'ds-secret',
      fetchImpl: respond({
        is_available: false,
        balance_infos: [{
          currency: 'USD', total_balance: '0.00',
          granted_balance: '0.00', topped_up_balance: '0.00',
        }],
      }),
    });

    assert.equal(group.buckets[0].value, '$0.00');
    assert.equal(group.buckets[0].remainingFraction, 0);
    assert.equal(group.buckets[0].creditUsd, 0);
  });

  it('keeps a non-USD balance barless instead of scaling it by 10 dollars', async () => {
    const group = await fetchDeepSeekUsage({
      apiKey: 'ds-secret',
      fetchImpl: respond({
        is_available: true,
        balance_infos: [{ currency: 'CNY', total_balance: '80.00', granted_balance: '0', topped_up_balance: '80.00' }],
      }),
    });
    assert.equal(group.buckets[0].value, '80.00 CNY');
    assert.equal(group.buckets[0].metered, false);
    assert.equal(group.buckets[0].creditUsd, undefined);
  });

  it('returns null without a key so the card simply does not appear', async () => {
    assert.equal(await fetchDeepSeekUsage({ apiKey: '', fetchImpl: async () => { throw new Error('çağrılmamalı'); } }), null);
  });
});

describe('OpenCode Go subscription quota', () => {
  const respond = payload => async (url, options) => ({
    ok: true,
    json: async () => {
      assert.equal(url, 'https://opencode.ai/zen/go/v1/usage');
      assert.equal(options.headers.Authorization, 'Bearer oc-secret');
      return payload;
    },
  });

  it('üç pencereyi kalan yüzdeye çevirir ve anahtarı sızdırmaz', async () => {
    const group = await fetchOpenCodeGoUsage({
      apiKey: 'oc-secret',
      fetchImpl: respond({
        usage: {
          rolling: { status: 'ok', percent: 0, resetsAt: '2026-08-16T16:53:13.978Z' },
          weekly: { status: 'ok', percent: 40, resetsAt: '2026-08-17T00:00:00.000Z' },
          monthly: { status: 'ok', percent: 20, resetsAt: '2026-09-16T11:45:50.000Z' },
        },
      }),
    });

    assert.equal(group.name, 'OpenCode Go');
    assert.equal(group.source, 'opencode-go');
    assert.deepEqual(group.buckets.map(b => b.id), ['opencode-go-5h', 'opencode-go-week', 'opencode-go-month']);
    assert.equal(group.buckets[0].value, '%100 kaldı');
    assert.equal(group.buckets[1].value, '%60 kaldı');
    assert.equal(group.buckets[1].remainingFraction, 0.6);
    assert.equal(group.buckets[2].resetTime, '2026-09-16T11:45:50.000Z');
    // Durum normalken açıklama satırı kalabalık etmemeli.
    assert.equal(group.buckets[0].description, '');
    // Kota kartı: çubuk çizilmeli. metered:false olsaydı istemci hiç çizmezdi.
    assert.ok(group.buckets.every(b => b.metered === true));
    assert.doesNotMatch(JSON.stringify(group), /oc-secret/);
  });

  it('beklenmedik durum kodunu yutmaz', async () => {
    const group = await fetchOpenCodeGoUsage({
      apiKey: 'oc-secret',
      fetchImpl: respond({ usage: { weekly: { status: 'exceeded', percent: 100, resetsAt: '2026-08-17T00:00:00.000Z' } } }),
    });

    assert.equal(group.buckets.length, 1);
    assert.equal(group.buckets[0].value, '%0 kaldı');
    assert.equal(group.buckets[0].remainingFraction, 0);
    assert.equal(group.buckets[0].description, 'Durum: exceeded');
  });

  it('yüzdesi olmayan pencereyi "%100 kaldı" diye uydurmaz', async () => {
    const group = await fetchOpenCodeGoUsage({
      apiKey: 'oc-secret',
      fetchImpl: respond({
        usage: {
          rolling: { status: 'ok', resetsAt: '2026-08-16T16:53:13.978Z' },
          weekly: { status: 'ok', percent: 10, resetsAt: '2026-08-17T00:00:00.000Z' },
        },
      }),
    });

    assert.deepEqual(group.buckets.map(b => b.id), ['opencode-go-week']);
  });

  it('tek bir pencere bile gelmezse kart hiç çıkmaz', async () => {
    assert.equal(await fetchOpenCodeGoUsage({ apiKey: 'oc-secret', fetchImpl: respond({ usage: {} }) }), null);
  });

  it('anahtar yoksa uca hiç gitmez', async () => {
    assert.equal(await fetchOpenCodeGoUsage({ apiKey: '', fetchImpl: async () => { throw new Error('çağrılmamalı'); } }), null);
  });
});
