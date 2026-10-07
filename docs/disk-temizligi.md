# Disk Temizliği

> Tarih: 2026-08-19 · Durum: kurulu, köprü yeniden başlatılınca ilk süpürme koşar
> Kapsam: köprünün diskte biriktirdiği her şeyin yaşa göre budanması.

Köprü üç ayrı yerde sessizce birikiyordu ve üçü de kullanıcının baktığı bir
yerde değildi. Bu belge neyin, ne kadar sonra, hangi muafiyetlerle silindiğini
ve gerekçelerini toplar.

## Özet

| ne | süre | config | mekanizma |
|---|---|---|---|
| Paylaşılan içeriğin kopyası (`~/.agentbridge/share-notes`) | 3 gün | `noteExtract.retentionDays` | klasör yaşı |
| Çıkarımın agy artığı (`~/.gemini/agentbridge-screenshot-notes`) | 3 gün | `noteExtract.retentionDays` | klasör yaşı |
| agy CLI oturumları (`~/.gemini/antigravity-cli`) | 7 gün | `agy.sessionRetentionDays` | klasör yaşı |
| claude / codex / opencode oturumları | 7 gün | `sessionPrune.retentionDays` | oturum listesi + backend'in kendi silme yolu |

Her biri `0` ile kapatılır. Budama açılıştan 60 sn sonra bir kez, sonra 6
saatte bir koşar (`sessionPrune.intervalHours`).

**Aralık saklama süresi değildir.** Neyin silineceğini `retentionDays`
belirliyor; aralık yalnız "uygun hâle geldi" ile "fiilen silindi" arasındaki
gecikme. Aralık büyüdükçe saklama süresi fiilen uzar — haftada bir koşarsa 7
günlük kural 7–14 gün gibi davranır. Varsayılanın sık (6 saat) olmasının
sebebi bu: kural ne diyorsa o uygulansın.

Açılışta hemen değil 60 sn sonra: claude tarafında tüm transcript'lerin başı
okunuyor ve telefonun ilk isteği o taramanın arkasında beklerdi.

## Neden iki farklı mekanizma

**Klasör yaşı** (`disk-prune.mjs`) basit: `mtime` eskiyse gir, sil. agy'nin
artığı için doğru olan bu, çünkü orada birikenin hepsi oturum değil — `brain/`
ve `log/` de var — ve CLI/IDE ayrımı dosya sistemi seviyesinde bir gerçek,
oturum kaydında değil.

**Oturum listesi** (`session-prune.mjs`) claude/codex/opencode için gerekli,
çünkü oradaki "bir oturum" ≠ "bir dosya":

- claude: bir sohbet N dosya (CLI her resume'da geçmişin tamamını yeni uuid'li
  bir dosyaya kopyalıyor)
- codex: rollout dosyası + `state_5.sqlite` metadata'sı
- opencode: `opencode.db` içinde session + message + part + event satırları

Neyin silineceğini yalnız ilgili modül biliyor, o yüzden silme her backend'in
kendi `deleteDiskSession`'ına devrediliyor. `session-prune.mjs` yalnız **kimin**
silineceğini seçiyor.

## İnaktiflik ölçütü

Kullanıcının tanımı: *"gelen son prompt veya son ajan mesajı inaktiflik
süresinin başladığı andır."* Üç backend'de de liste kaydındaki `mtime` bunu
gösteriyor:

- **claude-app** — transcript içindeki son mesajın zamanı (`lastTs`). Ham dosya
  `mtime`'ı değil: CLI eski transcript'lere zaman damgasız bakım satırları
  ekleyip dosya mtime'ını ilerletiyor.
- **codex-app** — rollout dosyasının son yazılma zamanı.
- **opencode-app** — `opencode.db`'deki `time_updated`.

## Muafiyetler

**Cowork / çalışma alanı oturumları — mutlak muaf.** cwd cowork kökünün
altındaysa yaşı ne olursa olsun dokunulmaz. Bunlar dosya teslimatı olan iş
oturumları, sohbet geçmişi değil; bir dava dosyası aylarca sessiz kalıp sonra
devam edebilir. Testte üç ayrı tuzak sabitlendi:

- Kökün kendisi de muaf.
- opencode `directory`'yi `/` ile veriyor, Windows yolları `\` ile — ayırıcı
  normalize edilmezse muafiyet **sessizce kaçar** ve çalışma alanı oturumu
  silinir.
- `CoworkSpaces-yedek` gibi adı geçen ama altında olmayan klasör muaf DEĞİL;
  düz string prefix karşılaştırması burada yanılırdı, `path.relative` yanılmaz.

**Pinlenmiş oturumlar muaf.** Kullanıcı zaten "bunu sakla" demiş.

**Yaşı bilinmeyen ama içi dolu oturum saklanır.** `mtime` yoksa/sayı değilse
budanmaz. Tersi (0 = çok eski) zamanı okunamayan her oturumu ilk turda
uçururdu. **Tek istisna:** yaşı bilinmeyen **ve** `turns === 0` olan kayıt
budanır — kaybedilecek bir şey yok, oysa saklamak sonsuza dek saklamak demek,
çünkü bu kayıtların hiçbir zaman damgası olmadığı için yaşları da hiç gelmez.
`turns` alanını hiç bildirmeyen kayıt bu istisnaya girmez (`=== 0` bilerek
katı); eksik alanı "boş" saymak, tur sayısı bildirmeyen bir backend'in bütün
geçmişini sessizce silerdi.

