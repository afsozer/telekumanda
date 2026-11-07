// Köprü kimlik doğrulaması.
//
// Köprü token'ı yalnız `Authorization: Bearer` başlığından kabul ediyor; adrese
// konan token proxy, tarayıcı geçmişi ve günlüklerde açıkta kalıyordu.
//
// - REST çağrıları başlığı kullanır.
// - Tarayıcı WebSocket el sıkışmasına başlık ekleyemediği için akış, başlıkla
//   alınan tek kullanımlık, 30 saniyelik bir biletle açılır (`POST /ws-ticket`,
//   bkz. api.ts `wsTicket`).
//
// İlk açılışta token adresin parçasından gelir (`/ui/#token=...`): `#` sonrası
// sunucuya hiç gönderilmez. Token yerel depoya alınır ve adresten SİLİNİR, yoksa
// tarayıcı geçmişinde dururdu. Eski `/ui/?token=...` biçimi de okunur ve silinir.

const STORAGE_KEY = 'agentbridge.token'

/** Adres parçasından (`#token=...`) token'ı ayıklar. Saf fonksiyon. */
export function tokenFromHash(hash: string): string | null {
  return tokenFromSearch(hash.startsWith('#') ? hash.slice(1) : hash)
}

/** Sorgu dizesinden token'ı ayıklar. Saf fonksiyon; testte doğrudan çağrılır. */
export function tokenFromSearch(search: string): string | null {
  const value = new URLSearchParams(search).get('token')
  const trimmed = value?.trim()
  return trimmed ? trimmed : null
}

/**
 * Token'ı adresten temizlenmiş hâliyle geri döndürür — yani `token` parametresi
 * çıkarılmış yeni sorgu dizesini. Diğer parametreler korunur.
 * Sorguda token yoksa `null` döner (adrese dokunmaya gerek yok demektir).
 */
export function searchWithoutToken(search: string): string | null {
  const params = new URLSearchParams(search)
  if (!params.has('token')) return null
  params.delete('token')
  const rest = params.toString()
  return rest ? `?${rest}` : ''
}

export function storedToken(): string | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return value && value.trim() ? value : null
  } catch {
    // Depolama kapalıysa (gizli sekme, üçüncü taraf çerez engeli) uygulama
    // token giriş ekranıyla çalışmaya devam etsin — çökmesin.
    return null
  }
}

export function saveToken(token: string): void {
  try {
    localStorage.setItem(STORAGE_KEY, token.trim())
  } catch {
    /* yoksay: aşağıdaki bellek kopyası oturum boyunca yeter */
  }
}

export function clearToken(): void {
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    /* yoksay */
  }
}

/**
 * Açılış akışı: adreste token varsa onu al, kaydet ve adresten temizle;
 * yoksa yerel depodakini kullan.
 */
export function bootstrapToken(location: Location, history: History): string | null {
  const fromHash = tokenFromHash(location.hash)
  if (fromHash) {
    saveToken(fromHash)
    history.replaceState(null, '', `${location.pathname}${location.search}`)
    return fromHash
  }
  const fromUrl = tokenFromSearch(location.search)
  if (fromUrl) {
    saveToken(fromUrl)
    const cleaned = searchWithoutToken(location.search)
    if (cleaned !== null) {
      history.replaceState(null, '', `${location.pathname}${cleaned}${location.hash}`)
    }
    return fromUrl
  }
  return storedToken()
}
