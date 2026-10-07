// Telekumanda — versiyonlu Backend Adapter sözleşmesi ve yetenek (capability) kataloğu.
//
// Faz 4 (Backend platformu): her adapter-backed backend modülü ortak bir yüzeyi
// (listSessions/newSession/prompt/getConversation/stop) uygular ve registerBackend
// ile aynı REST kontratına bağlanır. Yetenekler artık BURADA tek kaynak olarak
// bildirilir; Android bunları /backends ucundan okuyup kontrolleri üretir ve kendi
// gömülü kopyasını yalnız çevrimdışı yedek olarak tutar.
//
// DEĞİŞMEZ KONTROL İLKESİ (docs/vision-roadmap.md): capability bilgisi yalnız
// arayüzü açıklamak ve desteklenmeyen kontrolleri gizlemek içindir; provider seçme
// veya görev yönlendirme motoru DEĞİLDİR.

export const CONTRACT_VERSION = 1;

// Adapter-backed bir backend modülünün MUTLAKA export etmesi gereken yüzey.
// registerBackend bu fonksiyonların hepsini çağırır; biri eksikse kontrat kırılır.
export const REQUIRED_METHODS = ['listSessions', 'newSession', 'prompt', 'getConversation', 'stop'];

// Poll tabanlı onay sistemine katılan backend'ler. server.mjs bu listeyi TEK KAYNAK
// olarak kullanır; her biri getPendingApproval export etmek zorundadır (conformance
// testiyle doğrulanır).
export const POLL_APPROVAL_BACKENDS = ['claude-app', 'codex-app', 'opencode2-app', 'omp'];

// Capability şeması: alan → varsayılan. Android BackendCapabilities ile birebir.
export const CAPABILITY_SCHEMA = {
  approvals: false,
  // KABA (eski) alan: "tur sürerken kullanıcı girdisi kabul edilir". Tek başına
  // arayüzü çizmeye YETMİYOR, çünkü tur içi gönderimin iki ayrı ucu ve iki ayrı
  // anlamı var; aşağıdaki iki alan gerçeği taşır ve kontrolleri onlar üretir.
  userInput: false,
  userInputSteer: false,  // POST /<b>/steer — süren tura enjeksiyon (turu yönlendirir)
  userInputQueue: false,  // POST /<b>/follow-up — tur bitince işlensin diye sıraya bırakır
  permissionModes: false,
  context: true,
  plan: false,
  outputs: false,
  // Aşağıdaki ikisi yalnızca bazı backend'lerde `extras` olarak kayıtlı
  // (registerBackend'in ortak yüzeyinde yok) ve hiçbir yetenek alanı onları
  // karşılamıyordu; arayüz desteklenmeyen kontrolü gizleyebilsin diye eklendi.
  efforts: false,    // GET /<b>/efforts + POST /<b>/effort
  interrupt: false,  // POST /<b>/interrupt (stop her backend'de var, bu değil)
  runpod: false,     // GET/POST /<b>/runpod/* (RunPod RunPod yaşam döngüsü)
  agents: false,     // GET /<b>/agents + POST /<b>/agent (opencode ajan seçimi)
  // GET /<b>/diff?session=<id> — oturumun dosya değişiklikleri ("Değişiklikler"
  // görünümü). opencode-app'te kaynak `opencode serve`in tur başına diff'i,
  // codex-app'te akan `item/completed` bildirimlerinden biriken fileChange
  // item'ları (ölçüm notları iki modülün sessionDiff başlıklarında). ŞEMA İKİSİNDE
  // DE AYNI — telefonda tek bir gövde çiziyor.
  sessionDiff: false,
  // GET /<b>/checkpoints + POST /<b>/revert + POST /<b>/unrevert — mesaj
  // seviyesinde geri sarma. `rewind` HER backend'de var (sondan sayımlı, kaba);
  // bu alan AYRI bir şeyi söylüyor: kullanıcı mesajlarının kimlikli listesi
  // sunuluyor, seçilen noktaya dönülüyor ve dönüş GERİ ALINABİLİYOR (unrevert).
  // Bugün yalnız opencode-app: kaynağı serve'ün revert/unrevert çifti.
  sessionRevert: false,
  // Snapshot'taki `subagents` alanı + GET /<b>/subagent-conversation — opencode'un
  // `task` aracının doğurduğu alt-oturumların kart yığını ve salt-okunur
  // transkripti. Bugün yalnız opencode-app: kaynağı serve'ün çocuk oturumları.
  subagents: false,
  // POST /<b>/share + POST /<b>/unshare — oturumu herkese açık bir linkte
  // yayınlama. Bugün yalnız opencode-app: kaynağı serve'ün opncd.ai paylaşımı.
  // AYRI bir alan ŞART: arayüz bu satırı onay diyaloğuyla çiziyor ve
  // desteklenmeyen bir backend'de o uyarıyı hiç göstermemeli.
  sessionShare: false,
  // GET /<b>/commands + POST /<b>/command — sağlayıcının KENDİ özel komutları
  // (opencode'da `/command` kataloğu: komutlar + skill'ler). Statik `/slash`
  // tablosundan AYRI: orada telefonun composer'a yazdığı istem şablonları var,
  // burada serve'ün çalıştırdığı gerçek komutlar.
  sessionCommands: false,
  // POST /<b>/init — projeyi analiz edip AGENTS.md yazan tur. Bugün yalnız
  // opencode-app (serve'ün /session/:id/init ucu).
  agentsInit: false,
  // GET /<b>/instructions + POST/DELETE — oturuma AGENTS.md'ye dokunmadan
  // kalıcı talimat parçaları (v2'nin experimental instructions entries'i).
  // Bugün yalnız opencode2-app; v1'in karşılığı yok.
  sessionInstructions: false,
  // GET /<b>/permissions/saved + POST /<b>/permissions/saved/delete — "her
  // zaman" verilen izinlerin birikmiş kural listesi. Bugün yalnız opencode2-app.
  savedPermissions: false,
  // POST /<b>/fork-from — orijinale dokunmadan seçilen mesajın öncesine kadar
  // kopya çocuk oturum. Bugün yalnız opencode2-app (v1'de karşılığı yok).
  sessionFork: false,
  // POST /<b>/rewind — sondan sayımlı kaba geri sarma (her turu düşürmez,
  // kullanıcı turlarını sayar). claude/codex/opencode/opencode2'de kayıtlı.
  sessionRewind: false,
  sessionPin: false,
  sessionArchive: false,
  sessionRename: false,
  sessionDelete: false,
};