## claude tarafındaki çatal tuzağı

`listDiskSessions` budama için **yanlış kaynak**, iki sebeple:

1. Yalnız en yeni 120 dosyayı tarar. Çekmece için doğru, budama için değil —
   budanacak olanlar tam da o dilimin dışında kalanlar.
2. Aynı sohbetin çatal kopyaları ayrı dosyalar. Dosya dosya bakılırsa **canlı**
   bir sohbetin eski kopyaları "bir haftadır sessiz" görünür ve silinir.

Bu yüzden `claude-app.listAllDiskSessionsForPrune()` eklendi: tüm havuzu tarar,
çatalları `rootKey` ile gruplar, ölçüt grubun **en yeni** kopyasıdır. Grup
tazeyse hiçbir üyesi silinmez; grup eskiyse tüm dosyaları birlikte gider.

## İlk süpürmenin ölçülen kapsamı (19.08.2026, kuru çalıştırma)

| backend | kayıt | silinecek | cowork muaf | pinli | taze | boş kabuk |
|---|---|---|---|---|---|---|
| claude-app | 142 grup | 99 (109 dosya) | 12 | 8 | 23 | 0 |
| codex-app | 141 | 121 | 7 | 0 | 13 | 31 |
| opencode-app | 57 | 33 | 4 | 0 | 20 | 0 |

Ondan önce elle yapılan tek seferlik temizlik: 94 MB ekran görüntüsü + 97 MB
çıkarım artığı + 275 MB agy CLI oturumu = 466 MB.

## codex'in zamansız kabukları

İlk ölçümde codex'te **32 oturumun hiçbir zaman damgası yoktu** ve hepsinin tur
sayısı 0'dı: diskte rollout'u olmayan, bellekten geri yüklenmiş boş kabuklar,
cwd'lerinin çoğu birim testlerin geçici klasörleri (`cowork-project-root-*`).
Sebep: `_lastActivity` yalnız `prompt()` yolunda doluyordu, yani hiç
konuşulmamış bir kabuğun hiçbir zamanı olmuyordu.

İki taraflı çözüldü:

1. **Geçmiş** — yaşı bilinmeyen + boş kayıtlar budanıyor (yukarıdaki istisna).
   31'i silinecek; 32.'si cowork çalışma alanında olduğu için muaf kaldı,
   muafiyet boş kabuk kuralını yeniyor.
2. **Gelecek** — codex oturumlarına `createdAt` eklendi (oluşturma anı),
   `persistSessions`/restore ile taşınıyor ve `mtime` zincirinin son halkası
   oldu. Artık her oturumun en kötü ihtimalle bir yaşı var; yeni açılmış boş
   bir sekme "taze" sayılır, bir hafta sonra normal kuralla budanır.

Eski kayıtlarda alan yok (`createdAt: 0`) — bu bilinçli, onları boş kabuk
kuralına bırakıyor.

## İlk koşunun sonucu (20.08.2026 00:05)

| backend | önce | sonra | budanan |
|---|---|---|---|
| claude-app | 142 grup | 43 | 99 (109 dosya) |
| codex-app | 141 | 20 | 121 |
| opencode-app | 57 | 24 | 33 |

### Koşu sırasında çıkan hata: silinen codex oturumu diriliyordu

Budamadan sonra codex'te **63 oturum hâlâ budanacak görünüyordu**, oysa log
"121 budandı, hata yok" diyordu. Ölçüm: 63'ünün de rollout dosyası gerçekten
silinmiş, 63'ü de arşivde — yani veri gitmişti, geri gelen şey **kayıttı**.

Kök sebep: `codex-app.deleteDiskSession` oturumu `sessions` haritasından
düşürüyor ama **`persistSessions()` çağırmıyordu**. Kalıcılık dosyası eski
hâlinde kalıyor, restart'ta `__restoreSessionsFromData` silinmiş oturumu boş bir
kabuk olarak geri diriltiyordu. Bu yalnız budamayı değil, **telefondan elle
silmeyi de** etkiliyordu; arşiv bayrağı çekmecede gizlediği için görünmüyordu.

İki yerden kapatıldı:

1. `deleteDiskSession` silmeden sonra `persistSessions()` çağırıyor.
2. `__restoreSessionsFromData` arşivde adı geçen kaydı geri yüklemiyor —
   mezar taşı kontrolü. (Bu, dosyadaki mükerrer-kabuk toleransıyla
   karıştırılmamalı; o tolerans bilinçli tasarım ve yerinde duruyor.)

İkincisi eski kalıcılık dosyalarını da iyileştiriyor: dirilme durunca ilk
`persistSessions()` yazımında dosya kendiliğinden temizleniyor. Ölçüldü —
codex kaydı 83'ten 20'ye indi, bekleyen 0.

## Kapsam dışı

- **omp** — kullanıcının isteği üç backend'di (claude, codex, opencode).
- **Masaüstü Antigravity IDE home'u** (`~/.gemini/antigravity`) — bu köprüye ait
  değil; budamak kullanıcının editöründeki geçmişi silmek olurdu. Kodda bu
  gerekçe yorumla yazılı ki sonradan "simetri olsun" diye eklenmesin.
