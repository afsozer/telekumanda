# Bildirim kanalı

Köprü bildirimlerinin (not, hatırlatma, onay, görev olayları) **tek** kanalı tam
Android uygulamasının HTTP long-poll bağlantısıdır (`BridgeMonitorService`). ADB
üzerinden bildirim gönderme (`adb-push.mjs`, `PushReceiver`, `config.adbPush`)
22.09.2026'da tamamen kaldırıldı; kanal seçimi ayarı da yok. Tam sürümde servis
hep açıktır; Lite arka plan bildirimi paketlemez (mevcut davranış).

## Sözleşme

- `POST /notifications/device`: `deviceId` (kalıcı kurulum UUID'si), `model`
  (`Build.MODEL`; yoksa `X-Device-Model` başlığı). Servis her poll turunda kaydı
  tazeler. Kayıt cihazları `deviceId` ile tutar; 30 gün görülmeyen cihaz ve kuyruğu
  silinir.
- `GET /notifications/poll?...&deviceId=...`: eski alanlarla birlikte kayıtlı
  cihaza en fazla 50 bekleyen `pushEvents` döner. Kayıtsız cihazda alan yoktur.
- `POST /notifications/ack`: `deviceId`, `ids`. Yalnız bu cihazın mesajları onaylanır.
- Hedefleme: operasyon olayları turu başlatan cihaza gider. Prompt isteğindeki
  `X-Device-Model` (`prompt-origin.mjs`) kayıttaki modelle kayıtlı cihaz(lar)a
  çevrilir; eşleşme yoksa tüm kayıtlı cihazlara gider. Lite'tan başlayan tur
  (`__agentbridge_lite_no_push__`) bildirim üretmez. Hatırlatmalar tüm kayıtlı
  cihazlara gider.
- Operasyon olaylarından push'lanan `kind` değerleri: `started`, `attention`,
  `completed`, `failed` (`server.mjs` `OPERATION_PUSH_KINDS`). `started` kapsülü
  (Android 16 Live Updates, `docs/kapsul-live-updates-plani.md`) açar, `waiting →
  running` geçişinde de üretilir; **sessizdir**, yalnız kapsülü günceller.
- Operasyon push gövdesi: `deliveryId`, `kind`, `backend`, `backendLabel`,
  `sessionId`, `summary`, `title`, `startedAt` (ISO 8601 metin).
- Kuyruk `bridge/data/notification-delivery.json` dosyasına atomik yazılır; köprü
  yeniden başlasa veya bağlantı kopsa bekleyenler korunur. Eski (ADB seri
  numarasına bağlı) dosya yüklenirken bilinen `deviceId`'ye taşınır, gerisi atılır.
- Uygulama bildirimi Android'e başarıyla verdikten sonra teslim onayı yollar
  (okundu anlamına gelmez). Bildirim izni kapalıysa onay gönderilmez. Yerel makbuz
  aynı bildirimin yeniden ses çıkarmasını önler.
- Silinen veya zamanı değiştirilen hatırlatmaların kuyruk kaydı gösterilmez.
- Hatırlatma, kuyrukta beklerken deneme sayacı artmaz; `sent` teslim onayından
  sonra yazılır. Hiç kayıtlı cihaz yoksa gönderim başarısız sayılır.
- Ekran kapalıyken de long-poll sürer; Doze teslimi geciktirebilir, kuyruk kalır.

## Devreye alma ve sınırlar

Yeni APK ve yeni köprü birlikte gerekir. Yeni APK eski köprüde `/notifications/device`
için 404 alırsa eski görev/onay poller'ı çalışır. Eski APK'lar yeni köprüde
kaydolmazsa bildirim alamaz (ADB yolu yok). Köprü dışındaki betiklerin doğrudan
`adb am broadcast` ile gönderdiği uyarılar artık bir alıcı bulmaz.

## Doğrulama

`node --test bridge/test/notification-delivery.test.mjs bridge/test/notification-feed.test.mjs bridge/test/reminders.test.mjs bridge/test/prompt-origin.test.mjs`

Android: `:app:testDebugUnitTest` için `PushEventTest`, `NotificationCursorTest`,
`NotificationFocusTest`; `:shared:test` için `SettingsModelsTest`; ardından
`:app:assembleDebug`.

Canlı cihaz kabulü: not hatırlatması, ekran kapalı teslim, bağlantı kesilip geri
gelmesi, tabletten başlatılan turun yalnız tablete bildirilmesi, bildirime
dokununca doğru notun açılması.

### 9 Eylül 2026 doğrulama kaydı

- Köprüde ilgili 50 test başarılı.
- Android'de `PushEventTest` (2), `NotificationCursorTest` (4),
  `NotificationFocusTest` (7): 13 test başarılı.
- `:app:assembleDebug` başarılı; paket `com.agent.bridge`, sürüm 11.71 / 393.
- APK: 68.297.759 bayt; SHA-256
  `eee0d6c56929e018f245ab706bd7e8143f4a5a64c13e751b278d5905b543cdef`.
- APK imzası doğrulandı ve önceki güncellemenin sertifikasıyla eşleşti.
- `bridge/update` güncelleme kanalına kondu. Çalışan köprüden
  `/update/latest.json` ve `/update/app-latest.apk` okunarak sürüm ve indirilen
  APK'nın SHA-256 eşitliği doğrulandı.
- Önceki güncelleme `tmp/notification-update-11.70-backup` altında korundu.
- Canlı köprü ajan tarafından yeniden başlatılmadı; telefon kurulumu ve ekran
  kapalıyken bildirim teslimi henüz cihazda doğrulanmadı.
