# AgentBridge Lite güncellemeleri

Lite yalnız Claude, Codex ve Antigravity sunar. Eski OpenCode sekmeleri Lite
başlangıcında istemcinin sekme kaydından çıkarılır; köprüdeki oturum silinmez.
Normal uygulamanın sağlayıcıları korunur.

Lite açılışta sessiz güncelleme kontrolü yapar. Başlık çubuğundaki güncelleme
simgesi güncelleme ekranını açar; yeni sürüm varsa simge vurgulanır. Ekrandan
kontrol, indirme ve Android kurulum ekranını açma işlemleri yapılabilir.
Güncelleme ekranında başlıktaki simge kapatma düğmesine dönüşür. Bu düğme,
ekranın geri oku ve sistem geri hareketi açık sohbete döner; güncelleme ekranı
üst üste gezinme kaydı biriktirmez.

## Ayrı kanal

- Manifest: `/update/lite/latest.json`
- APK: `/update/lite/app-latest.apk`
- Paket: `com.agent.bridge.lite`
- Sürüm kaynağı: `android/lite-version.properties`
- Yayın dosyaları: `bridge/update/lite/`

Normal kanalın `/update/latest.json`, `/update/app-latest.apk` adresleri,
`bridge/release.mjs` betiği ve `defaultConfig` sürümü korunur. Lite manifesti
paket kimliği ve Lite APK yolu eşleşmeden kabul edilmez. Kurulumdan önce APK
paket kimliği ve sürüm kodu da doğrulanır.

## Yeni Lite sürümü

Proje kökünden:

```powershell
node bridge/release-lite.mjs 1.1-lite "Sürüm notları"
```

Betik yalnız Lite sürüm kodunu artırır, `:app:assembleLite` çalıştırır, çıktı
metadatasındaki paket ve sürümü doğrular, APK ve SHA-256 içeren manifesti
Lite dizinine koyar. Derleme başarısız olursa sürüm dosyasını geri alır.

İlk geçişte, OTA kontrolü bulunmayan eski Lite yerine yeni Lite APK bir kez
elle kurulmalıdır. Sonraki sürümler uygulama içinden yüklenebilir. Yeni OTA
rotalarını içeren bridge kodu ilk kez kullanıldığında bridge'in sahibi
tarafından yeniden başlatılması gerekir; sonraki APK/manifest yayınları
dosyalardan dinamik okunduğu için yeniden başlatma gerektirmez.
