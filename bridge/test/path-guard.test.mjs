import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { assertSafePathFields, unsafePathReason } from '../path-guard.mjs';

describe('path-guard', () => {
  it('UNC ve aygıt yollarını reddeder', () => {
    for (const p of ['\\\\10.0.0.5\\pay\\a', '//10.0.0.5/pay', '\\\\?\\C:\\x', '\\\\.\\PhysicalDrive0', '/\\\\sunucu\\pay', '%5C%5Csunucu%5Cpay', 'agfile:\\\\sunucu\\a']) {
      assert.ok(unsafePathReason(p), p);
    }
  });

  it('NTFS veri akışını reddeder', () => {
    for (const p of ['C:\\x\\a.txt:gizli', 'a.txt:gizli', 'C:/x/a.txt::$DATA']) assert.ok(unsafePathReason(p), p);
  });

  it('olağan yerel yolları geçirir', () => {
    for (const p of ['C:\\Users\\x\\a.txt', 'C:/Users/x', '/C:/Users/x/a.md', 'agfile:///C:/Users/x/a.md', 'notlar/not001.md', '', undefined]) {
      assert.equal(unsafePathReason(p), null, String(p));
    }
  });

  it('yalnız yol alanlarına bakar, dizi değerlerini de denetler', () => {
    assert.doesNotThrow(() => assertSafePathFields({ text: '\\\\sunucu\\pay', path: 'C:\\a' }));
    assert.throws(() => assertSafePathFields({ sources: ['C:\\a', '\\\\sunucu\\b'] }));
  });
});
