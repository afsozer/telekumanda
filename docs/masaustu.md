# Masaüstü kontrolü (Windows ekranını sürme)

`scripts/masaustu.ps1` — Windows oturumunu görsel olarak sürer: ekran görüntüsü
al, pencere yakala, fareyle tıkla, klavyeyle yaz. CLI'ı olmayan GUI
uygulamaları için. **Her ajan çağırabilir** (claude-app, codex-app,
opencode-app, agy); hepsi bu makinede kabuk erişimiyle çalışıyor.

Aşağıdaki tuzaklar canlı testte bulundu. Okumadan kullanma; her biri sessiz
başarısızlık üretiyor.

## Komutlar

```powershell
$s = "scripts\masaustu.ps1"

& $s screenshot -Out ekran.png                  # tüm ekran
& $s window -Title "Brave" -Out b.png           # tek pencere; ARKADA olsa da yakalar
& $s windows                                    # görünür pencereler + konum/boyut
& $s click  -Title "Brave" -X 960 -Y 540        # -Right sağ tık, -Double çift tık
& $s scroll -Title "Brave" -X 1100 -Y 500 -Amount -6   # eksi = aşağı
& $s type   -Title "Not Defteri" -Text "merhaba{ENTER}"
& $s type   -Title "Brave" -Literal -Text "https://x.com/a?q=b%20c"
& $s key    -Title "Brave" -Keys "^l"           # ^=Ctrl %=Alt +=Shift {ENTER}
& $s focus  -Title "Brave"
& $s crop   -In ekran.png -X 640 -Y 90 -W 1280 -H 680 -Out kirp.png
& $s monitoron                                  # kapalı monitörü yak (çalışmak için şart değil)
```

Çalışma döngüsü: **gör → bul → yap → doğrula.** Ekran görüntüsü al, hedefin
koordinatını belirle, eylemi yap, tekrar görüntü alıp sonucu gözünle doğrula.
"Yaptım" deme, gör.

## Tuzaklar

**1. `-Title` vermezsen tuşlar yanlış pencereye gider.** `type`/`key`/`click`
kendi içinde odaklanır (`AttachThreadInput` ile Windows'un odak-çalma
korumasını aşarak). `-Title`'sız çağırırsan araya giren herhangi bir pencere
odağı çalar ve yazı oraya düşer — sessizce, hata vermeden.

**2. Özel karakterli metinde `-Literal` şart.** SendKeys'te `% ^ + ~ ( ) { } [ ]`
komut anlamına gelir. URL'deki `%20` yüzünden **tüm çağrı reddedilir**. URL,
parola, formül yazdırırken `-Literal` kullan.

**3. Kaydırmada `scroll` kullan, `{PGDN}` değil.** PageDown pencere yerleşimini
bozuyor (büyütülmüş pencere küçüldü). Tekerlek yerleşime dokunmaz ve imlecin
altındaki panele gider.

**4. Açılır kutular ayrı penceredir.** Chromium'un izin balonu tarayıcının
içinde görünür ama ayrı üst-düzey penceredir; `-Title "Brave"` ile tıklamak
yalnız hover yapar. Önce `{ESC}` dene, olmazsa `windows` ile balonun kendi
başlığını bulup onu hedefle.

**5. Ekran görüntüsünde küçük yazı okunmuyorsa `crop` ile büyüt**, tam ekranı
zorlama.

## Koordinatlar

- `click` **mutlak ekran** koordinatı alır. Buton yerini tam ekran
  `screenshot`tan oku, pencere yakalamadan değil — büyütülmüş pencere `-8,-8`
  gibi negatif konumdan başlayabilir.
- Tek monitörde görüntü pikseli = ekran koordinatı.

## Sert sınırlar

- **Kilitliyken çalışmaz.** Kilit ekranı (Winlogon secure desktop) normal
  kullanıcı sürecine kapalı: yakalama "geçersiz işleyici" verir, sentetik
  girdi kabul edilmez. `screenshot` bu durumda `KILITLI` döner (exit 3).
  Chrome Remote Desktop/Moonlight aşabiliyor çünkü SYSTEM servisi + sanal
  sürücü olarak çalışıyorlar.
- **UAC ve yükseltilmiş pencereler** sentetik girdiyi reddeder. Yönetici
  gerektiren işi bu yolla yaptıramazsın.
- **Sistem uykuya girerse** süreç de donar. Fişte uyku "Asla" ayarlı.

## Performans

Yakalama ~265 ms, tıklama <5 ms. Asıl maliyet PowerShell süreci doğurmak
(~0.5 sn JIT), o yüzden **tek çağrı = tek eylem** tut; döngüde onlarca kez
çağırma, gerekiyorsa tek betikte topla.

## Sohbette görsel paylaşımı (yalnız claude-app)

Android uygulaması, mesajda kendi satırında `Ek dosyalar:` gördüğünde altındaki
`- ad: mutlak-yol` satırlarını küçük resim olarak çizer; dokununca tam ekran
açar, "PC'de sakla" tuşu köprünün çalıştığı kullanıcının
`Pictures\AgentBridge` klasörüne kaydeder (klasör yoksa oluşturulur).

Paylaşılan görselleri `%TEMP%\agtest-paylas\` altında tut; iş bitince
**klasörün içeriğini** temizle, klasörün kendisini silme:

```powershell
Remove-Item "$env:TEMP\agtest-paylas\*" -Force -ErrorAction SilentlyContinue
```

Saklama kararı kullanıcınındır — kalıcı isterse kaydet tuşuna basar.
