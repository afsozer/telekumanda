# AgentBridge Ürün Vizyonu ve Uygulama Roadmap'i

## Kuzey yıldızı

AgentBridge, masaüstündeki yerel ve bulut ajanlarını telefondan güvenli biçimde
işleten kişisel bir AI operasyon konsoludur. Ana başarı ölçütü backend sayısı değil;
kullanıcının bilgisayardan uzaktayken çalışan işleri, bekleyen onayları, hataları ve
teslimatları tek yerden görebilmesidir.

## Değişmez kontrol ilkesi

- Provider ve model seçimi her zaman kullanıcıya aittir.
- Uygulama provider'ı otomatik seçmez, değiştirmez veya görevi başka bir provider'a
  kendiliğinden devretmez.
- Her başlatma, yeniden çalıştırma, devretme ve yetki yükseltme açık kullanıcı
  eylemi gerektirir.
- Capability bilgisi yalnız arayüzü açıklamak ve desteklenmeyen kontrolleri gizlemek
  için kullanılır; yönlendirme motoru olarak kullanılmaz.

## Faz 1 — Operasyon merkezi ve dayanıklılık

- [x] Sağlayıcıdan bağımsız operasyon durum modeli (`running`, `waiting`, `failed`).
- [x] Başlama, onay bekleme, tamamlanma ve hata geçişlerini kalıcı olay günlüğüne yaz.
- [x] Ana ekrana Operasyon Kutusu ve görülmemiş olay sayacı ekle.
- [x] Bir operasyona yalnız kullanıcı dokunuşuyla geçiş yap.
- [x] Arka plan servisinde provider-bağımsız tamamlanma/hata bildirimleri üret.
- [x] Prompt isteklerine payload'a bağlı kalıcı idempotency anahtarı ekle.
- [x] Bridge restart sonrası yarım kalan gönderimleri transcript kanıtıyla deterministik uzlaştır; belirsiz işi otomatik tekrarlama.
- [x] REST/WS protokol sürümünü health cevabında yayınla ve Android uyumluluk kontrolü ekle.

## Faz 2 — Proje ve teslimat merkezi

- [x] Cowork dışındaki oturumlar için de opsiyonel proje zarfı tanımla.
- [x] Ana ekranda proje → oturumlar → teslimatlar hiyerarşisi sun.
- [x] Dosya değişiklikleri, komutlar, plan ve çıktıları transcript'ten bağımsız özetle.
- [x] Projeyle ilişkilendirilen, yalnız açık ikinci onayla Codex/OpenCode global MCP konfigürasyonuna uygulanan MCP profili ve izin politikası ekle.
- [x] Teslimatların yeni/değişti durumunu kalıcı olarak izle.

## Faz 3 — Güvenlik ve denetlenebilirlik

- [x] Güvenli, Standart ve Tam erişim profillerini açık kullanıcı eylemiyle uygula.
- [x] Provider izinleri ve proje içi yol kökleri düzenlenebilen Özel politika profili oluştur.
- [x] Proje bazlı okunabilir/yazılabilir yol sınırlarını politika metadatasında tut ve arayüzde göster.
- [x] Proje adı ve izin profili kararları için kalıcı denetim günlüğü oluştur.
- [x] Ajan komutları ve dosya işlemlerini tekrar yazmayı önleyen denetim günlüğüne bağla.
- [x] Tam erişim yetki yükseltmesi için etkisi açık ikinci onay ekranı ekle.
- [x] Bearer token modelini kısa ömürlü tek kullanımlık kod, cihaz başına anahtar ve eski anahtarı anında iptal eden yenilemeyle güçlendir.

## Faz 4 — Backend platformu

- [x] Sürümlü `BackendAdapter` sözleşmesi tanımla (`bridge/backend-contract.mjs`, `CONTRACT_VERSION`).
- [x] Model, oturum, prompt, stop, approval ve capability alanlarını şemalaştır (`CAPABILITY_SCHEMA`, `REQUIRED_METHODS`, `POLL_APPROVAL_BACKENDS`).
- [x] Android kontrollerini capability kataloğundan üret (`/backends` → `BackendCatalogInfo`; gömülü enum yalnız çevrimdışı yedek).
- [x] Her adapter için ortak sözleşme conformance test paketi çalıştır (`bridge/test/backend-contract.test.mjs`, gerçek modüller).
- [~] Backend'e özel durumların ortak ViewModel/state içine sızmasını azalt: onay poll listesi kontrata taşındı (`findAnyPendingApproval` artık tek kaynaktan), yeni backend'lerin yetenek kapısı için Android enum düzenlemesi gerekmiyor. Kalan: RemoteViewModel'deki backend-özel alanların (codexApp*, claudeApp* vb.) capability-güdümlü ortak state'e indirgenmesi ayrı bir refaktör olarak ertelendi.

Stream ve health alanlarının şemalaştırılması akış sözleşmesini (WS event tipleri)
etkilediği için bilinçli olarak sonraki tura bırakıldı; capability kataloğu şu an
REST kontrolleri (onay, izin modu, plan, hesap, teslimat, bağlam) için tek kaynaktır.

## Ürün dışı bırakılan yaklaşım

Otomatik provider önerme, seçme veya görev yönlendirme bu roadmap'in parçası değildir.
Kontrolün kullanıcıda kalması ürünün bilinçli ve kalıcı tasarım kararıdır.