// Ortak "app agent" yetenek tabanı (Claude/Codex/OpenCode native app-server oturumları).
// userInputSteer/userInputQueue TABANA GİRMEZ: hangi ucun gerçekten kayıtlı
// olduğu backend başına değişiyor (claude-app'te ikisi de yok, codex-app'te yalnız
// steer, opencode-app'te yalnız queue) ve tabana koymak arayüzü olmayan bir uca
// buton çizmeye iterdi.
const APP_AGENT = { approvals: true, userInput: true, permissionModes: true, context: true };

// Backend kataloğu — tek kaynak. `apiBackend` ağ katmanının kullandığı gerçek uçtur;
// cowork bir sunum + konfigürasyon katmanıdır ve claude-app uçlarını kullanır.
// `adapterBacked=false` olan backend'ler registerBackend'in tam yüzeyini uygulamaz
// (cowork delege eder, chatgpt-planner kendi rotasını kullanır); conformance testi
// yalnız adapter-backed modülleri denetler, katalog ise hepsini yayınlar.
export const BACKENDS = {
  'claude-app': { apiBackend: 'claude-app', label: 'Claude', adapterBacked: true, capabilities: { ...APP_AGENT, efforts: true, interrupt: true, sessionRewind: true, sessionPin: true, sessionArchive: true, sessionRename: true, sessionDelete: true } },
  'agy': { apiBackend: 'agy', label: 'Agy', adapterBacked: true, capabilities: { context: false, sessionDelete: true } },
  // codex-app'te /codex-app/steer VAR ama /follow-up YOK; üstelik telefonda
  // kendi eski "steer kipi" yolunu kullanıyor (CodexAppActionsDelegate), ortak
  // tur-içi composer'ı DEĞİL. userInputQueue=false onu ortak kipin dışında tutar.
  'codex-app': { apiBackend: 'codex-app', label: 'Codex', adapterBacked: true, capabilities: { ...APP_AGENT, userInputSteer: true, plan: true, efforts: true, sessionDiff: true, sessionRewind: true, sessionPin: true, sessionArchive: true, sessionRename: true, sessionDelete: true } },
  // opencode-app (v1) 30.09.2026'da SÖKÜLDÜ — npm paketi kaldırıldı, tek
  // OpenCode girişi opencode2-app. Onunla giden capability'ler: sessionShare
  // (v2'de paylaşım linki yok) ve subagents (v2'de alt-ajan konuşması yok).
  // opencode2-app: v1 sunucusuyla aynı uçları KULLANMAZ (yol öneki /api, Basic
  // auth, farklı olay sözlüğü). v2'de teslim kipi ikiye ayrılıyor
  // (delivery:"steer" | "queue"), yani v1'de olmayan tur-içi enjeksiyon da var;
  // ikisi de 24.09.2026'da canlı 2.0.16'ya karşı ölçüldü.
  // 26.09.2026: checkpoint/geri sarma (revert stage+commit), diff, compact,
  // komutlar ve pin/rename v2 uçlarıyla köprüye taşındı (ölçümler modülde).
  // STEER de açıldı: v2 teslim kipi delivery:"steer" süren tura anında
  // enjekte ediyor — v1'de hiç yoktu, telefonda "Yönlendir" tuşu bu uçla
  // çizilir (POST /opencode2-app/steer).
  // YOK olanlar: paylaşım linki, alt-ajan konuşmaları, görev panosu — v2
  // API'sinde karşıtları yok (openapi-v2.json tarandı), o yüzden capability
  // kapalı kalıyor. AGENTS.md init v2'nin gerçek "init" komutuyla koşuyor.
  // Etiket "OpenCode 2" DEĞİL "OpenCode": v1 söküldü, kullanıcı tek giriş görüyor.
  // `runpod` capability'si v1'den DEVRALINDI — pod denetimi uçları
  // /opencode2-app/runpod/* olarak taşındı (sunucu sürümünden bağımsız iş).
  'opencode2-app': { apiBackend: 'opencode2-app', label: 'OpenCode', adapterBacked: true, capabilities: { ...APP_AGENT, userInputSteer: true, userInputQueue: true, runpod: true, agents: true, sessionDiff: true, sessionRevert: true, sessionRewind: true, sessionFork: true, sessionCommands: true, agentsInit: true, sessionInstructions: true, savedPermissions: true, sessionPin: true, sessionRename: true, sessionDelete: true } },
  'omp': { apiBackend: 'omp', label: 'OMP', adapterBacked: true, capabilities: { ...APP_AGENT, userInputSteer: true, userInputQueue: true, efforts: true, sessionPin: true, sessionArchive: true, sessionRename: true, sessionDelete: true } },
  'cowork': { apiBackend: 'claude-app', label: 'Cowork', adapterBacked: false, capabilities: { ...APP_AGENT, efforts: true, interrupt: true, outputs: true } },
};

