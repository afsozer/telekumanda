import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { mcpInventoryFromPrompt, skillInventoryFromCommands } from '../omp-inventory.mjs';

describe('OMP native envanter şekillendirme', () => {
  it('bro dahil skill komutlarını listeler', () => {
    const result = skillInventoryFromCommands({ commands: [
      { name: 'model', source: 'builtin' },
      { name: 'skill:bro', source: 'skill', description: 'Basitleştir' },
      { name: 'skill:uyap', source: 'skill', description: 'UYAP' },
    ] });
    assert.deepEqual(result.skills, ['bro', 'uyap']);
    assert.equal(result.skillDetails[0].description, 'Basitleştir');
  });

  it('uzun MCP adını önce eşleyip araçları doğru sunucuya ayırır', () => {
    const prompt = [
      '- xd://mcp__emsal_search_decisions',
      '- xd://mcp__emsal_mcp_local_search_decisions',
      '- xd://mcp__emsal_mcp_local_prepare_petition',
      '- xd://mcp__node_repl_js',
    ].join('\n');
    const servers = mcpInventoryFromPrompt(prompt, {
      candidates: [
        { name: 'emsal', source: 'opencode' },
        { name: 'emsal_mcp_local', source: 'codex' },
        { name: 'node_repl', source: 'codex' },
      ],
      disabled: new Set(['emsal_mcp']),
    });
    assert.equal(servers.find(s => s.name === 'emsal_mcp_local').toolCount, 2);
    assert.equal(servers.find(s => s.name === 'emsal').toolCount, 1);
    assert.equal(servers.find(s => s.name === 'node_repl').toolCount, 1);
    assert.equal(servers.find(s => s.name === 'emsal_mcp').enabled, false);
  });
});

