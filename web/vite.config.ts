/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Köprünün geliştirme adresi. Üretimde arayüzü köprünün kendisi servis eder,
// bu proxy yalnızca `npm run dev` içindir.
const BRIDGE = process.env.BRIDGE_URL ?? 'http://127.0.0.1:8787'

// Arayüz /ui/ altında yaşar. Sebep: köprüde ~56 uç nokta kökte duruyor
// (/health, /claude-app/*, /dirs, /download ...) ve router yalnız tam yol
// eşleştiriyor. Ayrı bir önek olmadan yeni bir uç nokta eklendiği gün
// arayüzle çakışabilirdi. /ui ile ayrım kalıcı ve belirsizliksiz.
export default defineConfig({
  base: '/ui/',
  plugins: [react()],
  server: {
    proxy: {
      // /ui ve /ui/* dışındaki HER ŞEY köprüye gider. Vite kendi modül
      // adreslerini de base ile önekliyor (/ui/@vite/client, /ui/src/...),
      // bu yüzden tek kural yetiyor.
      '^/(?!ui(/|$))': {
        target: BRIDGE,
        changeOrigin: true,
        // Akış uçları (/claude-app/stream ...) aynı proxy üzerinden geçsin.
        ws: true,
      },
    },
  },
  test: {
    // ÖNEMLİ: varsayılan 'forks' havuzu bu makinede çalışmıyor — worker'lar
    // 60 sn'de yanıt vermeden zaman aşımına düşüyor (Node 25 + Windows).
    // 'threads' ile sorunsuz. Değiştirme.
    pool: 'threads',
    // Varsayılan node: saf mantık testleri DOM kurulum maliyetini ödemesin.
    // Bileşen testleri dosyanın başına şunu koyar:
    //   // @vitest-environment happy-dom
    // (jsdom değil — ikisi de soğuk önbellekte ~40 sn açılıyor ama happy-dom
    // çok daha az dosya taşıyor.)
    environment: 'node',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
  },
})