// Kısmi capability'yi şema varsayılanlarıyla tam nesneye tamamlar.
export function normalizeCapabilities(partial = {}) {
  const out = {};
  for (const [field, fallback] of Object.entries(CAPABILITY_SCHEMA)) {
    out[field] = field in partial ? !!partial[field] : fallback;
  }
  return out;
}

// cowork için provider'a göre türetilmiş yetenek: seçilen provider'ın yetenekleri +
// ortak workspace teslimatları. Android da aynı türetmeyi katalog üzerinden yapar.
export function coworkCapabilities(provider) {
  const base = BACKENDS[provider]?.capabilities ?? BACKENDS['claude-app'].capabilities;
  return normalizeCapabilities({ ...base, outputs: true });
}

export function describeBackend(id) {
  const d = BACKENDS[id];
  if (!d) return null;
  return {
    id,
    apiBackend: d.apiBackend || id,
    label: d.label || id,
    adapterBacked: !!d.adapterBacked,
    capabilities: normalizeCapabilities(d.capabilities),
  };
}

// Android'in /backends ucundan okuduğu versiyonlu katalog.
export function buildCatalog() {
  return {
    contractVersion: CONTRACT_VERSION,
    backends: Object.keys(BACKENDS).map(describeBackend),
  };
}

// Bir backend modülünün sözleşmeye uyup uymadığını denetler.
//  - adapter-backed ise tüm REQUIRED_METHODS export edilmiş olmalı;
//  - poll onay listesindeyse getPendingApproval export edilmiş olmalı;
//  - export edilmişse deleteDiskSession/getPendingApproval birer fonksiyon olmalı.
// Yan etkisiz; test paketi ve /backends canlı durum raporu bunu kullanır.
export function verifyAdapter(id, mod) {
  const descriptor = describeBackend(id);
  const problems = [];
  if (!descriptor) return { id, ok: false, problems: ['unknown backend id'] };

  if (descriptor.adapterBacked) {
    for (const method of REQUIRED_METHODS) {
      if (typeof mod?.[method] !== 'function') problems.push(`missing required method: ${method}`);
    }
  }
  if (POLL_APPROVAL_BACKENDS.includes(id) && typeof mod?.getPendingApproval !== 'function') {
    problems.push('poll-approval backend must export getPendingApproval');
  }
  for (const optional of ['getPendingApproval', 'deleteDiskSession', 'listDiskSessions', 'adoptSession']) {
    if (optional in (mod || {}) && typeof mod[optional] !== 'function') {
      problems.push(`optional export ${optional} is not a function`);
    }
  }
  return { id, ok: problems.length === 0, problems };
}
